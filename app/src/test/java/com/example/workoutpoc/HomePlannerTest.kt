package com.example.workoutpoc

import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.home.HomePlanner
import com.example.workoutpoc.planning.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Hoy suggests the next day in order (A→B→C→D) from what is recorded, never from what is assumed. */
class HomePlannerTest {
    private var time = ClockSnapshot(1_000, 1_799_020_800_000L, 5)
    private var ids = 0
    private val program = ProgramCodec.decode(javaClass.classLoader!!.getResourceAsStream("examples/variable-program-v1.json")!!
        .bufferedReader().use { it.readText() })

    private class MemoryStorage(var saved: WorkoutState = WorkoutState()) : WorkoutStorage {
        override suspend fun read() = saved
        override suspend fun write(state: WorkoutState) { saved = state }
    }
    private suspend fun WorkoutRepository.go(e: WorkoutEvent) = dispatch(state.value.command(e))
    private suspend fun WorkoutRepository.train(week: String, day: String) {
        val d = program.findDay(week, day)!!
        time = time.copy(elapsedMillis = time.elapsedMillis + 60_000, wallMillis = time.wallMillis + 60_000)
        load(d.day.toPlannedSets(), d.source); go(WorkoutEvent.StartWorkout); completeSet(); go(WorkoutEvent.FinishWorkout)
    }
    private suspend fun repo() = WorkoutRepository(MemoryStorage(), { time }, { "s-${++ids}" }).apply { restore() }
    private fun WorkoutRepository.sessions() = archived.value.sessions + state.value

    @Test fun `nothing recorded suggests the first day of the first week`() = runTest {
        val s = HomePlanner.summary(listOf(program), emptyList(), repo().sessions(), WeekAnnotations())
        assertEquals("week-one", s.week!!.week.id)
        assertEquals(1, s.week!!.number)
        assertEquals("day-one-a", s.week!!.next!!.id)
        assertNull(s.weekToPlan)
    }

    @Test fun `days go in order and a finished week moves to the next one`() = runTest {
        val r = repo()
        r.train("week-one", "day-one-a")
        var s = HomePlanner.summary(listOf(program), emptyList(), r.sessions(), WeekAnnotations())
        assertEquals("day-one-b", s.week!!.next!!.id)
        r.train("week-one", "day-one-b")
        s = HomePlanner.summary(listOf(program), emptyList(), r.sessions(), WeekAnnotations())
        assertEquals("week-two", s.week!!.week.id)
        assertEquals(2, s.week!!.number)
        assertEquals("day-two-c", s.week!!.next!!.id)
    }

    @Test fun `a declared not-performed day is passed over without inventing a session`() = runTest {
        val marks = WeekAnnotations(notPerformed = listOf(DayRef(program.id, "week-one", "day-one-a")))
        val s = HomePlanner.summary(listOf(program), emptyList(), repo().sessions(), marks)
        assertEquals("day-one-b", s.week!!.next!!.id)
    }

    @Test fun `every saved week recorded asks for the next week`() = runTest {
        val r = repo()
        r.train("week-one", "day-one-a"); r.train("week-one", "day-one-b"); r.train("week-two", "day-two-c"); r.train("week-three", "day-three-a")
        val s = HomePlanner.summary(listOf(program), emptyList(), r.sessions(), WeekAnnotations())
        assertNull(s.week)
        assertEquals(4, s.weekToPlan)
    }

    @Test fun `the program of the latest session is current when there is no plan`() = runTest {
        val other = program.copy(id = "other-program", name = "Otro")
        val r = repo()
        r.train("week-one", "day-one-a")
        assertEquals(program.id, HomePlanner.currentProgram(listOf(program, other), emptyList(), r.sessions())!!.id)
        assertEquals(other.id, HomePlanner.currentProgram(listOf(program, other), emptyList(), emptyList())!!.id)
    }

    private fun outline() = com.example.workoutpoc.exchange.ExchangeCodec.decode(
        java.io.File("src/main/assets/examples/plan-outline-v1.json").readText()).outline!!.let { o ->
        o.copy(programId = program.id, weeks = o.weeks.take(3), blocks = o.blocks.take(1))
    }

    @Test fun `last week warns before completion and a finished cycle never asks for week thirteen`() = runTest {
        val r = repo()
        r.train("week-one", "day-one-a"); r.train("week-one", "day-one-b"); r.train("week-two", "day-two-c")
        val o = outline()
        val last = HomePlanner.summary(listOf(program), listOf(o), r.sessions(), WeekAnnotations())
        assertTrue(last.lastWeek)
        assertTrue(last.cycleNotice!!.contains("última semana"))
        assertFalse(last.cycleComplete)
        r.train("week-three", "day-three-a")
        val done = HomePlanner.summary(listOf(program), listOf(o), r.sessions(), WeekAnnotations())
        assertTrue(done.cycleComplete)
        assertNull(done.weekToPlan)
        assertTrue(done.cycleNotice!!.contains("nuevo plan de 12 semanas"))
    }

    @Test fun `a running final session does not close the cycle`() = runTest {
        val r = repo()
        r.train("week-one", "day-one-a"); r.train("week-one", "day-one-b"); r.train("week-two", "day-two-c")
        val d = program.findDay("week-three", "day-three-a")!!
        r.load(d.day.toPlannedSets(), d.source); r.go(WorkoutEvent.StartWorkout); r.completeSet()
        val s = HomePlanner.summary(listOf(program), listOf(outline()), r.sessions(), WeekAnnotations())
        assertFalse(s.cycleComplete)
        assertTrue(s.lastWeek)
    }

    @Test fun `activating a new cycle does not send Hoy back to the old program`() = runTest {
        val o = outline().copy(id = "next-outline", programId = "next-program")
        val r = repo()
        r.train("week-one", "day-one-a")
        val s = HomePlanner.summary(listOf(program), listOf(o), r.sessions(), WeekAnnotations())
        assertNull(s.program)
        assertNull(s.week)
        assertFalse(s.cycleComplete)
        assertEquals(1, s.weekToPlan)
        assertEquals(o, s.outline)
    }
}
