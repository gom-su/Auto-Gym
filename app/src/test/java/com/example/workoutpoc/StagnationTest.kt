package com.example.workoutpoc

import com.example.workoutpoc.catalog.*
import com.example.workoutpoc.data.WorkoutValidation
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.planning.WeekAnnotations
import com.example.workoutpoc.planning.WeekRef
import org.junit.Assert.*
import org.junit.Test

/** INT-03: stagnation alerts state period, data used, limitations and a verifiable explanation; no alert without evidence. */
class StagnationTest {
    private val day = 86_400_000L
    private val t0 = 1_800_000_000_000L

    /** One archived planned session with a single completed squat set. */
    private fun session(n: Int, dayOffset: Int, loadTenths: Int, reps: Int, rir: Int?, unit: WeightUnit = WeightUnit.KG, week: String = "w$n") =
        WorkoutState(sessionId = "s$n", phase = Phase.Completed, index = 0,
            plan = listOf(PlannedSet("Sentadilla", 1, 1, loadTenths, reps, rir, "p$n", "squat", unit, "TOTAL", maxWeightTenths = 5000)),
            source = SessionSource("prog", "Programa", "b", week, "Semana $n", 1, "d$n", "Día"),
            records = listOf(SetRecord(0, "Sentadilla", 1, SetValues(loadTenths, reps, rir), t0 + dayOffset * day, "p$n")),
            values = SetValues(loadTenths, reps, rir), startedAtMillis = t0 + dayOffset * day, endedAtMillis = t0 + dayOffset * day + 1)
            .also(WorkoutValidation::validate)

    private val names = mapOf("squat" to "Sentadilla")

    @Test fun `no improvement over the reference across a long enough window raises a verifiable alert`() {
        val sessions = listOf(session(1, 0, 1000, 5, 2), session(2, 7, 1000, 5, 2), session(3, 14, 975, 5, 2),
            session(4, 21, 1000, 4, 2), session(5, 28, 1000, 5, 1))
        val report = stagnation(names, sessions, WeekAnnotations())
        val a = report.alerts.single()
        assertEquals(t0 + 7 * day, a.fromMillis); assertEquals(t0 + 28 * day, a.toMillis)
        assertEquals(1000, a.reference!!.topLoadTenths); assertEquals(5, a.reference!!.repsAtTop)
        assertEquals(4, a.window.size)
        // The explanation states the rule and the reference; every number can be checked against the listed points.
        assertTrue(a.explanation, a.explanation.contains("100 kg × 5"))
        assertTrue(a.window.none { it.topLoadTenths > 1000 || (it.topLoadTenths == 1000 && it.repsAtTop > 5) })
        assertTrue(a.limitations.any { it.startsWith("Solo series de trabajo completadas") })
        assertTrue(report.insufficient.isEmpty())
    }

    @Test fun `any real progress in the window means no alert`() {
        val more = listOf(session(1, 0, 1000, 5, 2), session(2, 7, 1000, 5, 2), session(3, 14, 1000, 6, 2),
            session(4, 21, 1000, 5, 2), session(5, 28, 1000, 5, 2))
        assertTrue(stagnation(names, more, WeekAnnotations()).alerts.isEmpty())
        val heavier = more.mapIndexed { i, s -> if (i == 3) session(4, 21, 1025, 3, 2) else s }
        assertTrue(stagnation(names, heavier, WeekAnnotations()).alerts.isEmpty())
    }

    @Test fun `insufficient or too short evidence is reported, never alerted`() {
        val four = listOf(session(1, 0, 1000, 5, 2), session(2, 7, 1000, 5, 2), session(3, 14, 1000, 5, 2), session(4, 21, 1000, 5, 2))
        val r1 = stagnation(names, four, WeekAnnotations())
        assertTrue(r1.alerts.isEmpty()); assertTrue(r1.insufficient.single().reason.contains("se necesitan 5"))
        val short = listOf(session(1, 0, 1000, 5, 2), session(2, 1, 1000, 5, 2), session(3, 2, 1000, 5, 2), session(4, 3, 1000, 5, 2), session(5, 4, 1000, 5, 2))
        val r2 = stagnation(names, short, WeekAnnotations())
        assertTrue(r2.alerts.isEmpty()); assertTrue(r2.insufficient.single().reason.contains("al menos 14"))
    }

    @Test fun `deloads are excluded, units stay apart and effort limitations are stated`() {
        val sessions = listOf(session(1, 0, 1000, 5, 2), session(2, 7, 1000, 5, 3), session(3, 14, 600, 5, 4, week = "deload"),
            session(4, 21, 1000, 5, null), session(5, 28, 1000, 5, 3), session(6, 35, 1000, 4, 3),
            session(7, 0, 2200, 5, 2, WeightUnit.LB), session(8, 7, 2250, 5, 2, WeightUnit.LB))
        val report = stagnation(names, sessions, WeekAnnotations(deloads = listOf(WeekRef("prog", "deload"))))
        val a = report.alerts.single()
        assertEquals(WeightUnit.KG, a.unit)
        assertTrue(a.window.none { it.topLoadTenths == 600 })
        assertTrue(a.limitations.any { it.contains("Se excluyeron 1 exposiciones de semanas de descarga") })
        assertTrue(a.limitations.any { it.contains("sin RIR registrado") })
        assertTrue(a.limitations.any { it.contains("más margen de RIR") })
        // The lb group is evaluated on its own and has too little data.
        assertEquals(WeightUnit.LB, report.insufficient.single().unit)
    }
}
