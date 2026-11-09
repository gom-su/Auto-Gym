package com.example.workoutpoc.proposal

import com.example.workoutpoc.exchange.SessionResult
import com.example.workoutpoc.exchange.WeekProposal
import com.example.workoutpoc.planning.*
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable enum class Severity { OFF, WARNING, BLOCKING }

/** User-configurable progression and coherence rules. Integrity rules are always blocking and not configurable. */
@Serializable data class RuleSettings(
    val schemaVersion: Int = 1,
    val maxLoadIncreasePercent: Int = 10, val loadIncrease: Severity = Severity.WARNING,
    val maxSetIncreasePercent: Int = 50, val setIncrease: Severity = Severity.WARNING,
    val unitChange: Severity = Severity.BLOCKING,
    val rirZero: Severity = Severity.WARNING,
    val newExercise: Severity = Severity.WARNING,
    /** A computed change for which the agent gave no reason mentioning the exercise (AG-07). */
    val unexplainedChange: Severity = Severity.WARNING,
    /** Reps, RIR, sets, exercises or deload volume outside the activated multi-week plan's targets for that week. */
    val outlineTargets: Severity = Severity.WARNING
) {
    fun validate() {
        require(schemaVersion == 1) { "Versión de reglas no compatible." }
        require(maxLoadIncreasePercent in 0..1000 && maxSetIncreasePercent in 0..1000) { "Umbral fuera de rango (0–1000 %)." }
    }
}

data class ValidationIssue(val code: String, val severity: Severity, val message: String)

/** Deterministic review of a proposal against the saved library. The agent's stated differences are shown separately. */
data class ProposalEvaluation(
    val proposal: WeekProposal, val base: WeekPlan?, val issues: List<ValidationIssue>, val differences: List<String>,
    /** The proposed week is already in the program exactly as proposed (an approval whose decision was not yet recorded). */
    val alreadyApplied: Boolean,
    val explanations: List<ExplainedChange> = emptyList()
) {
    val blocking get() = issues.filter { it.severity == Severity.BLOCKING }
    val warnings get() = issues.filter { it.severity == Severity.WARNING }
    val canApprove get() = blocking.isEmpty()
}

/** Per-exercise aggregate of one week, keyed by stable exercise ID. */
private data class ExerciseSummary(val name: String, val sets: Int, val loads: Map<Pair<LoadUnit, LoadBasis>, Double>,
                                   val minReps: Int?, val maxReps: Int?, val minRir: Int?)

private fun WeekPlan.summary(): Map<String, ExerciseSummary> =
    days.flatMap { it.exercises }.groupBy { it.exerciseId }.mapValues { (_, list) ->
        val sets = list.flatMap { it.sets }
        ExerciseSummary(list.first().name, sets.size,
            sets.mapNotNull { it.load }.groupBy { it.unit to it.basis }.mapValues { (_, l) -> l.maxOf { it.value } },
            sets.mapNotNull { it.reps }.minOrNull(), sets.mapNotNull { it.reps }.maxOrNull(), sets.mapNotNull { it.rir }.minOrNull())
    }

object ProposalRules {
    /**
     * Evaluates [proposal] against the saved [library]. Comparisons use the base week: the last saved week of the
     * target block. [knownExerciseIds] are IDs seen in programs, history or the catalog.
     */
    /**
     * The program as it would be after approving [p]: the week appended to its block. With an activated [outline],
     * a block the plan defines is created when its first week arrives, and so is the program itself for week 1.
     * Null when the target program or block is unknown.
     */
    fun appliedProgram(p: WeekProposal, program: TrainingProgram?, outline: PlanOutline?): TrainingProgram? {
        val planned = outline?.takeIf { it.programId == p.programId }?.blocks?.find { it.id == p.blockId }
        return when {
            program == null -> planned?.let { TrainingProgram(p.programId, outline.programName, listOf(TrainingBlock(it.id, it.name, listOf(p.week)))) }
            program.blocks.any { it.id == p.blockId } -> program.copy(blocks = program.blocks.map { if (it.id == p.blockId) it.copy(weeks = it.weeks + p.week) else it })
            planned != null -> program.copy(blocks = program.blocks + TrainingBlock(planned.id, planned.name, listOf(p.week)))
            else -> null
        }
    }

