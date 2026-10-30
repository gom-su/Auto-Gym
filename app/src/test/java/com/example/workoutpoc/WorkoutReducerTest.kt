package com.example.workoutpoc

import com.example.workoutpoc.data.DemoWorkout
import com.example.workoutpoc.domain.*
import org.junit.Assert.*
import org.junit.Test

class WorkoutReducerTest {
    private val reducer = WorkoutReducer(DemoWorkout.sets)
    private val now = ClockSnapshot(1_000, 100_000, 5)
    private fun step(s: WorkoutState, e: WorkoutEvent, rir: Int? = null, time: ClockSnapshot = now): WorkoutState =
        reducer.reduce(s, s.command(e, rir), time, "session")
    private fun started(): WorkoutState = step(WorkoutState(), WorkoutEvent.StartWorkout)

    @Test fun `acceptance values and stored record`() {
        var s = started()
        s = step(s, WorkoutEvent.IncrementWeight)
        s = step(s, WorkoutEvent.DecrementReps)
        s = step(s, WorkoutEvent.SelectRir, 1)
        assertEquals(SetValues(325, 7, 1), s.values)
        s = step(s, WorkoutEvent.CompleteSet)
        assertEquals(Phase.Resting, s.phase)
        assertEquals(1, s.records.size)
        assertEquals(SetValues(325, 7, 1), s.records.single().values)
        assertEquals(100_000L, s.records.single().completedAtMillis)
        assertEquals(10_000L, s.rest!!.remainingMillis(now))
    }

    @Test fun `weight reps and rir bounds`() {
        var s = started()
        repeat(200) { s = step(s, WorkoutEvent.IncrementWeight) }
        repeat(40) { s = step(s, WorkoutEvent.IncrementReps) }
        assertEquals(3000, s.values.weightTenths)
        assertEquals(30, s.values.reps)
        repeat(200) { s = step(s, WorkoutEvent.DecrementWeight) }
        repeat(40) { s = step(s, WorkoutEvent.DecrementReps) }
        assertEquals(0, s.values.weightTenths)
        assertEquals(0, s.values.reps)
        assertEquals(s, step(s, WorkoutEvent.CompleteSet))
        assertEquals(s, step(s, WorkoutEvent.SelectRir, 5))
        assertEquals(s, step(s, WorkoutEvent.SelectRir, -1))
        assertEquals(4, step(s, WorkoutEvent.SelectRir, 4).values.rir)
    }

    @Test fun `duplicate complete skip and stale session cannot advance twice`() {
        val active = started()
        val command = active.command(WorkoutEvent.CompleteSet)
        val resting = reducer.reduce(active, command, now, "unused")
        assertEquals(resting, reducer.reduce(resting, command, now, "unused"))
        val next = step(resting, WorkoutEvent.SkipRest)
        assertEquals(next, reducer.reduce(next, resting.command(WorkoutEvent.SkipRest), now, "unused"))
        assertEquals(next, reducer.reduce(next, next.command(WorkoutEvent.CompleteSet).copy(sessionId = "old"), now, "unused"))
        assertEquals(1, next.index)
        assertEquals(1, next.records.size)
    }

    @Test fun `same exercise carries last weight but new exercise loads defaults`() {
        var s = step(started(), WorkoutEvent.IncrementWeight)
        repeat(3) { index ->
            s = step(s, WorkoutEvent.CompleteSet)
            s = step(s, WorkoutEvent.SkipRest)
            assertEquals(if (index < 2) 325 else 550, s.values.weightTenths)
        }
        assertEquals("Jalón al pecho", DemoWorkout.sets[s.index].exercise)
        assertEquals(10, s.values.reps)
    }

    @Test fun `seven sets finish with no final rest or overflow`() {
        var s = started()
        repeat(7) { index ->
            s = step(s, WorkoutEvent.CompleteSet)
            assertEquals(index + 1, s.records.size)
            if (index < 6) s = step(s, WorkoutEvent.SkipRest)
        }
        assertEquals(Phase.Completed, s.phase)
        assertNull(s.rest)
        assertEquals((0..6).toList(), s.records.map { it.index })
        assertEquals(s, step(s, WorkoutEvent.CompleteSet))
        assertEquals(s, step(s, WorkoutEvent.SkipRest))
    }

    @Test fun `rest uses monotonic clock and extension invalidates old finish`() {
        val resting = step(started(), WorkoutEvent.CompleteSet)
        val clockChanged = now.copy(elapsedMillis = 2_000, wallMillis = 999_999)
        assertEquals(9_000L, resting.rest!!.remainingMillis(clockChanged))
        assertEquals(resting, step(resting, WorkoutEvent.RestFinished, time = clockChanged))
        val extended = step(resting, WorkoutEvent.AddRestTime)
        assertEquals(20_000L, extended.rest!!.remainingMillis(now))
        assertEquals(extended, reducer.reduce(extended, resting.command(WorkoutEvent.RestFinished), now.copy(elapsedMillis = 11_000), "unused"))
        val advanced = step(extended, WorkoutEvent.RestFinished, time = now.copy(elapsedMillis = 21_000))
        assertEquals(Phase.ActiveSet, advanced.phase)
        assertEquals(1, advanced.index)
    }

    @Test fun `reboot uses wall fallback`() {
        val resting = step(started(), WorkoutEvent.CompleteSet)
        assertEquals(5_000L, resting.rest!!.remainingMillis(ClockSnapshot(100, 105_000, 6)))
        assertEquals(Phase.ActiveSet, step(resting, WorkoutEvent.RestFinished,
            time = ClockSnapshot(100, 110_000, 6)).phase)
    }

    @Test fun `undo restores exactly the last saved set even from completed`() {
        val first = started()
        assertEquals(first, step(first, WorkoutEvent.UndoLastSet))
        var s = first
        repeat(7) {
            s = step(s, WorkoutEvent.CompleteSet)
            if (s.phase == Phase.Resting) s = step(s, WorkoutEvent.SkipRest)
        }
        s = step(s, WorkoutEvent.UndoLastSet)
        assertEquals(6, s.index)
        assertEquals(6, s.records.size)
        assertEquals(Phase.ActiveSet, s.phase)
        repeat(6) { s = step(s, WorkoutEvent.UndoLastSet) }
        assertEquals(0, s.index)
        assertTrue(s.records.isEmpty())
        assertEquals(s, step(s, WorkoutEvent.UndoLastSet))
    }

    @Test fun `finish early keeps records and reset rejects old commands`() {
        val resting = step(started(), WorkoutEvent.CompleteSet)
        val ended = step(resting, WorkoutEvent.FinishWorkout)
        assertEquals(Phase.Completed, ended.phase)
        assertTrue(ended.endedEarly)
        assertEquals(resting.records, ended.records)
        val reset = reducer.reduce(ended, ended.command(WorkoutEvent.ResetDemo), now, "new-session")
        assertEquals(Phase.Idle, reset.phase)
        assertTrue(reset.records.isEmpty())
        assertEquals(reset, reducer.reduce(reset, resting.command(WorkoutEvent.SkipRest), now, "unused"))
    }
}
