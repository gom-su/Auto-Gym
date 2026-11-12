package com.example.workoutpoc

import com.example.workoutpoc.data.WorkoutRepository
import com.example.workoutpoc.data.WorkoutStorage
import com.example.workoutpoc.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Contract §2: a set recorded in a corrected unit keeps its prescription, and the correction is the record's unit. */
class RecordedUnitTest {
    private class MemoryStorage(var saved: WorkoutState = WorkoutState()) : WorkoutStorage {
        override suspend fun read() = saved
        override suspend fun write(state: WorkoutState) { saved = state }
    }

    private val time = ClockSnapshot(1_000, 1_800_000_000_000, 5)
    private val source = SessionSource("prog", "Programa", "block", "week-one", "Semana 1", 1, "day-a", "Día A")
    private val plan = listOf(PlannedSet(exercise = "Press", number = 1, total = 1, weightTenths = 400, reps = 8, rir = 2,
        prescriptionId = "press-1", exerciseId = "press", unit = WeightUnit.KG))

    private suspend fun completedRepo(): WorkoutRepository {
        val repo = WorkoutRepository(MemoryStorage(), { time }, { "session-units" })
        repo.load(plan, source)
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        repo.dispatch(repo.state.value.command(WorkoutEvent.CompleteSet))
        return repo
    }

    @Test fun `recording the set in lb keeps the prescribed kg and stores lb on the record`() = runTest {
        val repo = completedRepo()
        val state = repo.state.value
        assertTrue(repo.edit(state.sessionId, state.revision, 0, SetStatus.COMPLETED, SetValues(375, 8, 2), null, WeightUnit.LB))

        val record = repo.state.value.records.single()
        assertEquals(WeightUnit.LB, record.recordedUnit)
        assertEquals(WeightUnit.KG, repo.state.value.plan.single().unit)
        assertEquals(375, record.values.weightTenths)
    }

    @Test fun `returning to the prescribed unit clears the recorded unit and keeps the old value in the trail`() = runTest {
        val repo = completedRepo()
        var state = repo.state.value
        repo.edit(state.sessionId, state.revision, 0, SetStatus.COMPLETED, SetValues(375, 8, 2), null, WeightUnit.LB)
        state = repo.state.value
        repo.edit(state.sessionId, state.revision, 0, SetStatus.COMPLETED, SetValues(400, 8, 2), null, null)

        val record = repo.state.value.records.single()
        assertNull(record.recordedUnit)
        assertEquals(WeightUnit.LB, record.revisions.last().recordedUnit)
    }
}