    fun evaluate(proposal: WeekProposal, library: ProgramLibrary, knownExerciseIds: Set<String>, settings: RuleSettings,
                 outline: PlanOutline? = null, sessions: List<SessionResult> = emptyList()): ProposalEvaluation {
        val issues = mutableListOf<ValidationIssue>()
        fun add(code: String, severity: Severity, message: String) { if (severity != Severity.OFF) issues += ValidationIssue(code, severity, message) }
        val week = proposal.week
        val program = library.programs.find { it.id == proposal.programId }
        val block = program?.blocks?.find { it.id == proposal.blockId }
        val plan = outline?.takeIf { it.programId == proposal.programId }
        val plannedBlock = plan?.blocks?.find { it.id == proposal.blockId }
        var alreadyApplied = false
        val differences = mutableListOf<String>()
        when {
            program == null && plannedBlock == null -> add("PROGRAM_MISSING", Severity.BLOCKING, "El programa ${proposal.programId} no está guardado en este dispositivo" +
                (if (plan != null) " y el bloque ${proposal.blockId} no está en su plan." else "."))
            program != null && block == null && plannedBlock == null -> add("BLOCK_MISSING", Severity.BLOCKING, "El bloque ${proposal.blockId} no existe en ${program.name}" +
                (if (plan != null) " ni en su plan." else "."))
            else -> {
                val existing = program?.blocks?.flatMap { it.weeks }?.find { it.id == week.id }
                if (existing != null && existing == week && block?.weeks?.any { it.id == week.id } == true) alreadyApplied = true
                else if (existing != null) add("WEEK_EXISTS", Severity.BLOCKING,
                    "La semana ${week.id} ya existe en el programa con otro contenido. Una propuesta no reemplaza semanas guardadas; usa un ID nuevo.")
                else {
                    // Appending must keep the program valid, e.g. no proposal ID may reuse a saved day or set ID.
                    val applied = appliedProgram(proposal, program, plan)!!
                    if (program == null) differences += "Crea el programa ${applied.name} con el bloque ${plannedBlock!!.name}."
                    else if (block == null) differences += "Empieza el bloque ${plannedBlock!!.name} del plan."
                    try {
                        if (program == null) ProgramCodec.validate(applied)
                        else ProgramMerge.plan(program, applied).conflicts.forEach { add("INTEGRITY", Severity.BLOCKING, it) }
                    } catch (e: ProgramValidationException) {
                        add("INTEGRITY", Severity.BLOCKING, "La semana propuesta reutiliza identificadores guardados: ${e.message}")
                    }
                }
            }
        }
        val base = if (plan != null) outlineChecks(proposal, program, plan, alreadyApplied, sessions, settings, ::add)
            else block?.weeks?.lastOrNull { it.id != week.id }
        val proposed = week.summary()
        week.days.flatMap { it.exercises }.flatMap { e -> e.sets.filter { it.rir == 0 }.map { e.name } }.distinct().forEach {
            add("RIR_ZERO", settings.rirZero, "$it: prescribe series al fallo (RIR 0).")
        }
        proposed.forEach { (id, p) -> if (id !in knownExerciseIds) add("NEW_EXERCISE", settings.newExercise, "${p.name} ($id) no aparece en programas, historial ni catálogo.") }
        if (base == null) {
            differences += "Sin semana base guardada en el bloque: no hay comparación de progresión."
        } else {
            val before = base.summary()
            if (base.days.size != week.days.size) differences += "Días: ${base.days.size} → ${week.days.size}."
            (before.keys - proposed.keys).forEach { differences += "Se elimina ${before.getValue(it).name}." }
            (proposed.keys - before.keys).forEach { differences += "Se añade ${proposed.getValue(it).name}: ${proposed.getValue(it).sets} series." }
            proposed.keys.intersect(before.keys).forEach { id ->
                val b = before.getValue(id); val p = proposed.getValue(id)
                if (b.sets != p.sets) differences += "${p.name}: series ${b.sets} → ${p.sets}."
                if (b.minReps != p.minReps || b.maxReps != p.maxReps) differences += "${p.name}: repeticiones ${range(b.minReps, b.maxReps)} → ${range(p.minReps, p.maxReps)}."
                if (b.minRir != p.minRir) differences += "${p.name}: RIR mínimo ${b.minRir ?: "—"} → ${p.minRir ?: "—"}."
                if (b.sets > 0 && p.sets > b.sets) {
                    val pct = ((p.sets - b.sets) * 100.0 / b.sets).roundToInt()
                    if (pct > settings.maxSetIncreasePercent) add("SET_INCREASE", settings.setIncrease, "${p.name}: +$pct % de series (máximo ${settings.maxSetIncreasePercent} %).")
                }
                if (b.loads.isNotEmpty() && p.loads.isNotEmpty() && b.loads.keys != p.loads.keys)
                    add("UNIT_CHANGE", settings.unitChange, "${p.name}: cambia unidad o base de carga (${b.loads.keys.label()} → ${p.loads.keys.label()}); las cargas no son comparables.")
                p.loads.forEach { (key, value) ->
                    val old = b.loads[key] ?: return@forEach
                    if (old != value) differences += "${p.name}: carga máxima ${old.fmt()} → ${value.fmt()} ${key.first} ${key.second}."
                    if (old > 0 && value > old) {
                        val pct = ((value - old) * 100 / old).roundToInt()
                        if (pct > settings.maxLoadIncreasePercent) add("LOAD_INCREASE", settings.loadIncrease,
                            "${p.name}: carga +$pct % (${old.fmt()} → ${value.fmt()} ${key.first}; máximo ${settings.maxLoadIncreasePercent} %).")
                    }
                }
            }
            if (differences.isEmpty()) differences += "Sin cambios respecto a ${base.name}."
        }
        return ProposalEvaluation(proposal, base, issues, differences, alreadyApplied)
    }

