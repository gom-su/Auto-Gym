package com.example.workoutpoc

import com.example.workoutpoc.data.DemoWorkout
import com.example.workoutpoc.data.WorkoutSerializer
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.history.review
import com.example.workoutpoc.planning.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SessionTimingTest {
    private val reducer = WorkoutReducer(DemoWorkout.sets)
    private val source = SessionSource("program", "Programa", "block", "week", "Semana", 1, "day", "Día")
    private val plan = DemoWorkout.sets.take(2).map {
        it.copy(weightTenths = 400, weightStepTenths = 25, rir = 2, restSeconds = 75)
    }

    private fun step(state: WorkoutState, event: WorkoutEvent, seconds: Long, rir: Int? = null): WorkoutState =
        reducer.reduce(state, state.command(event, rir), ClockSnapshot(seconds * 1000, 100_000 + seconds * 1000, 1), "session")

    @Test fun `review compares actual results and includes the wait after rest expiry in the total`() = runTest {
        var state = reducer.load(WorkoutState(), plan, source, "loaded")
        state = step(state, WorkoutEvent.StartWorkout, 0)
        state = step(state, WorkoutEvent.DecrementWeight, 5)
        state = step(state, WorkoutEvent.SelectRir, 15, 1)
        state = step(state, WorkoutEvent.CompleteSet, 30)
        state = step(state, WorkoutEvent.ConfirmRest, 125)
        state = step(state, WorkoutEvent.CompleteSet, 150)
        val bytes = ByteArrayOutputStream().also { WorkoutSerializer.writeTo(state, it) }.toByteArray()
        val restored = WorkoutSerializer.readFrom(ByteArrayInputStream(bytes))
        val review = restored.review()
        assertEquals(listOf(TimedActivity.SET, TimedActivity.REST, TimedActivity.SET), review.timeline.map { it.focus.activity })
        assertEquals(listOf(30_000L, 95_000L, 25_000L), review.timeline.map { it.durationMillis })
        assertEquals(150_000L, restored.measuredDurationMillis)
        val first = review.exercises.first().sets.first()
        assertEquals("40 kg", first.targetWeight)
        assertEquals("37,5 kg", first.actualWeight)
        assertEquals(2, first.targetRir)
        assertEquals(1, first.actualRir)
        val day = SessionPlan("day", "Día", listOf(ExercisePrescription("prescription", "exercise", "Ejercicio",
            listOf(SetPrescription("set-1"), SetPrescription("set-2")))))
        val week = WeekPlan("week", "Semana", 1, listOf(day))
        val program = TrainingProgram("program", "Programa", listOf(TrainingBlock("block", "Bloque", listOf(week))))
        val old = restored.copy(sessionId = "legacy", timing = null)
        val adherence = adherence(listOf(program), listOf(restored, old), WeekAnnotations()).single()
        assertEquals(1, adherence.measuredSessions)
        assertEquals(150_000L, adherence.averageSessionMillis)
        assertNull(old.review().time)
    }

    @Test fun `a pause splits the timed work set and contributes once to the total`() {
        var state = reducer.load(WorkoutState(), plan.take(1), source, "loaded")
        state = step(state, WorkoutEvent.StartWorkout, 0)
        state = step(state, WorkoutEvent.PauseSession, 10)
        state = step(state, WorkoutEvent.ResumeSession, 15)
        state = step(state, WorkoutEvent.CompleteSet, 40)
        val review = state.review()
        assertEquals(listOf(TimedActivity.SET, TimedActivity.PAUSE, TimedActivity.SET), review.timeline.map { it.focus.activity })
        assertEquals(listOf(10_000L, 5_000L, 25_000L), review.timeline.map { it.durationMillis })
        assertEquals(35_000L, review.exercises.single().sets.single().durationMillis)
        assertEquals(40_000L, state.measuredDurationMillis)
    }
}
