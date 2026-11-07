package com.example.workoutpoc

import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** ENT-05: recorded execution reflects real values and deviations while the prescription stays intact. */
class DeviationTest {
    private val time = ClockSnapshot(1_000, 1_800_000_000_000, 5)
    private var ids = 0
    private val program = ProgramCodec.decode(javaClass.classLoader!!.getResourceAsStream("examples/variable-program-v1.json")!!
        .bufferedReader().use { it.readText() })

    private class MemoryStorage(var saved: WorkoutState = WorkoutState()) : WorkoutStorage {
        override suspend fun read() = saved
        override suspend fun write(state: WorkoutState) { saved = state }
    }

    private suspend fun started(week: String = "week-two", day: String = "day-two-c"): Pair<WorkoutRepository, MemoryStorage> {
        val storage = MemoryStorage()
        val repo = WorkoutRepository(storage, { time }, { "session-${++ids}" })
        repo.restore()
        val selection = program.findDay(week, day)!!
        repo.load(selection.day.toPlannedSets(), selection.source)
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        return repo to storage
    }
    private suspend fun WorkoutRepository.go(e: WorkoutEvent) = dispatch(state.value.command(e))

    private fun roundTrip(s: WorkoutState): WorkoutState {
        val out = ByteArrayOutputStream()
        kotlinx.coroutines.runBlocking { WorkoutSerializer.writeTo(s, out) }
        return kotlinx.coroutines.runBlocking { WorkoutSerializer.readFrom(ByteArrayInputStream(out.toByteArray())) }
    }

    @Test fun `skipped set is recorded as omitted and the session continues without rest`() = runTest {
        val (repo, storage) = started()
        val prescription = repo.state.value.plan
        repo.go(WorkoutEvent.SkipSet)
        val s = repo.state.value
        assertEquals(Phase.ActiveSet, s.phase); assertEquals(1, s.index)
        assertEquals(SetStatus.SKIPPED, s.records.single().status)
        assertEquals("set-two-squat-1", s.records.single().prescriptionId)
        assertEquals(prescription, s.plan)
        assertEquals(s, roundTrip(storage.saved))
    }

    @Test fun `extra set is unprescribed, prefilled from the last real values and removable before recording`() = runTest {
        val (repo, _) = started()
        repo.go(WorkoutEvent.IncrementWeight)
        repo.go(WorkoutEvent.CompleteSet) // squat set 1, then resting
        repo.go(WorkoutEvent.AddSet)
        var s = repo.state.value
        assertEquals(Phase.Resting, s.phase); assertEquals(5, s.plan.size)
        val extra = s.plan[1]
        assertTrue(extra.extra); assertNull(extra.prescriptionId); assertFalse(extra.loadPrescribed)
        assertEquals("Sentadilla ficticia", extra.exercise); assertEquals(2, extra.number); assertEquals(25, extra.weightTenths)
        repo.go(WorkoutEvent.SkipRest)
        assertEquals(SetValues(25, 5, 4), repo.state.value.values)
        repo.go(WorkoutEvent.RemoveExtraSet)
        s = repo.state.value
        assertEquals(4, s.plan.size); assertEquals(1, s.index)
        assertEquals("set-two-press-1", s.plan[s.index].prescriptionId)
        assertEquals(SetValues(30, 12, null), s.values)
    }

    @Test fun `adding after the end reopens the session and the extra is recorded without a prescription`() = runTest {
        val (repo, storage) = started("week-one", "day-one-b")
        repo.go(WorkoutEvent.CompleteSet)
        assertEquals(Phase.Completed, repo.state.value.phase)
        repo.go(WorkoutEvent.AddSet)
        assertEquals(Phase.ActiveSet, repo.state.value.phase)
        assertNull(repo.state.value.endedAtMillis)
        repo.go(WorkoutEvent.CompleteSet)
        val s = repo.state.value
        assertEquals(Phase.Completed, s.phase); assertEquals(2, s.records.size)
        assertNull(s.records[1].prescriptionId)
        assertEquals(s, roundTrip(storage.saved))
    }

