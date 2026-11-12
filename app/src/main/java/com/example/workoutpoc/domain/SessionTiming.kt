package com.example.workoutpoc.domain

import kotlinx.serialization.Serializable

@Serializable
enum class TimedActivity { SET, CARDIO, REST, PAUSE }

@Serializable
data class TimingFocus(
    val activity: TimedActivity, val exerciseKey: String, val exercise: String,
    val setIndex: Int? = null, val setNumber: Int? = null, val stepId: String? = null
)

@Serializable
data class TimedSegment(val focus: TimingFocus, val durationMillis: Long)

@Serializable
data class OpenTimingSegment(val focus: TimingFocus, val start: PauseMark)

@Serializable
data class SessionTiming(
    val segments: List<TimedSegment> = emptyList(), val open: OpenTimingSegment? = null
)

val WorkoutState.measuredDurationMillis: Long?
    get() = timing?.takeIf { phase == Phase.Completed }?.segments?.sumOf { it.durationMillis }

internal fun WorkoutState.trackTiming(previous: WorkoutState, event: WorkoutEvent, now: ClockSnapshot): WorkoutState {
    if (source == null) return this
    val timing = previous.timing ?: if (event == WorkoutEvent.StartWorkout) SessionTiming() else return this
    val focus = timingFocus()
    val open = timing.open
    // Undo re-enters the prior set even when a one-set workout ended on that same index.
    if (open?.focus == focus && event != WorkoutEvent.UndoLastSet) return copy(timing = timing)
    val segments = if (open == null) timing.segments else
        timing.segments + TimedSegment(open.focus, open.start.elapsedSince(now))
    val next = focus?.let { OpenTimingSegment(it, PauseMark(now.elapsedMillis, now.wallMillis, now.bootCount)) }
    return copy(timing = SessionTiming(segments, next))
}

private fun WorkoutState.timingFocus(): TimingFocus? {
    val focus = when (phase) {
        Phase.ActiveSet, Phase.Resting -> plan.getOrNull(index)?.let { set ->
            TimingFocus(if (phase == Phase.Resting) TimedActivity.REST else TimedActivity.SET,
                set.exerciseId ?: set.exercise, set.exercise, index, set.number, set.prescriptionId)
        }
        Phase.ActiveCardio -> currentCardio?.let { cardio ->
            TimingFocus(TimedActivity.CARDIO, cardio.stepId, cardio.modality, stepId = cardio.stepId)
        }
        Phase.Completed, Phase.Idle -> null
    }
    return if (paused == null) focus else focus?.copy(activity = TimedActivity.PAUSE)
}
