package com.example.workoutpoc.exchange

import com.example.workoutpoc.domain.*
import com.example.workoutpoc.planning.LoadBasis
import com.example.workoutpoc.planning.LoadUnit
import com.example.workoutpoc.planning.PrescribedLoad
import kotlinx.serialization.Serializable
import java.time.Instant

/*
 * Frozen interchange v1 shape of the legacy demo snapshot. It mirrors the pre-ENT-04 WorkoutState field by field
 * so that recording-model changes can never silently alter the published v1 JSON contract.
 */
@Serializable data class DemoSetValuesV1(val weightTenths: Int = 300, val reps: Int = 8, val rir: Int = 2)
@Serializable data class DemoSetRecordV1(val index: Int, val exercise: String, val number: Int,
                                         val values: DemoSetValuesV1, val completedAtMillis: Long)
@Serializable data class DemoStateV1(
    val sessionId: String = "", val phase: Phase = Phase.Idle, val index: Int = 0, val revision: Long = 0,
    val values: DemoSetValuesV1 = DemoSetValuesV1(), val records: List<DemoSetRecordV1> = emptyList(),
    val rest: RestDeadline? = null, val endedEarly: Boolean = false
)

/** Only the built-in demo is exported in the legacy snapshot; planned sessions use [toSessionResult]. */
fun WorkoutState.toDemoV1(): DemoStateV1? {
    if (source != null || phase == Phase.Idle) return null
    fun v(x: SetValues): DemoSetValuesV1? = x.rir?.let { DemoSetValuesV1(x.weightTenths, x.reps, it) }
    val recordsV1 = records.map { DemoSetRecordV1(it.index, it.exercise, it.number, v(it.values) ?: return null, it.completedAtMillis) }
    return DemoStateV1(sessionId, phase, index, revision, v(values) ?: return null, recordsV1, rest, endedEarly)
}

private fun iso(millis: Long?): String? = millis?.let { Instant.ofEpochMilli(it).toString() }

/**
 * Maps a planned session's confirmed sets onto interchange v1 results. Values are reported in the unit recorded,
 * without conversion. A load whose base was never prescribed is not given an invented base: it is omitted from
 * the typed load and stated in the set notes instead. Unrecorded RIR stays null.
 */
fun WorkoutState.toSessionResult(): SessionResult? {
    val src = source ?: return null
    if (records.isEmpty()) return null
    val finished = phase == Phase.Completed
    val sets = records.map { r ->
        val p = plan[r.index]
        val notes = mutableListOf<String>()
        val load: PrescribedLoad? = when (p.unit) {
            WeightUnit.KG, WeightUnit.LB -> {
                val unit = if (p.unit == WeightUnit.KG) LoadUnit.KG else LoadUnit.LB
                val basis = p.basis?.let { b -> LoadBasis.entries.find { it.name == b } }
                if (basis != null) PrescribedLoad(r.values.weightTenths / 10.0, unit, basis)
                else { notes += "Carga registrada: ${loadLabel(r.values.weightTenths, p.unit)}; base no indicada en la prescripción."; null }
            }
            WeightUnit.MACHINE_SETTING -> PrescribedLoad(r.values.weightTenths / 10.0, LoadUnit.MACHINE_SETTING, LoadBasis.MACHINE_SETTING)
            WeightUnit.BODYWEIGHT -> {
                if (r.values.weightTenths > 0) notes += "Carga añadida al peso corporal: ${weightLabel(r.values.weightTenths)} kg."
                PrescribedLoad(0.0, LoadUnit.BODYWEIGHT, LoadBasis.BODYWEIGHT)
            }
        }
        if (r.values.rir == 4) notes += "RIR 4 registrado como 4 o más."
        SetResult(
            id = "$sessionId:${r.index}", prescriptionId = r.prescriptionId,
            exerciseId = p.exerciseId ?: "unknown-exercise", status = PerformanceStatus.COMPLETED,
            reps = r.values.reps, rir = r.values.rir, load = load,
            completedAt = iso(r.completedAtMillis), notes = notes.joinToString(" ").ifBlank { null }
        )
    }
    return SessionResult(
        id = sessionId, sessionPlanId = src.dayId,
        status = if (finished && !endedEarly && records.size == plan.size) PerformanceStatus.COMPLETED else PerformanceStatus.PARTIAL,
        startedAt = iso(startedAtMillis), endedAt = if (finished) iso(endedAtMillis) else null,
        notes = "Programa ${src.programId} · semana ${src.weekId} v${src.weekVersion} · ${records.size}/${plan.size} series registradas.",
        sets = sets, cardio = emptyList()
    )
}
