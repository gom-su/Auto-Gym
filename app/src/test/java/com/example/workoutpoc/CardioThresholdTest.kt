package com.example.workoutpoc

import com.example.workoutpoc.domain.cardioIsDone
import com.example.workoutpoc.planning.DayAdherence
import com.example.workoutpoc.planning.DayStatus
import com.example.workoutpoc.planning.partialText
import com.example.workoutpoc.planning.SessionPlan
import org.junit.Assert.*
import org.junit.Test

/** Cardio counts as done at 95% of the prescribed duration; anything less is "sin completar". */
class CardioThresholdTest {
    @Test fun `cardio at 95 percent is done and anything below is not`() {
        assertTrue(cardioIsDone(1710, 1800))      // exactly 95%
        assertTrue(cardioIsDone(1800, 1800))
        assertTrue(cardioIsDone(1900, 1800))      // over the plan
        assertFalse(cardioIsDone(1709, 1800))     // 94.9%
        assertFalse(cardioIsDone(90, 1800))       // 5%
        assertFalse(cardioIsDone(null, 1800))     // nothing recorded
    }

    @Test fun `partial label says which part is missing`() {
        val day = SessionPlan(id = "d", name = "A", exercises = emptyList(), cardio = null)
        fun adherence(strength: Boolean, pending: Boolean, prescribed: Int = 4) = DayAdherence(
            day = day, status = DayStatus.PARTIAL, prescribedSets = prescribed, completedSets = 0, omittedSets = 0,
            unrecordedSets = 0, extraSets = 0, cardioMinutes = 0, sessions = 1, inProgress = false,
            strengthDone = strength, cardioPending = pending, cardioPlanned = true)

        assertEquals("Fuerza completada · Cardio sin completar", adherence(strength = true, pending = true).partialLabel)
        assertEquals("Cardio completado · Fuerza sin completar", adherence(strength = false, pending = false).partialLabel)
        assertEquals("Parcial", adherence(strength = false, pending = true).partialLabel)
        assertEquals("Cardio sin completar", adherence(strength = false, pending = true, prescribed = 0).partialLabel)
        assertEquals("Fuerza completada · Cardio sin completar", partialText(hasStrength = true, inProgress = false,
            strengthDone = true, cardioPlanned = true, cardioPending = true))
    }
}