    @Test fun `editing a recorded set changes only its real values and notes`() = runTest {
        val (repo, storage) = started("week-one", "day-one-a")
        repo.go(WorkoutEvent.CompleteSet)
        val before = repo.state.value
        assertTrue(repo.edit(before.sessionId, before.revision, 0, SetStatus.COMPLETED, SetValues(225, 7, 1), "  Rodilla bien  "))
        val s = repo.state.value
        val r = s.records.single()
        assertEquals(SetValues(225, 7, 1), r.values); assertEquals("Rodilla bien", r.notes); assertEquals(time.wallMillis, r.editedAtMillis)
        assertEquals(before.plan, s.plan)
        assertEquals(SetValues(200, 8, 3), s.plan[0].let { SetValues(it.weightTenths, it.reps, it.rir) })
        assertEquals(s, roundTrip(storage.saved))
        // A stale snapshot never overwrites newer data.
        assertFalse(repo.edit(before.sessionId, before.revision, 0, SetStatus.COMPLETED, SetValues(0, 1, 0), null))
        assertEquals(s, repo.state.value)
        try { repo.edit(s.sessionId, s.revision, 0, SetStatus.COMPLETED, SetValues(225, 0, 1), null); fail() }
        catch (_: WorkoutEditException) { }
        try { repo.edit(s.sessionId, s.revision, 0, SetStatus.COMPLETED, SetValues(999_999, 7, 1), null); fail() }
        catch (_: WorkoutEditException) { }
        try { repo.edit(s.sessionId, s.revision, 0, SetStatus.COMPLETED, SetValues(225, 7, 1), "x".repeat(MAX_NOTE_LENGTH + 1)); fail() }
        catch (_: WorkoutEditException) { }
        assertTrue(repo.edit(s.sessionId, s.revision, 0, SetStatus.SKIPPED, r.values, null))
        assertEquals(SetStatus.SKIPPED, repo.state.value.records.single().status)
    }

    @Test fun `every edit keeps the replaced version, oldest first, and survives storage`() = runTest {
        val (repo, storage) = started("week-one", "day-one-a")
        repo.go(WorkoutEvent.CompleteSet)
        val original = repo.state.value.records.single()
        assertTrue(original.revisions.isEmpty())
        repo.state.value.let { assertTrue(repo.edit(it.sessionId, it.revision, 0, SetStatus.COMPLETED, SetValues(225, 7, 1), "Primera")) }
        repo.state.value.let { assertTrue(repo.edit(it.sessionId, it.revision, 0, SetStatus.SKIPPED, SetValues(225, 7, 1), null)) }
        val r = repo.state.value.records.single()
        assertEquals(listOf(
            SetRevision(SetStatus.COMPLETED, original.values, null, time.wallMillis),
            SetRevision(SetStatus.COMPLETED, SetValues(225, 7, 1), "Primera", time.wallMillis)), r.revisions)
        assertEquals(SetStatus.SKIPPED, r.status)
        assertEquals(repo.state.value, roundTrip(storage.saved))
        // A no-op save adds nothing to the trail.
        repo.state.value.let { assertFalse(repo.edit(it.sessionId, it.revision, 0, SetStatus.SKIPPED, SetValues(225, 7, 1), null)) }
        assertEquals(2, repo.state.value.records.single().revisions.size)
        // The trail is bounded: once full, a further edit is refused rather than dropping old versions.
        val full = repo.state.value.let { s -> s.copy(records = listOf(s.records[0].copy(revisions = List(MAX_SET_REVISIONS) { r.revisions[0] }))) }
        try { WorkoutReducer(full.plan).edit(full, full.sessionId, full.revision, 0, SetStatus.COMPLETED, SetValues(200, 8, 3), null, time); fail() }
        catch (_: WorkoutEditException) { }
    }

