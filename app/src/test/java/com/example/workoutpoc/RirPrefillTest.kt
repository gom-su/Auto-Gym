package com.example.workoutpoc

import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RirPrefillTest {
    @Test fun `RIR comes from matching previous week or latest work set today`() = runTest {
        val storage = object : WorkoutStorage {
            private var state = WorkoutState()
            override suspend fun read() = state
            override suspend fun write(state: WorkoutState) { this.state = state }
        }
        val now = ClockSnapshot(10_000, 100_000, 1)
        val repository = WorkoutRepository(storage, { now })
        val source = SessionSource("program", "Programa", "block", "week-1", "Semana", 1, "day", "Día")
        val work = DemoWorkout.sets.first().copy(exerciseId = "squat", restSeconds = 0)
        val warmup = work.copy(setType = SetType.APPROACH)
        suspend fun go(event: WorkoutEvent, rir: Int? = null) =
            repository.dispatch(repository.state.value.command(event, rir))
        fun rir() = repository.state.value.values.rir

        repository.load(listOf(work), source)
        go(WorkoutEvent.StartWorkout)
        assertNull(rir())
        go(WorkoutEvent.SelectRir, 2)
        repository.completeSet()

        val weekTwo = source.copy(weekId = "week-2")
        repository.load(listOf(warmup, work, work, work), weekTwo, source.weekId)
        go(WorkoutEvent.StartWorkout)
        assertNull(rir())
        go(WorkoutEvent.SelectRir, 4)
        repository.completeSet()
        assertEquals(2, rir())
        repository.completeSet()
        assertEquals(2, rir())
        go(WorkoutEvent.SelectRir, 1)
        repository.completeSet()
        assertEquals(1, rir())
        repository.completeSet()

        repository.load(listOf(work.copy(weightTenths = work.weightTenths + 10)), source.copy(weekId = "week-3"), weekTwo.weekId)
        go(WorkoutEvent.StartWorkout)
        assertNull(rir())
    }
}
