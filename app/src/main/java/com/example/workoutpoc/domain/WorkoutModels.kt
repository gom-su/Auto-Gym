package com.example.workoutpoc.domain

import kotlinx.serialization.Serializable

@Serializable
enum class Phase { Idle, ActiveSet, Resting, Completed }

@Serializable
data class PlannedSet(val exercise: String, val number: Int, val total: Int,
                      val weightTenths: Int, val reps: Int, val rir: Int)

/** Integer tenths of kg avoid floating point drift; rir=4 means 4+. */
@Serializable
data class SetValues(val weightTenths: Int = 300, val reps: Int = 8, val rir: Int = 2)

@Serializable
data class SetRecord(val index: Int, val exercise: String, val number: Int,
                     val values: SetValues, val completedAtMillis: Long)

/** Durable monotonic deadline, with wall time fallback only after a reboot. */
@Serializable
data class RestDeadline(val elapsedMillis: Long, val wallMillis: Long, val bootCount: Int)

@Serializable
data class WorkoutState(
    val sessionId: String = "",
    val phase: Phase = Phase.Idle,
    val index: Int = 0,
    val revision: Long = 0,
    val values: SetValues = SetValues(),
    val records: List<SetRecord> = emptyList(),
    val rest: RestDeadline? = null,
    val endedEarly: Boolean = false
)

enum class WorkoutEvent {
    StartWorkout, IncrementWeight, DecrementWeight, IncrementReps, DecrementReps,
    SelectRir, CompleteSet, AddRestTime, SkipRest, RestFinished, UndoLastSet, ResetDemo,
    FinishWorkout
}

/** Every command targets an exact snapshot. Old notification/overlay taps become no-ops. */
data class WorkoutCommand(val event: WorkoutEvent, val sessionId: String,
                          val index: Int, val revision: Long, val rir: Int? = null)

data class ClockSnapshot(val elapsedMillis: Long, val wallMillis: Long, val bootCount: Int)

fun WorkoutState.command(event: WorkoutEvent, rir: Int? = null): WorkoutCommand =
    WorkoutCommand(event, sessionId, index, revision, rir)

fun weightLabel(tenths: Int): String = if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10},${tenths % 10}"

fun RestDeadline.remainingMillis(now: ClockSnapshot): Long =
    ((if (bootCount == now.bootCount) elapsedMillis - now.elapsedMillis else wallMillis - now.wallMillis)).coerceAtLeast(0)
