package com.example.workoutpoc.catalog

import com.example.workoutpoc.domain.*

/** One recorded set within an exposure, as recorded. */
data class ExposureSet(val values: SetValues, val warmup: Boolean, val extra: Boolean, val skipped: Boolean, val recordedName: String)

/**
 * One session's work on an exercise in a single load unit and basis. Summary figures use completed work sets only
 * (warm-ups and omitted sets are shown but never counted), so different exposures stay comparable.
 */
data class Exposure(
    val sessionId: String, val startedAtMillis: Long, val label: String, val sets: List<ExposureSet>
) {
    private val work get() = sets.filter { !it.warmup && !it.skipped }
    val workSets get() = work.size
    val topLoadTenths: Int? get() = work.maxOfOrNull { it.values.weightTenths }
    val bestRepsAtTopLoad: Int? get() = topLoadTenths?.let { top -> work.filter { it.values.weightTenths == top }.maxOf { it.values.reps } }
    val totalReps get() = work.sumOf { it.values.reps }
    /** Load × reps in tenths of the group's unit; only meaningful within one unit and basis, never across groups. */
    val volumeTenths: Long get() = work.sumOf { it.values.weightTenths.toLong() * it.values.reps }
    val minRir: Int? get() = work.mapNotNull { it.values.rir }.minOrNull()
}

/** Exposures that share a unit and load basis; values are only ever compared inside one group. */
data class ExposureGroup(val unit: WeightUnit, val basis: String?, val exposures: List<Exposure>)

fun basisLabel(basis: String?) = when (basis) {
    "TOTAL" -> "total"; "PER_SIDE" -> "por lado"; "PER_HAND" -> "por mano"; "BODYWEIGHT" -> "peso corporal"
    "MACHINE_SETTING" -> "posición"; null -> "base sin indicar"; else -> basis.lowercase()
}

fun unitLabel(unit: WeightUnit) = when (unit) {
    WeightUnit.KG -> "kg"; WeightUnit.LB -> "lb"; WeightUnit.MACHINE_SETTING -> "posición de máquina"; WeightUnit.BODYWEIGHT -> "peso corporal (+ kg)"
}

/**
 * All exposures to [exerciseId] across sessions, weeks and programs, matched by ID only and grouped by unit and
 * load basis so that kg, lb, machine positions, bodyweight and different bases are never mixed. Newest first.
 */
fun exposures(exerciseId: String, sessions: List<WorkoutState>): List<ExposureGroup> {
    data class Key(val unit: WeightUnit, val basis: String?)
    val byGroup = linkedMapOf<Key, MutableList<Exposure>>()
    sessions.distinctBy { it.sessionId }.forEach { s ->
        val bySessionGroup = linkedMapOf<Key, MutableList<ExposureSet>>()
        s.records.forEach { r ->
            val p = s.plan.getOrNull(r.index) ?: return@forEach
            if (p.exerciseId != exerciseId) return@forEach
            bySessionGroup.getOrPut(Key(p.unit, p.basis)) { mutableListOf() } +=
                ExposureSet(r.values, p.warmup, p.extra, r.status == SetStatus.SKIPPED, p.exercise)
        }
        val label = s.source?.let { "${it.programName} · ${it.weekName} · ${it.dayName}" } ?: "Sesión"
        val start = s.startedAtMillis ?: s.records.minOfOrNull { it.completedAtMillis } ?: 0
        bySessionGroup.forEach { (key, sets) -> byGroup.getOrPut(key) { mutableListOf() } += Exposure(s.sessionId, start, label, sets) }
    }
    return byGroup.map { (key, list) -> ExposureGroup(key.unit, key.basis, list.sortedByDescending { it.startedAtMillis }) }
        .sortedByDescending { g -> g.exposures.maxOf { it.startedAtMillis } }
}
