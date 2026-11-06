package com.example.workoutpoc.history

import com.example.workoutpoc.data.activePlan
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.planning.DayStatus

/** One recorded set as shown in history, with its prescription context. */
data class HistorySet(val exercise: String, val number: Int, val unit: WeightUnit, val values: SetValues, val status: SetStatus,
                      val warmup: Boolean, val extra: Boolean, val notes: String?, val edited: Boolean)

/**
 * Summary of one recorded session. Status uses the adherence categories: completed only when every prescribed set was
 * completed in a finished session; partial when some was or it is still running; not performed when nothing was.
 */
data class SessionSummary(
    val sessionId: String, val title: String, val startedAtMillis: Long?, val endedAtMillis: Long?, val activeMillis: Long?,
    val status: DayStatus, val inProgress: Boolean, val endedEarly: Boolean,
    val completedSets: Int, val omittedSets: Int, val unreachedSets: Int, val extraSets: Int, val warmupSets: Int,
    val cardio: List<CardioEntry>, val sets: List<HistorySet>, val demo: Boolean
)

fun summarize(s: WorkoutState, nowWallMillis: Long, nowElapsed: Long = 0, boot: Int = 0): SessionSummary {
    val plan = s.activePlan()
    val sets = s.records.map { r ->
        val p = plan[r.index]
        HistorySet(p.exercise, p.number, p.unit, r.values, r.status, p.warmup, p.extra, r.notes, r.editedAtMillis != null)
    }
    val prescribed = plan.count { !it.extra }
    val completed = s.records.count { !plan[it.index].extra && it.status == SetStatus.COMPLETED }
    val omitted = s.records.count { !plan[it.index].extra && it.status == SetStatus.SKIPPED }
    val inProgress = s.phase == Phase.ActiveSet || s.phase == Phase.Resting
    val status = when {
        s.phase == Phase.Completed && !s.endedEarly && completed == prescribed -> DayStatus.COMPLETED
        completed > 0 || inProgress -> DayStatus.PARTIAL
        else -> DayStatus.NOT_PERFORMED
    }
    val title = s.source?.let { "${it.programName} · ${it.weekName} · ${it.dayName}" } ?: "Demo"
    return SessionSummary(s.sessionId, title, s.startedAtMillis, s.endedAtMillis,
        s.activeMillis(ClockSnapshot(nowElapsed, nowWallMillis, boot)), status, inProgress, s.endedEarly,
        completed, omitted, (prescribed - completed - omitted).coerceAtLeast(0),
        s.records.count { plan[it.index].extra && !plan[it.index].warmup && it.status == SetStatus.COMPLETED },
        s.records.count { plan[it.index].warmup && it.status == SetStatus.COMPLETED },
        s.cardio, sets, s.source == null)
}

/** All sessions with at least one record or cardio entry, newest first; the current one is included if started. */
fun history(archived: List<WorkoutState>, current: WorkoutState, nowWallMillis: Long): List<SessionSummary> =
    (archived + current).distinctBy { it.sessionId }
        .filter { it.records.isNotEmpty() || it.cardio.isNotEmpty() || (it.phase != Phase.Idle && it.startedAtMillis != null) }
        .sortedByDescending { s -> s.startedAtMillis ?: s.records.minOfOrNull { it.completedAtMillis } ?: 0L }
        .map { summarize(it, nowWallMillis) }
