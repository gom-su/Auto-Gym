package com.example.workoutpoc

import com.example.workoutpoc.catalog.*
import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.planning.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** ANA-05: compare weeks and blocks of comparable work; a deload is shown but never treated as a regression. */
class PhaseComparisonTest {
    private var time = ClockSnapshot(1_000, 1_800_000_000_000, 5)
    private var ids = 0

    private fun week(id: String, load: Double) = WeekPlan(id, "Semana $id", 1, listOf(SessionPlan("$id-d", "Día", listOf(
        ExercisePrescription("$id-e", "squat", "Sentadilla", listOf(
            SetPrescription("$id-s1", 5, 2, PrescribedLoad(load, LoadUnit.KG, LoadBasis.TOTAL), 0),
            SetPrescription("$id-s2", 5, 2, PrescribedLoad(load, LoadUnit.KG, LoadBasis.TOTAL), 0)))))))
    private val program = TrainingProgram("phase-program", "Programa por fases", listOf(
        TrainingBlock("b1", "Acumulación", listOf(week("w1", 100.0), week("w2", 60.0))),
        TrainingBlock("b2", "Intensificación", listOf(week("w3", 105.0)))))

    private class MemoryStorage(var saved: WorkoutState = WorkoutState()) : WorkoutStorage {
        override suspend fun read() = saved
        override suspend fun write(state: WorkoutState) { saved = state }
    }

    private suspend fun sessions(): List<WorkoutState> {
        ProgramCodec.validate(program)
        val repo = WorkoutRepository(MemoryStorage(), { time }, { "s-${++ids}" })
        repo.restore()
        for (w in listOf("w1", "w2", "w3")) {
            time = time.copy(wallMillis = time.wallMillis + 7 * 86_400_000L)
            val d = program.findDay(w, "$w-d")!!
            repo.load(d.day.toPlannedSets(), d.source)
            repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
            repo.dispatch(repo.state.value.command(WorkoutEvent.AddWarmupSet))      // warm-ups never count
            repeat(4) { repo.dispatch(repo.state.value.command(WorkoutEvent.DecrementWeight)) }
            repo.completeSet()
            repo.completeSet()
            if (w == "w3") repo.dispatch(repo.state.value.command(WorkoutEvent.SkipSet)) // an omitted set is not zero
            else repo.completeSet()
            repo.dispatch(repo.state.value.command(WorkoutEvent.ResetDemo))
        }
        return repo.archived.value.sessions
    }

    @Test fun `deload is listed but never compared, and progress is measured against the last comparable week`() = runTest {
        val s = sessions()
        val deload = WeekAnnotations(deloads = listOf(WeekRef("phase-program", "w2")))
        val rows = phaseComparison("squat", s, listOf(program), deload, PhaseFilter()).single().rows
        assertEquals(listOf("w1", "w2", "w3"), rows.map { it.weekId })
        val (w1, w2, w3) = rows
        assertEquals(2, w1.workSets); assertEquals(1000, w1.topLoadTenths); assertEquals(10_000L, w1.volumeTenths)
        assertNull(w1.comparedWith)
        assertTrue(w2.deload); assertNull(w2.deltaTopLoadTenths); assertNull(w2.deltaVolumeTenths); assertNull(w2.comparedWith)
        assertEquals("Acumulación", w2.blockName)
        // w3 compares with w1, skipping the lighter deload: +5 kg, not a recovery from a "regression".
        assertEquals("Semana w1 (Acumulación)", w3.comparedWith)
        assertEquals(50, w3.deltaTopLoadTenths)
        assertEquals(1, w3.workSets)                                            // the omitted set is not counted as zero
        assertEquals(1050L * 5 - 10_000L, w3.deltaVolumeTenths)
        // Without the mark, the same week would read as a drop: the mark is what keeps it out of comparisons.
        val unmarked = phaseComparison("squat", s, listOf(program), WeekAnnotations(), PhaseFilter()).single().rows
        assertEquals(-400, unmarked[1].deltaTopLoadTenths)
    }

    @Test fun `filters by block, program and deload visibility`() = runTest {
        val s = sessions()
        val deload = WeekAnnotations(deloads = listOf(WeekRef("phase-program", "w2")))
        val b2 = phaseComparison("squat", s, listOf(program), deload, PhaseFilter(blockId = "b2")).single().rows
        assertEquals(listOf("w3"), b2.map { it.weekId }); assertNull(b2.single().comparedWith)
        val hidden = phaseComparison("squat", s, listOf(program), deload, PhaseFilter(includeDeloads = false)).single().rows
        assertEquals(listOf("w1", "w3"), hidden.map { it.weekId }); assertEquals(50, hidden[1].deltaTopLoadTenths)
        assertTrue(phaseComparison("squat", s, listOf(program), deload, PhaseFilter(programId = "other")).isEmpty())
        assertTrue(phaseComparison("bench", s, listOf(program), deload, PhaseFilter()).isEmpty())
    }
}