    /**
     * Checks against the activated plan and returns the base week for comparisons: the previous week of the plan,
     * skipping deloads unless there is nothing else, so progression after a deload is measured against real work.
     */
    private fun outlineChecks(p: WeekProposal, program: TrainingProgram?, plan: PlanOutline, alreadyApplied: Boolean,
                              sessions: List<SessionResult>, settings: RuleSettings,
                              add: (String, Severity, String) -> Unit): WeekPlan? {
        val weeks = PlanOutlines.programWeeks(program)
        val position = if (alreadyApplied) weeks.indexOfFirst { it.second.id == p.week.id } + 1 else weeks.size + 1
        PlanOutlines.conflicts(plan, program).forEach { add("OUTLINE_PROGRAM", Severity.BLOCKING, it) }
        if (position > plan.weeks.size) {
            add("OUTLINE_COMPLETE", Severity.BLOCKING, "El plan ${plan.name} tiene ${plan.weeks.size} semanas; no se añade una semana $position. El ciclo requiere evaluación.")
            return weeks.lastOrNull()?.second
        }
        val target = plan.weeks[position - 1]
        if (target.blockId != p.blockId) add("OUTLINE_BLOCK", Severity.BLOCKING,
            "La semana $position del plan pertenece al bloque ${target.blockId}, no a ${p.blockId}.")
        val previous = weeks.take(position - 1).map { it.second }
        if (!alreadyApplied && previous.isNotEmpty()) {
            val last = previous.last()
            if (last.days.none { d -> sessions.any { it.sessionPlanId == d.id && PlanOutlines.hasRealData(it) } })
                add("OUTLINE_NO_DATA", settings.outlineTargets, "La semana ${position - 1} (${last.name}) aún no tiene registros reales; la semana $position se planifica sin evidencia.")
        }
        val templateIds = plan.days.flatMap { d -> d.exercises.map { it.exerciseId } }.toSet()
        val exercises = p.week.days.flatMap { it.exercises }
        exercises.map { it.exerciseId to it.name }.distinctBy { it.first }.filter { it.first !in templateIds }.forEach { (id, name) ->
            add("OUTLINE_EXERCISE", settings.outlineTargets, "$name ($id) no está en la distribución de días del plan.")
        }
        fun targetFor(exerciseId: String) = target.targets.find { exerciseId in it.exerciseIds } ?: target.targets.find { it.exerciseIds.isEmpty() }
        exercises.groupBy { it.exerciseId }.forEach { (id, list) ->
            val t = targetFor(id) ?: return@forEach
            val name = list.first().name
            val sets = list.flatMap { it.sets }
            val reps = sets.mapNotNull { it.reps }
            val rirs = sets.mapNotNull { it.rir }
            if ((t.repsMin != null && reps.any { it < t.repsMin }) || (t.repsMax != null && reps.any { it > t.repsMax }))
                add("OUTLINE_REPS", settings.outlineTargets, "$name: repeticiones ${range(reps.minOrNull(), reps.maxOrNull())} fuera del objetivo ${range(t.repsMin, t.repsMax)} de ${t.label} (semana $position).")
            if (t.rirMin != null && rirs.any { it < t.rirMin })
                add("OUTLINE_RIR", settings.outlineTargets, "$name: RIR ${rirs.min()} por debajo del mínimo ${t.rirMin} de ${t.label} (semana $position).")
            if (t.sets != null && list.any { it.sets.size > t.sets })
                add("OUTLINE_SETS", settings.outlineTargets, "$name: ${list.maxOf { it.sets.size }} series; el plan indica ${t.sets} en ${t.label} (semana $position).")
        }
        val base = previous.withIndex().lastOrNull { (i, _) -> !plan.weeks[i].deload }?.value ?: previous.lastOrNull()
        if (target.deload && base != null) {
            val before = base.days.sumOf { d -> d.exercises.sumOf { it.sets.size } }
            val now = p.week.days.sumOf { d -> d.exercises.sumOf { it.sets.size } }
            if (now > before) add("OUTLINE_DELOAD", settings.outlineTargets, "La semana $position es de descarga y tiene $now series frente a $before de ${base.name}.")
        }
        return base
    }

    private fun range(a: Int?, b: Int?) = if (a == null && b == null) "—" else if (a == b) "$a" else "${a ?: "—"}–${b ?: "—"}"
    private fun Double.fmt() = if (this == toLong().toDouble()) toLong().toString() else toString()
    private fun Set<Pair<LoadUnit, LoadBasis>>.label() = joinToString(", ") { "${it.first} ${it.second}" }
}
