package com.example.workoutpoc.home

import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.planning.*

/** The saved week Hoy is about: its place in the program and its days, in order, with their status. */
data class CurrentWeek(
    val program: TrainingProgram, val block: TrainingBlock, val week: WeekPlan,
    /** 1-based position of the week in the program (blocks in order, weeks in order), like plan week numbers. */
    val number: Int,
    /** Weeks in the active plan, when the program has one. */
    val planWeeks: Int?,
    val days: List<DayAdherence>,
    /** The first day of the week, in order, with nothing recorded; null when every day has something. */
    val next: SessionPlan?,
    val deload: Boolean
)

/** What Hoy shows besides the session card. Every field is derived; nothing here is stored. */
data class HomeSummary(
    val program: TrainingProgram?,
    val week: CurrentWeek?,
    /** Every saved week has records: the next week has to come from the agent (plan week number when known). */
    val weekToPlan: Int?,
    val outline: PlanOutline?
)

object HomePlanner {
    /**
     * The program Hoy is about: the one with the active plan; without a plan, the program of the most recent planned
     * session; otherwise the most recently saved program.
     */
    fun currentProgram(programs: List<TrainingProgram>, active: List<PlanOutline>, sessions: List<WorkoutState>): TrainingProgram? {
        active.firstNotNullOfOrNull { o -> programs.find { it.id == o.programId } }?.let { return it }
        val recent = sessions.filter { it.source != null }.maxByOrNull { it.startedAtMillis ?: 0L }?.source?.programId
        return programs.find { it.id == recent } ?: programs.lastOrNull()
    }

    /**
     * Days are trained in order. The current week is the last week with any record if it still has an unrecorded day,
     * otherwise the week after it; with nothing recorded yet, the first week. A day counts as recorded with any status
     * other than "sin registro" (a declared "no realizado" moves on too).
     */
    fun summary(programs: List<TrainingProgram>, active: List<PlanOutline>, sessions: List<WorkoutState>,
                annotations: WeekAnnotations): HomeSummary {
        val program = currentProgram(programs, active, sessions)
        val outline = active.find { it.programId == (program?.id ?: it.programId) }
        if (program == null) return HomeSummary(null, null, if (outline != null) 1 else null, outline)
        val weeks = program.blocks.flatMap { b -> b.weeks.map { b to it } }
        if (weeks.isEmpty()) return HomeSummary(program, null, 1, outline)
        val byWeek = adherence(listOf(program), sessions, annotations).associateBy { it.week.id }
        fun recorded(d: DayAdherence) = d.status != DayStatus.NO_RECORD
        val last = weeks.indexOfLast { (_, w) -> byWeek[w.id]?.days?.any(::recorded) == true }
        val index = when {
            last < 0 -> 0
            byWeek[weeks[last].second.id]?.days?.all(::recorded) == true -> last + 1
            else -> last
        }
        if (index >= weeks.size) return HomeSummary(program, null, weeks.size + 1, outline)
        val (block, week) = weeks[index]
        val days = byWeek[week.id]?.days.orEmpty()
        val planDeload = outline?.weeks?.find { it.number == index + 1 }?.deload == true
        return HomeSummary(program, CurrentWeek(program, block, week, index + 1, outline?.weeks?.size, days,
            days.firstOrNull { !recorded(it) }?.day, planDeload || annotations.isDeload(program.id, week.id)), null, outline)
    }
}
