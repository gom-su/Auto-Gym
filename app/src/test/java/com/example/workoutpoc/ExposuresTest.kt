package com.example.workoutpoc

import com.example.workoutpoc.catalog.exposures
import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.planning.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** ANA-01: every execution of an exercise across weeks and programs, comparable without mixing units. */
class ExposuresTest {
    private var time = ClockSnapshot(1_000, 1_800_000_000_000, 5)
    private var ids = 0
    private val program = ProgramCodec.decode(javaClass.classLoader!!.getResourceAsStream("examples/variable-program-v1.json")!!
        .bufferedReader().use { it.readText() })
    /** The same exercises in another program, prescribed in pounds. */
    private val other = program.copy(id = "other-program", name = "Otro programa", blocks = program.blocks.map { b -> b.copy(weeks = b.weeks.map { w ->
        w.copy(days = w.days.map { d -> d.copy(exercises = d.exercises.map { e -> e.copy(sets = e.sets.map { s ->
            s.copy(load = s.load?.let { if (it.unit == LoadUnit.KG) it.copy(unit = LoadUnit.LB, value = 45.0) else it }) }) }) }) }) })

    private class MemoryStorage(var saved: WorkoutState = WorkoutState()) : WorkoutStorage {
        override suspend fun read() = saved
        override suspend fun write(state: WorkoutState) { saved = state }
    }

    private suspend fun session(repo: WorkoutRepository, p: TrainingProgram, week: String, day: String, steps: suspend WorkoutRepository.() -> Unit) {
        time = time.copy(wallMillis = time.wallMillis + 86_400_000)
        val selection = p.findDay(week, day)!!
        repo.load(selection.day.toPlannedSets(), selection.source)
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        repo.steps()
        repo.dispatch(repo.state.value.command(WorkoutEvent.FinishWorkout))
    }
    private suspend fun WorkoutRepository.go(e: WorkoutEvent) = dispatch(state.value.command(e))

    @Test fun `exposures span weeks and programs and never mix units or bases`() = runTest {
        val repo = WorkoutRepository(MemoryStorage(), { time }, { "s-${++ids}" })
        repo.restore()
        session(repo, program, "week-one", "day-one-a") {          // squat KG TOTAL: warm-up, 20 kg × 8, 22.5 kg × 7
            go(WorkoutEvent.AddWarmupSet); repeat(4) { go(WorkoutEvent.DecrementWeight) }; go(WorkoutEvent.CompleteSet); go(WorkoutEvent.SkipRest)
            go(WorkoutEvent.CompleteSet); go(WorkoutEvent.SkipRest)
            dispatch(state.value.command(WorkoutEvent.SelectRir, 2))
            go(WorkoutEvent.DecrementReps); go(WorkoutEvent.CompleteSet)
        }
        session(repo, program, "week-two", "day-two-c") {          // squat KG PER_SIDE, omitted
            go(WorkoutEvent.SkipSet)
        }
        session(repo, other, "week-one", "day-one-a") {            // squat LB TOTAL in another program
            go(WorkoutEvent.CompleteSet)
        }
        repo.go(WorkoutEvent.ResetDemo)
        val groups = exposures("squat", repo.archived.value.sessions)
        assertEquals(3, groups.size)
        val keys = groups.map { it.unit to it.basis }.toSet()
        assertEquals(setOf(WeightUnit.KG to "TOTAL", WeightUnit.KG to "PER_SIDE", WeightUnit.LB to "TOTAL"), keys)
        groups.forEach { g -> assertTrue(g.exposures.all { e -> e.sets.isNotEmpty() }) }

        val kg = groups.single { it.unit == WeightUnit.KG && it.basis == "TOTAL" }.exposures.single()
        assertEquals(3, kg.sets.size)
        assertEquals(2, kg.workSets)                 // the warm-up is listed but not counted
        assertEquals(225, kg.topLoadTenths); assertEquals(5, kg.bestRepsAtTopLoad)
        assertEquals(8 + 5, kg.totalReps)
        assertEquals(200L * 8 + 225L * 5, kg.volumeTenths)
        assertEquals(2, kg.minRir)
        assertTrue(kg.label.startsWith("Programa ficticio variable · Semana 1"))

        val omitted = groups.single { it.basis == "PER_SIDE" }.exposures.single()
        assertEquals(0, omitted.workSets); assertNull(omitted.topLoadTenths); assertTrue(omitted.sets.single().skipped)

        val lb = groups.single { it.unit == WeightUnit.LB }.exposures.single()
        assertEquals(450, lb.topLoadTenths); assertTrue(lb.label.startsWith("Otro programa"))
        // Newest group first.
        assertEquals(WeightUnit.LB, groups.first().unit)
    }

    @Test fun `other exercise ids and the demo are excluded`() = runTest {
        val repo = WorkoutRepository(MemoryStorage(), { time }, { "s-${++ids}" })
        repo.restore()
        session(repo, program, "week-one", "day-one-b") { go(WorkoutEvent.CompleteSet) }
        repo.go(WorkoutEvent.ResetDemo)
        repo.go(WorkoutEvent.StartWorkout); repo.go(WorkoutEvent.CompleteSet)
        val sessions = repo.archived.value.sessions + repo.state.value
        assertTrue(exposures("squat", sessions).isEmpty())
        assertEquals(1, exposures("dumbbell-row", sessions).single().exposures.size)
    }
}
