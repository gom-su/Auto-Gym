package com.example.workoutpoc.catalog

import com.example.workoutpoc.domain.*
import com.example.workoutpoc.planning.TrainingProgram

/** One recorded set of an exercise, as recorded (name at the time, unit as recorded). */
data class ExerciseHistoryEntry(
    val sessionId: String, val dayName: String?, val completedAtMillis: Long, val recordedName: String,
    val warmup: Boolean, val extra: Boolean, val status: SetStatus, val values: SetValues, val unit: WeightUnit
)

/**
 * All recorded sets of [exerciseId] across sessions, newest first. Matching is by ID only, so renaming the exercise
 * (or recording it under different names in different programs) never splits or loses its history. The demo has no
 * exercise IDs and is therefore never attributed to a catalogued exercise.
 */
fun exerciseHistory(exerciseId: String, sessions: List<WorkoutState>): List<ExerciseHistoryEntry> =
    sessions.distinctBy { it.sessionId }.flatMap { s ->
        s.records.mapNotNull { r ->
            val p = s.plan.getOrNull(r.index) ?: return@mapNotNull null
            if (p.exerciseId != exerciseId) null
            else ExerciseHistoryEntry(s.sessionId, s.source?.dayName, r.completedAtMillis, p.exercise, (p.setType != com.example.workoutpoc.domain.SetType.WORK), p.extra, r.status, r.values, p.unit)
        }
    }.sortedByDescending { it.completedAtMillis }

/** Every exercise ID referenced by saved programs and recorded sessions, with the name used there. */
fun exerciseReferences(programs: List<TrainingProgram>, sessions: List<WorkoutState>): List<ExerciseReference> =
    programs.flatMap { p -> p.blocks.flatMap { b -> b.weeks.flatMap { w -> w.days.flatMap { d -> d.exercises.map { ExerciseReference(it.exerciseId, it.name) } } } } } +
        sessions.flatMap { s -> s.plan.mapNotNull { p -> p.exerciseId?.let { ExerciseReference(it, p.exercise) } } }
