package com.example.workoutpoc.domain

import kotlinx.serialization.Serializable

@Serializable
enum class Phase { Idle, ActiveSet, Resting, Completed }

/** Recording unit. Values are integer tenths of this unit; BODYWEIGHT tenths are added external load in kg. */
@Serializable
enum class WeightUnit { KG, LB, MACHINE_SETTING, BODYWEIGHT }

/**
 * One executable set. weightTenths/reps/rir are the prefilled values derived from the prescription;
 * reps = 0 and rir = null mean the prescription did not state them (never invented).
 * loadPrescribed = false means no load was prescribed: the prefilled 0 is a starting point, not a target.
 */
@Serializable
data class PlannedSet(
    val exercise: String, val number: Int, val total: Int,
    val weightTenths: Int, val reps: Int, val rir: Int?,
    val prescriptionId: String? = null, val exerciseId: String? = null,
    val unit: WeightUnit = WeightUnit.KG, val basis: String? = null, val loadPrescribed: Boolean = true,
    val restSeconds: Int = 10, val weightStepTenths: Int = 25, val maxWeightTenths: Int = 3000, val maxReps: Int = 30,
    /** Added during the session; it has no prescription of its own and is never reported as prescribed. */
    val extra: Boolean = false
)

/** Integer tenths of the set's unit avoid floating point drift; rir=4 means 4+, null means not recorded. */
@Serializable
data class SetValues(val weightTenths: Int = 300, val reps: Int = 8, val rir: Int? = 2)

@Serializable
enum class SetStatus { COMPLETED, SKIPPED }

/**
 * What was actually done for one plan position. The prescription stays in the session plan; edits change only
 * these recorded values and are marked with editedAtMillis. A SKIPPED set's values are not performance data.
 */
@Serializable
data class SetRecord(val index: Int, val exercise: String, val number: Int,
                     val values: SetValues, val completedAtMillis: Long, val prescriptionId: String? = null,
                     val status: SetStatus = SetStatus.COMPLETED, val notes: String? = null, val editedAtMillis: Long? = null)

const val MAX_NOTE_LENGTH = 1000

/** Durable monotonic deadline, with wall time fallback only after a reboot. */
@Serializable
data class RestDeadline(val elapsedMillis: Long, val wallMillis: Long, val bootCount: Int)

/** Where a planned session came from. Names are display snapshots; IDs are the identity. */
@Serializable
data class SessionSource(
    val programId: String, val programName: String, val blockId: String,
    val weekId: String, val weekName: String, val weekVersion: Int,
    val dayId: String, val dayName: String
)

@Serializable
data class WorkoutState(
    val sessionId: String = "",
    val phase: Phase = Phase.Idle,
    val index: Int = 0,
    val revision: Long = 0,
    val values: SetValues = SetValues(),
    val records: List<SetRecord> = emptyList(),
    val rest: RestDeadline? = null,
    val endedEarly: Boolean = false,
    /** Empty means the built-in demo plan, which keeps pre-ENT-04 snapshots readable. */
    val plan: List<PlannedSet> = emptyList(),
    val source: SessionSource? = null,
    val startedAtMillis: Long? = null,
    val endedAtMillis: Long? = null
)

enum class WorkoutEvent {
    StartWorkout, IncrementWeight, DecrementWeight, IncrementReps, DecrementReps,
    SelectRir, CompleteSet, AddRestTime, SkipRest, RestFinished, UndoLastSet, ResetDemo,
    FinishWorkout,
    /** Planned sessions only: omit the current set, add an extra set of the last exercise, remove an unrecorded extra. */
    SkipSet, AddSet, RemoveExtraSet
}

/** Every command targets an exact snapshot. Old notification/overlay taps become no-ops. */
data class WorkoutCommand(val event: WorkoutEvent, val sessionId: String,
                          val index: Int, val revision: Long, val rir: Int? = null)

data class ClockSnapshot(val elapsedMillis: Long, val wallMillis: Long, val bootCount: Int)

fun WorkoutState.command(event: WorkoutEvent, rir: Int? = null): WorkoutCommand =
    WorkoutCommand(event, sessionId, index, revision, rir)

fun weightLabel(tenths: Int): String = if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10},${tenths % 10}"

/** Human label of a recorded/prefilled load in the set's own unit, without conversion. */
fun loadLabel(tenths: Int, unit: WeightUnit): String = when (unit) {
    WeightUnit.KG -> "${weightLabel(tenths)} kg"
    WeightUnit.LB -> "${weightLabel(tenths)} lb"
    WeightUnit.MACHINE_SETTING -> "posición ${weightLabel(tenths)}"
    WeightUnit.BODYWEIGHT -> if (tenths == 0) "peso corporal" else "PC + ${weightLabel(tenths)} kg"
}

fun rirLabel(rir: Int?): String = when (rir) { null -> "—"; 4 -> "4+"; else -> "$rir" }

fun RestDeadline.remainingMillis(now: ClockSnapshot): Long =
    ((if (bootCount == now.bootCount) elapsedMillis - now.elapsedMillis else wallMillis - now.wallMillis)).coerceAtLeast(0)
