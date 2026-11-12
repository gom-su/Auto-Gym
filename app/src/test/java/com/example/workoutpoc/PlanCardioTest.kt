package com.example.workoutpoc

import com.example.workoutpoc.planning.*
import org.junit.Assert.*
import org.junit.Test

class PlanCardioTest {
    private fun day(id: String, exercises: Boolean, cardio: Int?) = SessionPlan(
        id = id, name = "Día $id",
        exercises = if (exercises) listOf(ExercisePrescription(
            id = "$id-ex", exerciseId = "press", name = "Press",
            sets = listOf(SetPrescription(id = "$id-set", reps = 8, rir = 2, load = PrescribedLoad(40.0, LoadUnit.KG, LoadBasis.TOTAL)))))
        else emptyList(),
        cardio = cardio?.let { CardioPrescription("Bicicleta", it) })

    private fun program(vararg days: SessionPlan) = TrainingProgram("prog", "Programa", listOf(TrainingBlock("block", "Bloque",
        listOf(WeekPlan("week", "Semana 1", 1, days.toList())))))

    @Test fun `a cardio-only day is valid and a day with neither strength nor cardio is not`() {
        ProgramCodec.validate(program(day("d", exercises = false, cardio = 3600)))
        val error = runCatching { ProgramCodec.validate(program(day("e", exercises = false, cardio = null))) }.exceptionOrNull()
        assertTrue(error is ProgramValidationException)
        assertTrue(error!!.message!!.contains("falta un ejercicio o un bloque de cardio"))
    }

    @Test fun `cardio duration must be a positive bounded value`() {
        val error = runCatching { ProgramCodec.validate(program(day("f", exercises = true, cardio = 0))) }.exceptionOrNull()
        assertTrue(error is ProgramValidationException)
        assertTrue(error!!.message!!.contains("duración de cardio fuera de rango"))
    }

    @Test fun `distribution rules report missing and wrong cardio and strength on a cardio-only day`() {
        val week = WeekPlan("week", "Semana 1", 1, listOf(
            day("a", exercises = true, cardio = 1800),
            day("b", exercises = true, cardio = 900),
            day("d", exercises = true, cardio = null)))
        val rules = listOf(
            CardioDayRule("a", 1800),
            CardioDayRule("b", 1800),
            CardioDayRule("c", 1800),
            CardioDayRule("d", 3600, strengthAllowed = false))

        val problems = cardioDistributionViolations(week, rules)

        assertEquals(listOf(
            "Día b: el cardio debe durar 30 min.",
            "Semana 1: falta el día c.",
            "Día d: falta el cardio obligatorio de 60 min.",
            "Día d: este día debe ser solo cardio."), problems)
    }

    @Test fun `a compliant week has no distribution problems`() {
        val week = WeekPlan("week", "Semana 1", 1, listOf(day("a", true, 1800), day("d", false, 3600)))
        assertTrue(cardioDistributionViolations(week, listOf(CardioDayRule("a", 1800), CardioDayRule("d", 3600, false))).isEmpty())
    }
}
