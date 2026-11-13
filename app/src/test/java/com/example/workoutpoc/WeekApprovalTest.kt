package com.example.workoutpoc

import com.example.workoutpoc.domain.SessionComment
import com.example.workoutpoc.planning.CardioPrescription
import com.example.workoutpoc.planning.ExercisePrescription
import com.example.workoutpoc.planning.LoadBasis
import com.example.workoutpoc.planning.LoadUnit
import com.example.workoutpoc.planning.PrescribedLoad
import com.example.workoutpoc.planning.SessionPlan
import com.example.workoutpoc.planning.SetKind
import com.example.workoutpoc.planning.SetPrescription
import com.example.workoutpoc.planning.WeekPlan
import com.example.workoutpoc.proposal.ChangeKind
import com.example.workoutpoc.proposal.ExplainedChange
import com.example.workoutpoc.proposal.buildReviewedWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Requirement 2: the whole proposed week, comments anchored to the day, exercise and set they refer to. */
class WeekApprovalTest {
    private val row = ExercisePrescription("ex-row-a", "row", "Remo", listOf(
        SetPrescription("s1", reps = 8, rir = 2, load = PrescribedLoad(30.0, LoadUnit.KG, LoadBasis.TOTAL), restSeconds = 90),
        SetPrescription("s2", reps = 10, rir = 1, kind = SetKind.APPROACH)))
    private val week = WeekPlan("week-1", "Semana 1", 1, listOf(
        SessionPlan("day-a", "Día A", listOf(row, ExercisePrescription("ex-press", "press", "Press", emptyList()))),
        SessionPlan("day-b", "Día B", listOf(ExercisePrescription("ex-row-b", "row", "Remo", listOf(
            SetPrescription("s3", reps = 12, rir = 3, restSeconds = 60))), ), cardio = CardioPrescription("Bicicleta", 1200))))

    private fun comment(id: String, text: String, dayId: String?, exerciseId: String? = null, setIndex: Int? = null) =
        SessionComment(id, text, 1L, "prog", "week-1", null, exerciseId, setIndex, null, dayId)

    @Test fun `an exercise that appears twice shows a comment only with the occurrence it refers to`() {
        val reviewed = buildReviewedWeek(week, "prog", listOf(comment("c1", "Más peso en Día B", "day-b", "row")), emptyList())
        val dayA = reviewed.days.first { it.id == "day-a" }
        val dayB = reviewed.days.first { it.id == "day-b" }
        assertTrue(dayA.exercises.first { it.name == "Remo" }.comments.isEmpty())
        assertEquals(listOf("Más peso en Día B"), dayB.exercises.single { it.name == "Remo" }.comments)
    }

    @Test fun `set comments point at the set position and day comments sit under the day`() {
        val reviewed = buildReviewedWeek(week, "prog", listOf(
            comment("c2", "Serie de agarre neutro", "day-a", "row", setIndex = 1),
            comment("c3", "Día de empuje ligero", "day-a")), emptyList())
        val remo = reviewed.days.first { it.id == "day-a" }.exercises.first { it.name == "Remo" }
        assertTrue(remo.sets[0].comments.isEmpty())
        assertEquals(listOf("Serie de agarre neutro"), remo.sets[1].comments)
        assertEquals(listOf("Día de empuje ligero"), reviewed.days.first { it.id == "day-a" }.comments)
    }

    @Test fun `comments without a day stay visible at week level and resolved ones are not shown`() {
        val old = comment("c4", "Calentamiento largo", null, "row")
        val resolved = comment("c5", "Ya resuelto", "day-a").copy(resolvedAtMillis = 5L)
        val reviewed = buildReviewedWeek(week, "prog", listOf(old, resolved), emptyList())
        // Old comments keep their exercise; the proposal's name is shown, not the internal identifier.
        assertEquals(listOf("Remo: Calentamiento largo"), reviewed.generalComments)
        assertTrue(reviewed.days.none { d -> d.comments.contains("Ya resuelto") })
    }

    @Test fun `an agent explanation is shown once, with its first occurrence`() {
        val change = ExplainedChange(ChangeKind.INCREASE, "row", "Remo", "Sube la carga.", "Última exposición 2026-11-01", "Progresión")
        val reviewed = buildReviewedWeek(week, "prog", emptyList(), listOf(change))
        val shown = reviewed.days.flatMap { it.exercises }.filter { it.name == "Remo" }.map { it.explanations.size }
        assertEquals(listOf(1, 0), shown)
    }

    @Test fun `a comment for a day the proposal does not have is kept at week level`() {
        val reviewed = buildReviewedWeek(week, "prog", listOf(comment("c6", "Revisar el día borrado", "day-gone")), emptyList())
        assertEquals(listOf("[día day-gone (no está en esta propuesta)] Revisar el día borrado"), reviewed.generalComments)
    }

    @Test fun `two different explanations of the same kind are both shown`() {
        val first = ExplainedChange(ChangeKind.INCREASE, "row", "Remo", "Sube series.", "Evidencia A", "Volumen")
        val second = ExplainedChange(ChangeKind.INCREASE, "row", "Remo", "Sube carga.", "Evidencia B", "Progresión")
        val reviewed = buildReviewedWeek(week, "prog", emptyList(), listOf(first, second))
        val shown = reviewed.days.first { it.id == "day-a" }.exercises.first { it.name == "Remo" }.explanations
        assertEquals(2, shown.size)
    }

    @Test fun `sets show their kind, load with unit and basis, reps, RIR only for work, and rest`() {
        val reviewed = buildReviewedWeek(week, "prog", emptyList(), emptyList())
        val remo = reviewed.days.first { it.id == "day-a" }.exercises.first { it.name == "Remo" }
        assertEquals("Trabajo · 30 kg (base total) · 8 reps · RIR 2 · descanso 90 s", remo.sets[0].summary)
        assertEquals("Aproximación · carga sin indicar · 10 reps · descanso 90 s", remo.sets[1].summary)
        assertEquals("Cardio · Bicicleta · 20 min", reviewed.days.first { it.id == "day-b" }.cardio)
    }
}
