package com.example.workoutpoc.catalog

import com.example.workoutpoc.domain.SetStatus
import com.example.workoutpoc.domain.WeightUnit
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.planning.TrainingProgram
import com.example.workoutpoc.planning.WeekAnnotations

/** Program and block filters; null means all. Blocks act as phases (program v1 has no separate phase field). */
data class PhaseFilter(val programId: String? = null, val blockId: String? = null, val includeDeloads: Boolean = true)

/**
 * One planned week's work on an exercise in one unit/basis group, over completed work sets only. Deltas compare with
 * the previous comparable week: same group, not a deload, inside the filter. A deload week is listed but never
 * compared, so a planned reduction is never reported as a regression.
 */
data class WeekRow(
    val programId: String, val programName: String, val blockId: String, val blockName: String,
    val weekId: String, val weekName: String, val firstMillis: Long, val deload: Boolean,
    val workSets: Int, val reps: Int, val topLoadTenths: Int?, val volumeTenths: Long,
    val deltaTopLoadTenths: Int?, val deltaVolumeTenths: Long?, val comparedWith: String?
)

data class PhaseGroup(val unit: WeightUnit, val basis: String?, val rows: List<WeekRow>)

fun phaseComparison(exerciseId: String, sessions: List<WorkoutState>, programs: List<TrainingProgram>,
                    annotations: WeekAnnotations, filter: PhaseFilter): List<PhaseGroup> {
    data class Key(val unit: WeightUnit, val basis: String?)
    data class WeekKey(val programId: String, val blockId: String, val weekId: String)
    class Acc(var first: Long, var sets: Int = 0, var reps: Int = 0, var top: Int? = null, var volume: Long = 0)
    val groups = linkedMapOf<Key, LinkedHashMap<WeekKey, Acc>>()
    sessions.distinctBy { it.sessionId }.forEach { s ->
        val src = s.source ?: return@forEach
        if (filter.programId != null && src.programId != filter.programId) return@forEach
        if (filter.blockId != null && src.blockId != filter.blockId) return@forEach
        val deload = annotations.isDeload(src.programId, src.weekId)
        if (deload && !filter.includeDeloads) return@forEach
        s.records.forEach { r ->
            val p = s.plan.getOrNull(r.index) ?: return@forEach
            if (p.exerciseId != exerciseId || (p.setType != com.example.workoutpoc.domain.SetType.WORK) || r.status != SetStatus.COMPLETED) return@forEach
            val acc = groups.getOrPut(Key(p.unit, p.basis)) { linkedMapOf() }
                .getOrPut(WeekKey(src.programId, src.blockId, src.weekId)) { Acc(s.startedAtMillis ?: r.completedAtMillis) }
            acc.first = minOf(acc.first, s.startedAtMillis ?: r.completedAtMillis)
            acc.sets++; acc.reps += r.values.reps; acc.volume += r.values.weightTenths.toLong() * r.values.reps
            acc.top = maxOf(acc.top ?: 0, r.values.weightTenths)
        }
    }
    return groups.map { (key, weeks) ->
        var previous: WeekRow? = null
        val rows = weeks.entries.sortedBy { it.value.first }.map { (wk, acc) ->
            val program = programs.find { it.id == wk.programId }
            val block = program?.blocks?.find { it.id == wk.blockId }
            val week = block?.weeks?.find { it.id == wk.weekId }
            val deload = annotations.isDeload(wk.programId, wk.weekId)
            val base = previous.takeIf { !deload }
            val row = WeekRow(wk.programId, program?.name ?: wk.programId, wk.blockId, block?.name ?: wk.blockId,
                wk.weekId, week?.name ?: wk.weekId, acc.first, deload, acc.sets, acc.reps, acc.top, acc.volume,
                base?.topLoadTenths?.let { b -> acc.top?.minus(b) }, base?.let { acc.volume - it.volumeTenths },
                base?.let { "${it.weekName} (${it.blockName})" })
            if (!deload) previous = row
            row
        }
        PhaseGroup(key.unit, key.basis, rows)
    }
}
