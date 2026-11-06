package com.example.workoutpoc.planning

enum class MergeKind { NEW, IDENTICAL, ADDS_WEEKS, CONFLICT }

/**
 * Deterministic outcome of importing [incoming] over the saved program with the same ID.
 * Nothing already saved is ever replaced, reordered or deleted: identical content is a no-op, weeks with new IDs are
 * appended, weeks missing from the package are kept, and any difference in an existing element is a conflict.
 */
data class MergePlan(
    val kind: MergeKind, val program: TrainingProgram,
    val addedWeeks: List<String> = emptyList(), val unchangedWeeks: Int = 0, val keptWeeks: List<String> = emptyList(),
    val conflicts: List<String> = emptyList()
) {
    /** Human summary for review and save messages. */
    fun summary(): String = when (kind) {
        MergeKind.NEW -> "Programa nuevo: ${program.blocks.sumOf { it.weeks.size }} semanas."
        MergeKind.IDENTICAL -> "Este programa ya estaba importado con el mismo contenido. No se crearán duplicados."
        MergeKind.ADDS_WEEKS -> "Se añadirán ${addedWeeks.size} semanas nuevas (${addedWeeks.joinToString(", ")}); $unchangedWeeks sin cambios no se duplican." +
            (if (keptWeeks.isNotEmpty()) " Se conservan ${keptWeeks.size} semanas guardadas que el paquete no incluye." else "")
        MergeKind.CONFLICT -> "Ya existe un programa con ID '${program.id}' y contenido diferente. No se guardará nada:\n" +
            conflicts.take(MAX_LISTED).joinToString("\n") { "• $it" } +
            (if (conflicts.size > MAX_LISTED) "\n• y ${conflicts.size - MAX_LISTED} diferencias más." else "") +
            "\nPara cambiar una semana guardada, impórtala con un ID nuevo."
    }
    companion object { const val MAX_LISTED = 20 }
}

object ProgramMerge {
    fun plan(existing: TrainingProgram?, incoming: TrainingProgram): MergePlan {
        ProgramCodec.validate(incoming)
        if (existing == null) return MergePlan(MergeKind.NEW, incoming)
        require(existing.id == incoming.id)
        if (existing == incoming) return MergePlan(MergeKind.IDENTICAL, existing, unchangedWeeks = existing.blocks.sumOf { it.weeks.size })
        val conflicts = mutableListOf<String>()
        if (existing.name != incoming.name) conflicts += "Nombre del programa: '${existing.name}' → '${incoming.name}'."
        val existingWeeks = existing.blocks.flatMap { b -> b.weeks.map { it.id to b.id } }.toMap()
        val added = mutableListOf<String>()
        var unchanged = 0
        val blocks = existing.blocks.toMutableList()
        incoming.blocks.forEach { inBlock ->
            val index = blocks.indexOfFirst { it.id == inBlock.id }
            if (index < 0) {
                inBlock.weeks.filter { it.id in existingWeeks }.forEach { conflicts += "Semana '${it.name}' (${it.id}) aparece en otro bloque." }
                blocks += inBlock; added += inBlock.weeks.map { it.name }; return@forEach
            }
            val saved = blocks[index]
            if (saved.name != inBlock.name) conflicts += "Bloque ${saved.id}: nombre '${saved.name}' → '${inBlock.name}'."
            val weeks = saved.weeks.toMutableList()
            inBlock.weeks.forEach { week ->
                val old = saved.weeks.find { it.id == week.id }
                when {
                    old == null && week.id in existingWeeks -> conflicts += "Semana '${week.name}' (${week.id}) aparece en otro bloque."
                    old == null -> { weeks += week; added += week.name }
                    old == week -> unchanged++
                    else -> conflicts += weekDifferences(old, week)
                }
            }
            blocks[index] = saved.copy(weeks = weeks)
        }
        val incomingWeekIds = incoming.blocks.flatMap { b -> b.weeks.map { it.id } }.toSet()
        val kept = existing.blocks.flatMap { it.weeks }.filter { it.id !in incomingWeekIds }.map { it.name }
        val merged = existing.copy(blocks = blocks)
        if (conflicts.isEmpty()) {
            // New weeks must not reuse IDs of saved elements (e.g. a set ID from another week).
            try { ProgramCodec.validate(merged) } catch (e: ProgramValidationException) { conflicts += "Las semanas nuevas reutilizan identificadores guardados: ${e.message}" }
        }
        return if (conflicts.isNotEmpty()) MergePlan(MergeKind.CONFLICT, existing, added, unchanged, kept, conflicts)
        else if (added.isEmpty()) MergePlan(MergeKind.IDENTICAL, existing, unchangedWeeks = unchanged, keptWeeks = kept)
        else MergePlan(MergeKind.ADDS_WEEKS, merged, added, unchanged, kept)
    }

    /** Lists every difference between two versions of the same saved week, by path. */
    private fun weekDifferences(old: WeekPlan, new: WeekPlan): List<String> {
        val out = mutableListOf<String>()
        val w = "Semana '${old.name}'"
        if (old.name != new.name) out += "$w: nombre → '${new.name}'."
        if (old.version != new.version) out += "$w: versión ${old.version} → ${new.version}."
        diffIds(old.days.map { it.id }, new.days.map { it.id }, "$w: días", out)
        old.days.forEach { d ->
            val nd = new.days.find { it.id == d.id } ?: return@forEach
            val dp = "$w / ${d.name}"
            if (d.name != nd.name) out += "$dp: nombre → '${nd.name}'."
            diffIds(d.exercises.map { it.id }, nd.exercises.map { it.id }, "$dp: ejercicios", out)
            d.exercises.forEach { e ->
                val ne = nd.exercises.find { it.id == e.id } ?: return@forEach
                val ep = "$dp / ${e.name}"
                if (e.name != ne.name) out += "$ep: nombre → '${ne.name}'."
                if (e.exerciseId != ne.exerciseId) out += "$ep: ejercicio ${e.exerciseId} → ${ne.exerciseId}."
                diffIds(e.sets.map { it.id }, ne.sets.map { it.id }, "$ep: series", out)
                e.sets.forEachIndexed { i, s ->
                    val ns = ne.sets.find { it.id == s.id } ?: return@forEachIndexed
                    val sp = "$ep / serie ${i + 1}"
                    if (s.reps != ns.reps) out += "$sp: repeticiones ${s.reps ?: "—"} → ${ns.reps ?: "—"}."
                    if (s.rir != ns.rir) out += "$sp: RIR ${s.rir ?: "—"} → ${ns.rir ?: "—"}."
                    if (s.load != ns.load) out += "$sp: carga ${s.load.label()} → ${ns.load.label()}."
                    if (s.restSeconds != ns.restSeconds) out += "$sp: descanso ${s.restSeconds ?: "—"} → ${ns.restSeconds ?: "—"} s."
                }
            }
        }
        return out.ifEmpty { listOf("$w: contenido diferente.") }
    }

    private fun diffIds(old: List<String>, new: List<String>, path: String, out: MutableList<String>) {
        val removed = old - new.toSet(); val added = new - old.toSet()
        if (removed.isNotEmpty()) out += "$path eliminados: ${removed.joinToString(", ")}."
        if (added.isNotEmpty()) out += "$path añadidos: ${added.joinToString(", ")}."
        if (removed.isEmpty() && added.isEmpty() && old != new) out += "$path en otro orden."
    }

    private fun PrescribedLoad?.label() = this?.let { "${it.value} ${it.unit} ${it.basis}" } ?: "—"
}
