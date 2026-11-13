package com.example.workoutpoc

import com.example.workoutpoc.catalog.CatalogExercise
import com.example.workoutpoc.catalog.Equipment
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.presentation.*
import org.junit.Assert.*
import org.junit.Test

class WorkoutSurfaceTest {
    private val a = PlannedSet("Remo", 1, 2, 400, 12, 2, "a", "row", basis = "TOTAL", restSeconds = 75)
    private val b = PlannedSet("Jalón", 1, 1, 450, 10, null, "b", "pulldown", basis = "TOTAL", setType = SetType.APPROACH)
    private val source = SessionSource("program", "Programa", "block", "week", "Semana", 1, "day", "Día")
    private fun rest() = WorkoutState(sessionId = "s", phase = Phase.Resting, plan = listOf(a, b), source = source,
        values = SetValues(400, 12, 2), records = listOf(SetRecord(0, a.exercise, 1, SetValues(400, 12, 2), 0, "a")),
        rest = RestDeadline(75_000, 175_000, 1), restSegments = listOf(RestSegment("r", "a", 100_000, 1, 75, startedAtElapsedMillis = 0)))
    private fun at(seconds: Long) = ClockSnapshot(seconds * 1000, 100_000 + seconds * 1000, 1)

    @Test fun `all countdowns have the same signed rounding across zero`() {
        assertEquals("00:05", counterText(4_001, true))
        assertEquals("00:00", counterText(0, true))
        assertEquals("00:00", counterText(-999, true))
        assertEquals("−00:01", counterText(-1_000, true))
        assertEquals("−00:20", counterText(-20_000, true))
        assertEquals("00:18", counterText(18_900))
        assertEquals("−00:03", rest().surfaceTimer(at(78))!!.text)
    }

    @Test fun `pending wait switches label and counts up even before the next reconciliation`() {
        val rest = rest()
        val pending = MachineWait("w", "a", WaitOrigin.REST, restSegmentId = "r", pending = rest.rest)
        val state = rest.copy(machineWaits = listOf(pending))
        assertEquals(SurfaceTimer("Descanso", "00:30", false), state.surfaceTimer(at(45)))
        assertEquals(SurfaceTimer("Espera por máquina", "00:00", true), state.surfaceTimer(at(75)))
        assertEquals(SurfaceTimer("Espera por máquina", "00:18", true), state.surfaceTimer(at(93)))
    }

    @Test fun `work RIR zero enables completion while non-work does not require a RIR`() {
        val active = rest().copy(phase = Phase.ActiveSet, values = SetValues(400, 12, null))
        assertEquals("Selecciona el RIR", active.completionHint())
        assertNull(active.copy(values = active.values.copy(rir = 0)).completionHint())
        assertNull(active.copy(index = 1).completionHint())
        assertFalse(prescriptionLabel(b).contains("RIR"))
        assertFalse(prescriptionLabel(b.copy(setType = SetType.WARMUP)).contains("RIR"))
    }

    @Test fun `next machine load reps and type remain visible on entering the set`() {
        val catalog = listOf(CatalogExercise("row", "Remo", equipment = Equipment.MACHINE),
            CatalogExercise("pulldown", "Jalón", equipment = Equipment.CABLE, setup = "Asiento 3"))
        val preview = rest().seriesNotice(catalog)!!
        assertTrue(preview.detail.contains("Aproximación"))
        assertTrue(preview.changes.contains("Cambia de máquina"))
        assertTrue(preview.changes.contains("Carga 40 → 45 kg total"))
        assertTrue(preview.changes.contains("Repeticiones 12 → 10"))
        assertTrue(preview.changes.contains("Asiento 3"))
        val current = rest().copy(phase = Phase.ActiveSet, index = 1, values = SetValues(450, 10, null)).seriesNotice(catalog)!!
        assertEquals(preview.detail, current.detail)
        assertEquals(preview.changes, current.changes)
    }

    @Test fun `different units show the complete load without a numeric comparison`() {
        val state = rest().copy(plan = listOf(a, b.copy(unit = WeightUnit.LB, basis = "PER_HAND")))
        val preview = state.seriesNotice()!!
        assertTrue(preview.changes.contains("Carga: 45 lb por mano"))
        assertFalse(preview.changes.any { it.startsWith("Carga 40 →") })
        assertFalse(preview.changes.contains("Cambia de máquina"))
    }

    @Test fun `a cardio step takes preview precedence over the following strength set`() {
        val state = rest().copy(cardioPlan = listOf(PlannedCardio("bike", "Bicicleta", 1800, beforeSetId = "b")))
        val preview = state.seriesNotice()!!
        assertEquals("Siguiente · Cardio", preview.heading)
        assertTrue(preview.detail.contains("Bicicleta"))
        assertFalse(preview.detail.contains("Jalón"))
    }

    @Test fun `notification retains a known setup without inventing an equipment category`() {
        val notice = rest().seriesNotice(listOf(CatalogExercise("pulldown", "Jalón", setup = "Asiento 3")))!!
        val compact = notice.compactText()
        assertTrue(compact.contains("Asiento 3"))
        assertTrue(compact.contains("Repeticiones 12 → 10"))
        assertFalse(compact.contains("Cambia de máquina"))
        assertFalse(compact.contains("Equipo:"))
    }
}
