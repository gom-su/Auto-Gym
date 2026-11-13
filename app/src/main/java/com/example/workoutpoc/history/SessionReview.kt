package com.example.workoutpoc.history

import com.example.workoutpoc.data.activePlan
import com.example.workoutpoc.domain.*

data class ReviewSet(
    val number: Int, val type: SetType, val extra: Boolean, val recordedStatus: SetStatus?,
    val targetWeight: String?, val actualWeight: String?, val targetRir: Int?, val actualRir: Int?,
    val durationMillis: Long?
)

data class ReviewExercise(val name: String, val sets: List<ReviewSet>, val time: TimeBreakdown?)

data class TimeBreakdown(val activityMillis: Long, val restMillis: Long, val pauseMillis: Long, val waitMillis: Long = 0) {
    val totalMillis get() = activityMillis + restMillis + pauseMillis + waitMillis
}

/**
 * One rest as shown in the summary (requirement 12). [objectiveSeconds] is the objective in force when it closed;
 * [originalSeconds] is the plan's objective (null for legacy snapshots). [excessMillis] is the positive difference
 * between the real rest and the objective, or null when the rest is still open or unmeasured.
 */
data class RestReview(
    val afterStepId: String, val originalSeconds: Int?, val objectiveSeconds: Int,
    val realMillis: Long?, val excessMillis: Long?, val machineWaitMillis: Long
)

data class SessionReview(
    val exercises: List<ReviewExercise>,
    val cardio: List<CardioEntry>,
    val timeline: List<TimedSegment>,
    val time: TimeBreakdown?,
    val rests: List<RestReview> = emptyList()
) {
    /** Sum of the per-rest excess (requirement 12). */
    val totalExcessMillis: Long get() = rests.sumOf { it.excessMillis ?: 0L }
}

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
                    // Warm-ups and approaches show no RIR, target or recorded (requirement 13).
                    step.rir.takeIf { step.setType == SetType.WORK && !step.extra },
                    performed?.values?.rir?.takeIf { step.setType == SetType.WORK },
                    exerciseTime.filter { it.focus.activity == TimedActivity.SET && it.focus.setIndex == index }
                        .takeIf { performed != null && it.isNotEmpty() }?.sumOf { it.durationMillis })
            }
            ReviewExercise(steps.first().value.exercise, sets, exerciseTime.takeIf { it.isNotEmpty() }?.breakdown())
        }
    val rests = restSegments.map { seg ->
        val real = seg.measuredMillis
        RestReview(seg.afterStepId, seg.originalSeconds, seg.configuredSeconds, real,
            real?.let { (it - seg.configuredSeconds * 1000L).coerceAtLeast(0) },
            machineWaits.filter { it.restSegmentId == seg.id }.sumOf { it.measuredMillis ?: 0L })
    }
    return SessionReview(exercises, cardio, segments, timing?.let { segments.breakdown() }, rests)
}

private fun List<TimedSegment>.breakdown(): TimeBreakdown {
    val byActivity = groupBy { it.focus.activity }.mapValues { (_, segments) -> segments.sumOf { it.durationMillis } }
    return TimeBreakdown(
        byActivity.getOrDefault(TimedActivity.SET, 0) + byActivity.getOrDefault(TimedActivity.CARDIO, 0),
        byActivity.getOrDefault(TimedActivity.REST, 0), byActivity.getOrDefault(TimedActivity.PAUSE, 0),
        byActivity.getOrDefault(TimedActivity.MACHINE_WAIT, 0)
    )
}
