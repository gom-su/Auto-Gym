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

/** Postponing an exercise on its first set (machine busy): its unrecorded sets move after the next exercise. */
class PostponeTest {
    private val time = ClockSnapshot(1_000, 1_800_000_000_000, 5)
    private var ids = 0
    private val program = ProgramCodec.decode(javaClass.classLoader!!.getResourceAsStream("examples/variable-program-v1.json")!!
        .bufferedReader().use { it.readText() })

    private class MemoryStorage(var saved: WorkoutState = WorkoutState()) : WorkoutStorage {
        override suspend fun read() = saved
        override suspend fun write(state: WorkoutState) { saved = state }
    }

    /** Squat ×2, press ×3, row ×1: three distinct exercises taken from the fixture days. */
    private val day = program.findDay("week-one", "day-one-a")!!
    private val plan: List<PlannedSet> = day.day.toPlannedSets() +
        program.findDay("week-two", "day-two-c")!!.day.toPlannedSets().filter { it.exerciseId == "machine-press" } +
        program.findDay("week-one", "day-one-b")!!.day.toPlannedSets()

    private suspend fun started(): Pair<WorkoutRepository, MemoryStorage> {
        val storage = MemoryStorage()
        val repo = WorkoutRepository(storage, { time }, { "session-${++ids}" })
        repo.restore()
        repo.load(plan, day.source)
        repo.go(WorkoutEvent.StartWorkout)
        return repo to storage
    }
    private suspend fun WorkoutRepository.go(e: WorkoutEvent) = dispatch(state.value.command(e))
    /** Records the current set (adding a repetition when none was prescribed) and skips any rest. */
    private suspend fun WorkoutRepository.done() {
        if (state.value.values.reps == 0) go(WorkoutEvent.IncrementReps)
        completeSet()
        if (state.value.phase == Phase.Resting) go(WorkoutEvent.SkipRest)
    }
    private val WorkoutState.ids get() = plan.map { it.prescriptionId }
    private val WorkoutState.target get() = postponeTarget(plan)

    private fun roundTrip(s: WorkoutState): WorkoutState {
        val out = ByteArrayOutputStream()
        kotlinx.coroutines.runBlocking { WorkoutSerializer.writeTo(s, out) }
        return kotlinx.coroutines.runBlocking { WorkoutSerializer.readFrom(ByteArrayInputStream(out.toByteArray())) }
    }

    @Test fun `postponing on the first set moves the exercise after the next one and prefills the next prescription`() = runTest {
        val (repo, storage) = started()
        val before = repo.state.value
        assertEquals(PostponeTarget(2, 5, "Press ficticio"), before.target)
        repo.go(WorkoutEvent.PostponeExercise)
        val s = repo.state.value
        assertEquals(listOf("set-two-press-1", "set-two-press-2", "set-two-press-3", "set-one-squat-1", "set-one-squat-2", "set-one-row-1"), s.ids)
        assertEquals(before.plan.toSet(), s.plan.toSet())
        assertEquals(Phase.ActiveSet, s.phase); assertEquals(0, s.index)
        assertEquals(SetValues(30, 12, null), s.values)
        assertEquals(before.revision + 1, s.revision)
        WorkoutValidation.validate(s)
        assertEquals(s, roundTrip(storage.saved))
    }

    @Test fun `postponing after some sets only reorders unrecorded positions`() = runTest {
        val (repo, _) = started()
        repo.done(); repo.done() // both squat sets
        repo.go(WorkoutEvent.PostponeExercise) // press goes after the row
        val s = repo.state.value
        assertEquals(listOf("set-one-squat-1", "set-one-squat-2", "set-one-row-1", "set-two-press-1", "set-two-press-2", "set-two-press-3"), s.ids)
        assertEquals(2, s.index); assertEquals(listOf("set-one-squat-1", "set-one-squat-2"), s.records.map { it.prescriptionId })
        WorkoutValidation.validate(s)
    }

    @Test fun `postponing is only offered on the first set of an exercise with another exercise after it`() = runTest {
        val (repo, _) = started()
        repo.go(WorkoutEvent.PostponeExercise) // press now first
        repo.done()                            // press 1 recorded
        assertNull(repo.state.value.target)    // press 2: already started
        repo.state.value.let { s -> repo.go(WorkoutEvent.PostponeExercise); assertEquals(s, repo.state.value) }

        repo.completeSet()       // press 2 → resting
        assertEquals(Phase.Resting, repo.state.value.phase)
        assertNull(repo.state.value.target)
        repo.state.value.let { s -> repo.go(WorkoutEvent.PostponeExercise); assertEquals(s, repo.state.value) }
        repo.go(WorkoutEvent.SkipRest)
        repo.done(); repo.done(); repo.done()   // press 3, squat ×2
        val last = repo.state.value
        assertEquals("set-one-row-1", last.plan[last.index].prescriptionId)
        assertNull(last.target)                 // nothing to do instead of the last exercise
        repo.go(WorkoutEvent.PostponeExercise); assertEquals(last, repo.state.value)
    }

    @Test fun `paused, warmed-up, stale and demo states ignore postpone`() = runTest {
        val (paused, _) = started()
        paused.go(WorkoutEvent.PauseSession)
        assertNull(paused.state.value.target)
        paused.state.value.let { s -> paused.go(WorkoutEvent.PostponeExercise); assertEquals(s, paused.state.value) }

        val (warmed, _) = started()
        warmed.go(WorkoutEvent.AddWarmupSet); warmed.done() // a recorded warm-up means the exercise has started
        assertNull(warmed.state.value.target)

        val (stale, _) = started()
        val old = stale.state.value
        stale.go(WorkoutEvent.IncrementWeight)
        val current = stale.state.value
        stale.dispatch(old.command(WorkoutEvent.PostponeExercise))
        assertEquals(current, stale.state.value)

        val demo = WorkoutRepository(MemoryStorage(), { time }, { "demo-${++ids}" })
        demo.restore(); demo.go(WorkoutEvent.StartWorkout)
        assertNull(demo.state.value.target)
        demo.state.value.let { s -> demo.go(WorkoutEvent.PostponeExercise); assertEquals(s, demo.state.value) }
    }

    @Test fun `an unrecorded warm-up moves with its exercise`() = runTest {
        val (repo, _) = started()
        repo.go(WorkoutEvent.AddWarmupSet)
        assertNotNull(repo.state.value.target)
        repo.go(WorkoutEvent.PostponeExercise)
        val s = repo.state.value
        assertEquals(listOf("set-two-press-1", "set-two-press-2", "set-two-press-3", null, "set-one-squat-1", "set-one-squat-2", "set-one-row-1"), s.ids)
        assertTrue(s.plan[3].warmup)
        WorkoutValidation.validate(s)
    }

    @Test fun `a postponed session completes and exports every prescription once in performed order`() = runTest {
        val (repo, storage) = started()
        repo.go(WorkoutEvent.PostponeExercise)
        repeat(6) { repo.done() }
        val s = repo.state.value
        assertEquals(Phase.Completed, s.phase)
        val performed = listOf("set-two-press-1", "set-two-press-2", "set-two-press-3", "set-one-squat-1", "set-one-squat-2", "set-one-row-1")
        assertEquals(performed, s.records.map { it.prescriptionId })
        WorkoutValidation.validate(s)
        assertEquals(s, roundTrip(storage.saved))
        val result = s.toSessionResult()!!
        assertEquals(PerformanceStatus.COMPLETED, result.status)
        assertEquals(performed, result.sets.map { it.prescriptionId })
    }
}
