package com.example.workoutpoc

import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.history.HistoryCsv
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

    @Test fun `csv has one row per set and cardio entry with only recorded values`() = runTest {
        val repo = WorkoutRepository(MemoryStorage(), { time }, { "s-${++ids}" })
        repo.restore()
        assertEquals(HistoryCsv.header.joinToString(",") + "\r\n", HistoryCsv.encode(emptyList(), repo.state.value, time.wallMillis))
        repo.train("week-two", "day-two-c") {
            go(WorkoutEvent.SkipSet)
            go(WorkoutEvent.CompleteSet)
            state.value.let { edit(it.sessionId, it.revision, 1, SetStatus.COMPLETED, it.records[1].values, "Nota, con \"comillas\"") }
            state.value.let { addCardio(it.sessionId, it.revision, CardioEntry("c-1", "Bici", 600, null, null, "=1+1", time.wallMillis)) }
        }
        val rows = HistoryCsv.encode(repo.archived.value.sessions, repo.state.value, time.wallMillis).split("\r\n").filter { it.isNotEmpty() }
        assertEquals(1 + 2 + 1, rows.size)
        val skipped = rows[1].split(",")
        assertEquals("SKIPPED", skipped[HistoryCsv.header.indexOf("set_status")])
        assertEquals("", skipped[HistoryCsv.header.indexOf("load")]); assertEquals("", skipped[HistoryCsv.header.indexOf("reps")])
        assertEquals("PARTIAL (ENDED_EARLY)", skipped[HistoryCsv.header.indexOf("session_status")])
        assertEquals("1970-01-01T00:00:00Z".length, skipped[HistoryCsv.header.indexOf("started_at")].length)
        assertTrue(rows[2].endsWith(",1,\"Nota, con \"\"comillas\"\"\",,,,"))
        assertTrue(rows[3].contains(",cardio,"))
        assertTrue(rows[3].endsWith(",'=1+1,Bici,600,,"))
    }

    @Test fun `programs are listed once and totals count only the listed sessions`() = runTest {
        val repo = WorkoutRepository(MemoryStorage(), { time }, { "s-${++ids}" })
        repo.restore()
        repo.go(WorkoutEvent.StartWorkout); repo.go(WorkoutEvent.CompleteSet); repo.go(WorkoutEvent.FinishWorkout)   // demo
        repo.go(WorkoutEvent.ResetDemo)
        repo.train("week-one", "day-one-a") { go(WorkoutEvent.CompleteSet); go(WorkoutEvent.SkipRest); go(WorkoutEvent.SkipSet) }
        repo.go(WorkoutEvent.ResetDemo)
        repo.train("week-one", "day-one-b") { go(WorkoutEvent.CompleteSet) }
        repo.go(WorkoutEvent.ResetDemo)
        val list = history(repo.archived.value.sessions, repo.state.value, time.wallMillis)
        assertEquals(listOf<Pair<String?, String>>("fictional-program" to "Programa ficticio variable", null to "Demo"),
            com.example.workoutpoc.history.programsIn(list))
        val t = com.example.workoutpoc.history.totals(list.filter { it.programId == "fictional-program" })
        assertEquals(2, t.sessions); assertEquals(2, t.completedSets); assertEquals(1, t.omittedSets); assertEquals(0, t.cardio)
    }

    @Test fun `csv fields are quoted and formula-like text is neutralised`() {
        assertEquals("plain", HistoryCsv.field("plain"))
        assertEquals("\"a,b\"", HistoryCsv.field("a,b"))
        assertEquals("\"line\nbreak\"", HistoryCsv.field("line\nbreak"))
        assertEquals("-2.5", HistoryCsv.field("-2.5"))
        assertEquals("'@cmd", HistoryCsv.field("@cmd"))
    }
}
