package com.example.workoutpoc

import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.exchange.PerformanceStatus
import com.example.workoutpoc.exchange.toSessionResult
import com.example.workoutpoc.history.summarize
import com.example.workoutpoc.planning.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PlannedCardioTest {
    private val source = SessionSource("program", "Programa", "block", "week", "Semana", 1, "day-d", "Día D")
    private val cardio = PlannedCardio("bike", "Bicicleta", 3600)

    @Test fun `cardio only survives restore, excludes pause and archives a partial result`() = runTest {
        val storage = object : WorkoutStorage {
            private var saved: ByteArray? = null
            override suspend fun read() = saved?.let { WorkoutSerializer.readFrom(ByteArrayInputStream(it)) } ?: WorkoutState()
            override suspend fun write(state: WorkoutState) {
                WorkoutValidation.validate(state)
                saved = ByteArrayOutputStream().also { WorkoutSerializer.writeTo(state, it) }.toByteArray()
            }
        }
        var time = ClockSnapshot(0, 100_000, 1)
        fun at(seconds: Long) { time = ClockSnapshot(seconds * 1000, 100_000 + seconds * 1000, 1) }
        val history = MemorySessionHistory()
        var repo = WorkoutRepository(storage, { time }, history = history)
        suspend fun go(event: WorkoutEvent) = repo.dispatch(repo.state.value.command(event))

        val day = SessionPlan("d".repeat(128), "Día D", emptyList(), CardioPrescription(" Bicicleta ", 3600))
        val program = TrainingProgram("program", "Programa", listOf(TrainingBlock("block", "Bloque",
            listOf(WeekPlan("week", "Semana", 1, listOf(day))))))
        val decoded = ProgramCodec.decode(ProgramCodec.json.encodeToString(ProgramPackage.serializer(), ProgramPackage(1, program)))
        val selection = requireNotNull(decoded.findDay("week", day.id))
        val planned = requireNotNull(selection.day.toPlannedCardio())
        assertEquals(planned.stepId, day.toPlannedCardio()?.stepId)
        assertEquals(LoadResult.LOADED, repo.loadDay(selection))
        go(WorkoutEvent.StartWorkout)
        assertEquals(Phase.ActiveCardio, repo.state.value.phase)
        assertTrue(repo.state.value.activePlan().isEmpty())
        at(50)
        go(WorkoutEvent.PauseSession)
        at(80)
        repo = WorkoutRepository(storage, { time }, history = history)
        repo.restore()
        assertEquals(3_550_000L, repo.state.value.cardioRemainingMillis(time))
        go(WorkoutEvent.ResumeSession)
        at(125)
        go(WorkoutEvent.FinishCardio)
        val completed = repo.state.value
        val recorded = completed.cardio.single()
        assertEquals(95, recorded.durationSeconds)
        assertEquals(planned.stepId, recorded.plannedCardioId)
        assertEquals("Bicicleta", recorded.modality)
        assertTrue(recorded.partial)
        assertEquals(30_000L, completed.pausedMillis)
        assertEquals(Phase.Completed, completed.phase)
        assertEquals(DayStatus.PARTIAL, summarize(completed, time.wallMillis).status)
        assertEquals(PerformanceStatus.PARTIAL, completed.toSessionResult()?.cardio?.single()?.status)
        go(WorkoutEvent.ResetDemo)
        assertEquals(completed, repo.archived.value.sessions.single())
        val week = adherence(listOf(decoded), repo.archived.value.sessions, WeekAnnotations()).single()
        assertEquals(DayStatus.PARTIAL, week.days.single().status)
        assertEquals(1, week.days.single().cardioMinutes)
    }

    @Test fun `cardio between sets respects the planned sequence`() {
        val reducer = WorkoutReducer(DemoWorkout.sets)
        val plan = DemoWorkout.sets.take(2).mapIndexed { index, set ->
            set.copy(prescriptionId = "set-$index", restSeconds = 60)
        }
        val loaded = reducer.load(WorkoutState(), plan, source, "loaded",
            cardioPlan = listOf(cardio.copy(beforeSetId = "set-1", plannedSeconds = 30)))
        fun step(state: WorkoutState, event: WorkoutEvent, seconds: Long): WorkoutState =
            reducer.reduce(state, state.command(event), ClockSnapshot(seconds * 1000, 100_000 + seconds * 1000, 1), "session")
                .also(WorkoutValidation::validate)
        val started = step(loaded, WorkoutEvent.StartWorkout, 0)
        val rest = step(started, WorkoutEvent.CompleteSet, 20)
        assertTrue(rest.nextSetLabel(plan).startsWith("Bicicleta"))
        val running = step(rest, WorkoutEvent.ConfirmRest, 80)
        assertEquals(Phase.ActiveCardio, running.phase)
        val resumed = step(running, WorkoutEvent.FinishCardio, 110)
        assertEquals(1, resumed.index)
        assertEquals(Phase.ActiveSet, resumed.phase)
        val completed = step(resumed, WorkoutEvent.CompleteSet, 130)
        assertEquals(DayStatus.COMPLETED, summarize(completed, 230_000).status)
        assertEquals(30, completed.cardio.single().durationSeconds)
    }
}
