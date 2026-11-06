package com.example.workoutpoc

import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.planning.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** ANA-04: indicators keep completed, partial, not performed and no record apart; goals are explicit. */
class AdherenceTest {
    private val utc = TimeZone.getTimeZone("UTC")
    // 2027-01-04 is a Monday (UTC).
    private val monday = 1_799_020_800_000L
    private var time = ClockSnapshot(1_000, monday + 3_600_000, 5)
    private var ids = 0
    private val program = ProgramCodec.decode(javaClass.classLoader!!.getResourceAsStream("examples/variable-program-v1.json")!!
        .bufferedReader().use { it.readText() })

    private class MemoryStorage(var saved: WorkoutState = WorkoutState()) : WorkoutStorage {
        override suspend fun read() = saved
        override suspend fun write(state: WorkoutState) { saved = state }
    }
    private suspend fun WorkoutRepository.go(e: WorkoutEvent) = dispatch(state.value.command(e))
    private suspend fun WorkoutRepository.train(week: String, day: String, finish: Boolean = true, steps: suspend WorkoutRepository.() -> Unit) {
        val d = program.findDay(week, day)!!
        load(d.day.toPlannedSets(), d.source); go(WorkoutEvent.StartWorkout); steps()
        if (finish && state.value.phase != Phase.Completed) go(WorkoutEvent.FinishWorkout)
    }

    @Test fun `four categories are computed separately with set breakdowns`() = runTest {
        val repo = WorkoutRepository(MemoryStorage(), { time }, { "s-${++ids}" })
        repo.restore()
        repo.train("week-one", "day-one-a") { go(WorkoutEvent.CompleteSet); go(WorkoutEvent.SkipRest); go(WorkoutEvent.CompleteSet) }     // all 2 sets
        repo.train("week-two", "day-two-c") { go(WorkoutEvent.CompleteSet); go(WorkoutEvent.SkipRest); go(WorkoutEvent.SkipSet) }        // 1 done, 1 omitted, 2 unreached
        repo.train("week-one", "day-one-b") { go(WorkoutEvent.SkipSet) }                                                                // nothing completed
        repo.go(WorkoutEvent.ResetDemo)
        val sessions = repo.archived.value.sessions
        val weeks = adherence(listOf(program), sessions, WeekAnnotations())
        fun day(w: String, d: String) = weeks.single { it.week.id == w }.days.single { it.day.id == d }

        val a = day("week-one", "day-one-a")
        assertEquals(DayStatus.COMPLETED, a.status); assertEquals(2, a.completedSets); assertEquals(2, a.prescribedSets)
        val c = day("week-two", "day-two-c")
        assertEquals(DayStatus.PARTIAL, c.status)
        assertEquals(listOf(1, 1, 2), listOf(c.completedSets, c.omittedSets, c.unrecordedSets))
        assertEquals(DayStatus.NOT_PERFORMED, day("week-one", "day-one-b").status)
        val three = day("week-three", "day-three-a")
        assertEquals(DayStatus.NO_RECORD, three.status); assertEquals(three.prescribedSets, three.unrecordedSets)

        // An explicit mark turns "no record" into "not performed", but never overrides recorded work.
        val marks = WeekAnnotations(notPerformed = listOf(DayRef(program.id, "week-three", "day-three-a"), DayRef(program.id, "week-one", "day-one-a")))
        val marked = adherence(listOf(program), sessions, marks)
        assertEquals(DayStatus.NOT_PERFORMED, marked.single { it.week.id == "week-three" }.days.single().status)
        assertEquals(DayStatus.COMPLETED, marked.single { it.week.id == "week-one" }.days.single { it.day.id == "day-one-a" }.status)
    }

    @Test fun `ended early, in progress and extra sets are reported honestly`() = runTest {
        val repo = WorkoutRepository(MemoryStorage(), { time }, { "s-${++ids}" })
        repo.restore()
        // Both prescribed sets plus an extra, then finished normally: completed, with the extra counted apart.
        repo.train("week-one", "day-one-a") {
            go(WorkoutEvent.CompleteSet); go(WorkoutEvent.SkipRest); go(WorkoutEvent.CompleteSet); go(WorkoutEvent.AddSet); go(WorkoutEvent.CompleteSet)
        }
        repo.go(WorkoutEvent.ResetDemo)
        repo.train("week-two", "day-two-c", finish = false) { go(WorkoutEvent.CompleteSet) }   // still resting: in progress
        val sessions = repo.archived.value.sessions + repo.state.value
        val weeks = adherence(listOf(program), sessions, WeekAnnotations())
        val a = weeks.single { it.week.id == "week-one" }.days.single { it.day.id == "day-one-a" }
        assertEquals(DayStatus.COMPLETED, a.status); assertEquals(1, a.extraSets)
        val c = weeks.single { it.week.id == "week-two" }.days.single()
        assertEquals(DayStatus.PARTIAL, c.status); assertTrue(c.inProgress)
    }

    @Test fun `goal progress counts trained sessions and cardio in the current calendar week only`() = runTest {
        val repo = WorkoutRepository(MemoryStorage(), { time }, { "s-${++ids}" })
        repo.restore()
        repo.train("week-one", "day-one-a") {
            go(WorkoutEvent.CompleteSet)
            val s = state.value
            addCardio(s.sessionId, s.revision, CardioEntry("c1", "Bici", 25 * 60, null, null, null, time.wallMillis))
        }
        repo.go(WorkoutEvent.ResetDemo)
        repo.train("week-one", "day-one-b") { go(WorkoutEvent.SkipSet) }                    // no completed set: does not count
        repo.go(WorkoutEvent.ResetDemo)
        time = time.copy(wallMillis = monday - 3_600_000)                                  // previous Sunday
        repo.train("week-two", "day-two-c") { go(WorkoutEvent.CompleteSet) }
        repo.go(WorkoutEvent.ResetDemo)
        val sessions = repo.archived.value.sessions
        val p = goalProgress(sessions, Goals(3, 90), monday + 2 * 86_400_000, utc)
        assertEquals(monday, p.weekStartMillis)
        assertEquals(1, p.sessions); assertEquals(25, p.cardioMinutes)
        assertEquals(3, p.sessionsGoal); assertEquals(90, p.cardioGoal)
        assertNull(goalProgress(sessions, Goals(), monday, utc).sessionsGoal)
        try { Goals(sessionsPerWeek = 0).validate(); fail() } catch (_: IllegalArgumentException) { }
        try { Goals(cardioMinutesPerWeek = 5000).validate(); fail() } catch (_: IllegalArgumentException) { }
    }
}
