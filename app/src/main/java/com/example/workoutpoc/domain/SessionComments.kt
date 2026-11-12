package com.example.workoutpoc.domain

/**
 * Which saved comments reach the agent when a week is generated or regenerated (contract §7). Only comments that are
 * still in force (not resolved) for the same program count. An exercise comment reaches the agent only when that
 * exercise is part of the generation; general session comments always do. Comments are never removed here: replacing
 * a week and deleting its records does not touch them, because they live outside the week.
 */
object SessionComments {
    fun forGeneration(all: List<SessionComment>, programId: String, exerciseIds: Set<String>): List<SessionComment> =
        all.filter { c ->
            c.resolvedAtMillis == null && c.programId == programId &&
                (c.exerciseId == null || c.exerciseId in exerciseIds)
        }.sortedBy { it.createdAtMillis }
}
