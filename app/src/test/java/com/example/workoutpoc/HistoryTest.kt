package com.example.workoutpoc

import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.history.history
import com.example.workoutpoc.planning.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Session history lists every recorded session newest first, with honest status and set breakdowns. */
class HistoryTest {
    private var time = ClockSnapshot(1_000, 1_799_020_800_000L, 5)
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
        time = time.copy(elapsedMillis = time.elapsedMillis + 3_600_000, wallMillis = time.wallMillis + 3_600_000)
        load(d.day.toPlannedSets(), d.source); go(WorkoutEvent.StartWorkout); steps()
        if (finish && state.value.phase != Phase.Completed) go(WorkoutEvent.FinishWorkout)
    }

    @Test fun `sessions are newest first with status, counts and set details`() = runTest {
        val repo = WorkoutRepository(MemoryStorage(), { time }, { "s-${++ids}" })
        repo.restore()
        repo.train("week-one", "day-one-a") { go(WorkoutEvent.CompleteSet); go(WorkoutEvent.SkipRest); go(WorkoutEvent.CompleteSet) }
        repo.go(WorkoutEvent.ResetDemo)
        repo.train("week-one", "day-one-b") { go(WorkoutEvent.SkipSet) }
        repo.go(WorkoutEvent.ResetDemo)
        repo.train("week-two", "day-two-c", finish = false) { go(WorkoutEvent.CompleteSet) }

        val list = history(repo.archived.value.sessions, repo.state.value, time.wallMillis)
        assertEquals(listOf("Día C", "Día B", "Día A"), list.map { it.title.substringAfterLast(" · ") })
        assertTrue(list.zipWithNext().all { (a, b) -> a.startedAtMillis!! >= b.startedAtMillis!! })

        val running = list[0]
        assertTrue(running.inProgress); assertEquals(DayStatus.PARTIAL, running.status)
        assertEquals(listOf(1, 0, 3), listOf(running.completedSets, running.omittedSets, running.unreachedSets))
        val skipped = list[1]
        assertEquals(DayStatus.NOT_PERFORMED, skipped.status); assertEquals(1, skipped.omittedSets)
        assertEquals(SetStatus.SKIPPED, skipped.sets.single().status)
        val done = list[2]
        assertEquals(DayStatus.COMPLETED, done.status)
        assertEquals("Programa ficticio variable · Semana 1 · Día A", done.title)
        assertEquals(listOf("Sentadilla ficticia" to 1, "Sentadilla ficticia" to 2), done.sets.map { it.exercise to it.number })
        assertTrue(done.sets.all { it.status == SetStatus.COMPLETED && !it.edited && !it.extra })
        assertNotNull(done.activeMillis); assertNotNull(done.endedAtMillis)
    }

    @Test fun `untouched current state is not listed and duplicates are collapsed`() = runTest {
        val repo = WorkoutRepository(MemoryStorage(), { time }, { "s-${++ids}" })
        repo.restore()
        assertTrue(history(repo.archived.value.sessions, repo.state.value, time.wallMillis).isEmpty())
        repo.train("week-one", "day-one-a") { go(WorkoutEvent.CompleteSet); go(WorkoutEvent.SkipRest); go(WorkoutEvent.CompleteSet); go(WorkoutEvent.AddSet); go(WorkoutEvent.CompleteSet) }
        val finished = repo.state.value
        // The finished session may be both current and archived; it is shown once.
        val list = history(listOf(finished), finished, time.wallMillis)
        assertEquals(1, list.size)
        assertEquals(1, list.single().extraSets)
        assertEquals(DayStatus.COMPLETED, list.single().status)
    }
}
