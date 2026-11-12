package com.example.workoutpoc

import com.example.workoutpoc.data.DemoWorkout
import com.example.workoutpoc.domain.*
import org.junit.Assert.*
import org.junit.Test

class RestConfirmationTest {
    @Test fun `expiry keeps the rest open and confirmation records the wait exactly once`() {
        val reducer = WorkoutReducer(DemoWorkout.sets)
        val source = SessionSource("program", "Programa", "block", "week", "Semana", 1, "day", "Día")
        val plan = DemoWorkout.sets.take(2).map { it.copy(restSeconds = 75) }
        val loaded = reducer.load(WorkoutState(), plan, source, "loaded")
        fun at(seconds: Long) = ClockSnapshot(seconds * 1000, 1_000_000 + seconds * 1000, 1)
        fun apply(state: WorkoutState, event: WorkoutEvent, seconds: Long) =
            reducer.reduce(state, state.command(event), at(seconds), "session")

        val started = apply(loaded, WorkoutEvent.StartWorkout, 0)
        val resting = apply(started, WorkoutEvent.CompleteSet, 30)
        val paused = apply(resting, WorkoutEvent.PauseSession, 50)
        val resumed = apply(paused, WorkoutEvent.ResumeSession, 60)
        assertEquals(resumed, apply(resumed, WorkoutEvent.RestFinished, 115))
        val command = resumed.command(WorkoutEvent.ConfirmRest)
        assertEquals(resumed, reducer.reduce(resumed, command.copy(restSegmentId = "stale"), at(135), "session"))
        val next = reducer.reduce(resumed, command, at(135), "session")

        assertEquals(Phase.ActiveSet, next.phase)
        assertEquals(1, next.index)
        assertNull(next.openRestSegment)
        assertEquals(95L, next.restSegments.single().actualRestSeconds)
        assertEquals(10_000L, next.restSegments.single().pausedMillis)
        assertEquals(next, reducer.reduce(next, command, at(136), "session"))
    }
}
