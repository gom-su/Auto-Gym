package com.example.workoutpoc.planning

import com.example.workoutpoc.domain.Phase
import com.example.workoutpoc.domain.SetStatus
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.domain.inProgress
import com.example.workoutpoc.domain.plannedCardioComplete
import com.example.workoutpoc.domain.measuredDurationMillis
import java.util.Calendar
import java.util.TimeZone

/** The four reporting categories are never merged: "not performed" is a fact, "no record" is the absence of one. */
enum class DayStatus(val label: String) {
    COMPLETED("Completado"), PARTIAL("Parcial"), NOT_PERFORMED("No realizado"), NO_RECORD("Sin registro")
}

/**
 * Adherence of one planned day. Set counts refer to the day's prescribed sets in its most complete session:
 * completed, explicitly omitted, and never reached (no record). Extra and warm-up sets are counted apart.
 */
data class DayAdherence(
    val day: SessionPlan, val status: DayStatus, val prescribedSets: Int, val completedSets: Int, val omittedSets: Int,
    val unrecordedSets: Int, val extraSets: Int, val cardioMinutes: Int, val sessions: Int, val inProgress: Boolean
)

data class WeekAdherence(val program: TrainingProgram, val week: WeekPlan, val days: List<DayAdherence>,
                         val measuredSessions: Int = 0, val averageSessionMillis: Long? = null) {
    fun count(status: DayStatus) = days.count { it.status == status }
}

/**
 * Computes adherence for every saved week. A day is COMPLETED when a finished session completed every prescribed
 * set; PARTIAL when some prescribed set was completed (or a session is still in progress); NOT_PERFORMED when the
 * user declared it or its sessions completed nothing; NO_RECORD when nothing at all exists for it.
 */
fun adherence(programs: List<TrainingProgram>, sessions: List<WorkoutState>, annotations: WeekAnnotations): List<WeekAdherence> {
    val byDay = sessions.filter { it.source != null && it.startedAtMillis != null }.distinctBy { it.sessionId }
        .groupBy { Triple(it.source!!.programId, it.source.weekId, it.source.dayId) }
    return programs.flatMap { program -> program.blocks.flatMap { it.weeks }.map { week ->
        val durations = week.days.flatMap { day -> byDay[Triple(program.id, week.id, day.id)].orEmpty() }
            .mapNotNull { it.measuredDurationMillis }
        WeekAdherence(program, week, week.days.map { day ->
            val list = byDay[Triple(program.id, week.id, day.id)].orEmpty()
            val prescribed = day.exercises.sumOf { it.sets.size }
            fun completed(s: WorkoutState) = s.records.count { r -> s.plan[r.index].let { !it.extra } && r.status == SetStatus.COMPLETED }
            val best = list.maxByOrNull { completed(it) }
            val done = best?.let(::completed) ?: 0
            val omitted = best?.records?.count { r -> !best.plan[r.index].extra && r.status == SetStatus.SKIPPED } ?: 0
            val inProgress = list.any { it.phase.inProgress }
            val fullyDone = list.any { s -> s.phase == Phase.Completed && !s.endedEarly && completed(s) == prescribed && s.plannedCardioComplete }
            val status = when {
                fullyDone -> DayStatus.COMPLETED
                done > 0 || inProgress || list.any { s -> s.cardio.any { it.plannedCardioId != null } } -> DayStatus.PARTIAL
                list.isNotEmpty() -> DayStatus.NOT_PERFORMED
                annotations.isNotPerformed(program.id, week.id, day.id) -> DayStatus.NOT_PERFORMED
                else -> DayStatus.NO_RECORD
            }
            DayAdherence(day, status, prescribed, done, omitted, (prescribed - done - omitted).coerceAtLeast(0),
                best?.records?.count { best.plan[it.index].extra && it.status == SetStatus.COMPLETED } ?: 0,
                list.sumOf { s -> s.cardio.sumOf { it.durationSeconds ?: 0 } } / 60, list.size, inProgress)
        }, durations.size, durations.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size })
    } }
}

/** Progress toward the weekly goals in the calendar week (Monday start) containing [nowMillis]. */
data class GoalProgress(val sessions: Int, val sessionsGoal: Int?, val cardioMinutes: Int, val cardioGoal: Int?, val weekStartMillis: Long)

fun goalProgress(sessions: List<WorkoutState>, goals: Goals, nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): GoalProgress {
    val cal = Calendar.getInstance(zone).apply {
        timeInMillis = nowMillis; firstDayOfWeek = Calendar.MONDAY
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) add(Calendar.DAY_OF_MONTH, -1)
    }
    val start = cal.timeInMillis
    val end = cal.apply { add(Calendar.DAY_OF_MONTH, 7) }.timeInMillis
    val inWeek = sessions.distinctBy { it.sessionId }.filter { s -> s.startedAtMillis?.let { it in start until end } == true }
    val trained = inWeek.count { s -> s.records.any { it.status == SetStatus.COMPLETED } ||
        s.cardio.any { it.plannedCardioId != null && (it.durationSeconds ?: 0) > 0 } }
    val cardio = inWeek.sumOf { s -> s.cardio.sumOf { it.durationSeconds ?: 0 } } / 60
    return GoalProgress(trained, goals.sessionsPerWeek, cardio, goals.cardioMinutesPerWeek, start)
}
