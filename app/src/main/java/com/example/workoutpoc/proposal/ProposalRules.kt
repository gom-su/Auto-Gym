package com.example.workoutpoc.proposal

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
    val newExercise: Severity = Severity.WARNING
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
    val alreadyApplied: Boolean
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
    fun evaluate(proposal: WeekProposal, library: ProgramLibrary, knownExerciseIds: Set<String>, settings: RuleSettings): ProposalEvaluation {
        val issues = mutableListOf<ValidationIssue>()
        fun add(code: String, severity: Severity, message: String) { if (severity != Severity.OFF) issues += ValidationIssue(code, severity, message) }
        val week = proposal.week
        val program = library.programs.find { it.id == proposal.programId }
        val block = program?.blocks?.find { it.id == proposal.blockId }
        var alreadyApplied = false
        when {
            program == null -> add("PROGRAM_MISSING", Severity.BLOCKING, "El programa ${proposal.programId} no está guardado en este dispositivo.")
            block == null -> add("BLOCK_MISSING", Severity.BLOCKING, "El bloque ${proposal.blockId} no existe en ${program.name}.")
            else -> {
                val existing = program.blocks.flatMap { it.weeks }.find { it.id == week.id }
                if (existing != null && existing == week && block.weeks.any { it.id == week.id }) alreadyApplied = true
                else if (existing != null) add("WEEK_EXISTS", Severity.BLOCKING,
                    "La semana ${week.id} ya existe en el programa con otro contenido. Una propuesta no reemplaza semanas guardadas; usa un ID nuevo.")
                else {
                    // Appending must keep the program valid, e.g. no proposal ID may reuse a saved day or set ID.
                    try {
                        val plan = ProgramMerge.plan(program, program.copy(blocks = program.blocks.map { if (it.id == block.id) it.copy(weeks = it.weeks + week) else it }))
                        plan.conflicts.forEach { add("INTEGRITY", Severity.BLOCKING, it) }
                    } catch (e: ProgramValidationException) {
                        add("INTEGRITY", Severity.BLOCKING, "La semana propuesta reutiliza identificadores guardados: ${e.message}")
                    }
                }
            }
        }
        val base = block?.weeks?.lastOrNull { it.id != week.id }
        val differences = mutableListOf<String>()
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

    private fun range(a: Int?, b: Int?) = if (a == null) "—" else if (a == b) "$a" else "$a–$b"
    private fun Double.fmt() = if (this == toLong().toDouble()) toLong().toString() else toString()
    private fun Set<Pair<LoadUnit, LoadBasis>>.label() = joinToString(", ") { "${it.first} ${it.second}" }
}
