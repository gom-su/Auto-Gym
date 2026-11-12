package com.example.workoutpoc.history

import com.example.workoutpoc.data.activePlan
import com.example.workoutpoc.domain.*

data class ReviewSet(
    val number: Int, val type: SetType, val extra: Boolean, val recordedStatus: SetStatus?,
    val targetWeight: String?, val actualWeight: String?, val targetRir: Int?, val actualRir: Int?,
    val durationMillis: Long?
)

data class ReviewExercise(val name: String, val sets: List<ReviewSet>, val time: TimeBreakdown?)

data class TimeBreakdown(val activityMillis: Long, val restMillis: Long, val pauseMillis: Long) {
    val totalMillis get() = activityMillis + restMillis + pauseMillis
}

data class SessionReview(
    val exercises: List<ReviewExercise>,
    val cardio: List<CardioEntry>,
    val timeline: List<TimedSegment>,
    val time: TimeBreakdown?
)

fun WorkoutState.review(): SessionReview {
    val segments = timing?.segments.orEmpty()
    val recordsByIndex = records.associateBy { it.index }
    val exercises = activePlan().withIndex().groupBy { it.value.exerciseId ?: it.value.exercise }
        .map { (key, steps) ->
            val exerciseTime = segments.filter { it.focus.exerciseKey == key && it.focus.setIndex != null }
            val sets = steps.map { (index, step) ->
                val record = recordsByIndex[index]
                val performed = record?.takeIf { it.status == SetStatus.COMPLETED }
                ReviewSet(step.number, step.setType, step.extra, record?.status,
                    loadLabel(step.weightTenths, step.unit).takeIf { step.loadPrescribed && !step.extra },
                    performed?.let { loadLabel(it.values.weightTenths, it.recordedUnit ?: step.unit) },
                    step.rir.takeUnless { step.extra }, performed?.values?.rir,
                    exerciseTime.filter { it.focus.activity == TimedActivity.SET && it.focus.setIndex == index }
                        .takeIf { performed != null && it.isNotEmpty() }?.sumOf { it.durationMillis })
            }
            ReviewExercise(steps.first().value.exercise, sets, exerciseTime.takeIf { it.isNotEmpty() }?.breakdown())
        }
    return SessionReview(exercises, cardio, segments, timing?.let { segments.breakdown() })
}

private fun List<TimedSegment>.breakdown(): TimeBreakdown {
    val byActivity = groupBy { it.focus.activity }.mapValues { (_, segments) -> segments.sumOf { it.durationMillis } }
    return TimeBreakdown(
        byActivity.getOrDefault(TimedActivity.SET, 0) + byActivity.getOrDefault(TimedActivity.CARDIO, 0),
        byActivity.getOrDefault(TimedActivity.REST, 0), byActivity.getOrDefault(TimedActivity.PAUSE, 0)
    )
}