    @Test fun `demo keeps its fixed plan and rejects deviations`() = runTest {
        val repo = WorkoutRepository(MemoryStorage(), { time }, { "demo-${++ids}" })
        repo.restore()
        repo.go(WorkoutEvent.StartWorkout)
        val s = repo.state.value
        repo.go(WorkoutEvent.SkipSet); repo.go(WorkoutEvent.AddSet)
        assertEquals(s, repo.state.value)
        repo.go(WorkoutEvent.CompleteSet)
        val done = repo.state.value
        assertFalse(repo.edit(done.sessionId, done.revision, 0, SetStatus.SKIPPED, done.records[0].values, "nota"))
        assertNotNull(done.toDemoV1())
    }

    @Test fun `storage rejects inconsistent deviation records`() {
        val s = WorkoutState(sessionId = "s", phase = Phase.ActiveSet, index = 1,
            plan = program.findDay("week-one", "day-one-a")!!.day.toPlannedSets(), source = program.findDay("week-one", "day-one-a")!!.source,
            records = listOf(SetRecord(0, "Sentadilla ficticia", 1, SetValues(200, 0, null), 1, "set-one-squat-1", SetStatus.SKIPPED)),
            values = SetValues(225, 6, 2))
        WorkoutValidation.validate(s)
        listOf(
            s.copy(records = listOf(s.records[0].copy(status = SetStatus.COMPLETED))),
            s.copy(records = listOf(s.records[0].copy(prescriptionId = "other"))),
            s.copy(records = listOf(s.records[0].copy(notes = " "))),
            // A revision trail needs a matching edit time, ordered times and valid values.
            s.copy(records = listOf(s.records[0].copy(revisions = listOf(SetRevision(SetStatus.SKIPPED, SetValues(200, 0, null), null, 5))))),
            s.copy(records = listOf(s.records[0].copy(editedAtMillis = 5, revisions = listOf(
                SetRevision(SetStatus.SKIPPED, SetValues(200, 0, null), null, 9), SetRevision(SetStatus.SKIPPED, SetValues(200, 0, null), null, 5))))),
            s.copy(records = listOf(s.records[0].copy(editedAtMillis = 5, revisions = listOf(SetRevision(SetStatus.COMPLETED, SetValues(200, 0, null), null, 5))))),
            s.copy(plan = s.plan.mapIndexed { i, p -> if (i == 1) p.copy(extra = true) else p })
        ).forEach { bad ->
            try { WorkoutValidation.validate(bad); fail("accepted $bad") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `deviations export as omitted and unprescribed v1 results`() = runTest {
        val (repo, _) = started("week-one", "day-one-a")
        repo.go(WorkoutEvent.SkipSet)
        repo.go(WorkoutEvent.CompleteSet)
        repo.go(WorkoutEvent.AddSet)
        repo.go(WorkoutEvent.IncrementReps)
        repo.go(WorkoutEvent.CompleteSet)
        val s = repo.state.value
        repo.edit(s.sessionId, s.revision, 1, SetStatus.COMPLETED, s.records[1].values, "Buena técnica")
        val result = repo.state.value.toSessionResult()!!
        assertEquals(PerformanceStatus.PARTIAL, result.status)
        assertEquals(listOf(PerformanceStatus.OMITTED, PerformanceStatus.COMPLETED, PerformanceStatus.COMPLETED), result.sets.map { it.status })
        assertNull(result.sets[0].reps); assertNull(result.sets[0].load); assertNull(result.sets[0].completedAt)
        assertEquals("set-one-squat-2", result.sets[1].prescriptionId)
        assertTrue(result.sets[1].notes!!.contains("Buena técnica")); assertTrue(result.sets[1].notes!!.contains("Editada"))
        assertNull(result.sets[2].prescriptionId); assertTrue(result.sets[2].notes!!.contains("sin prescripción"))
        assertEquals(7, result.sets[2].reps)
        val pkg = ExchangePackage(1, ExchangeType.TrainingResults, "results", "2026-11-06T12:00:00Z", results = TrainingResults(listOf(result)))
        assertEquals(pkg, ExchangeCodec.decode(ExchangeCodec.encode(pkg)))
    }
}
