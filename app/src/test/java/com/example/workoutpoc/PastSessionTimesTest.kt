package com.example.workoutpoc

import com.example.workoutpoc.data.WorkoutValidation
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.history.review
import com.example.workoutpoc.history.summarize
import com.example.workoutpoc.planning.DayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Correcting the times and the cardio of an archived session item by item (requirement 9). */
class PastSessionTimesTest {
    private val source = SessionSource("program", "Programa", "block", "week-1", "Semana", 1, "day", "Día")
    private fun set(n: Int) = PlannedSet("Remo", n, 2, 300, 8, 2, "p$n", "row", restSeconds = 75)
    private val plan = listOf(set(1), set(2))
    private val bike = PlannedCardio("bike", "Bicicleta", 600)
    private fun at(seconds: Long) = ClockSnapshot(seconds * 1000, 1_000_000 + seconds * 1000, 1)
    private val reducer = WorkoutReducer(plan)
    private fun WorkoutState.go(event: WorkoutEvent, seconds: Long) = reducer.reduceRirReady(this, event, at(seconds))

    /**
     * Set 1 from 0 to 30 s with a 10 s pause; a 75 s rest with an 18 s machine wait, confirmed at 143 s (95 s real);
     * set 2 from 143 to 160 s; a rest to 240 s (80 s real); then 600 s of planned cardio, ending at 840 s.
     */
    private fun session(): WorkoutState {
        val loaded = reducer.load(WorkoutState(), plan, source, "s", cardioPlan = listOf(bike))
        var s = reducer.reduce(loaded, loaded.command(WorkoutEvent.StartWorkout), at(0), "s")
        s = s.go(WorkoutEvent.PauseSession, 5).go(WorkoutEvent.ResumeSession, 15)
        s = s.go(WorkoutEvent.CompleteSet, 30)
        s = s.go(WorkoutEvent.StartMachineWait, 45).go(WorkoutEvent.EndMachineWait, 123)
        s = s.go(WorkoutEvent.ConfirmRest, 143)
        s = s.go(WorkoutEvent.CompleteSet, 160).go(WorkoutEvent.ConfirmRest, 240)
        s = reducer.addCardio(s, s.sessionId, s.revision, CardioEntry("manual-1", "Caminata", 300, null, null, null, 1_000_000))
        s = s.go(WorkoutEvent.FinishCardio, 840)
        assertEquals(Phase.Completed, s.phase)
        return s
    }

    private fun edit(s: WorkoutState, e: PastSessionEdit) = reducer.editPastSession(s, e, at(5000)).also(WorkoutValidation::validate)

    @Test fun `the times are listed item by item`() {
        val times = session().sessionTimes()
        assertTrue(times.measured)
        assertEquals(mapOf(0 to 20_000L, 1 to 17_000L), times.setMillis)
        assertEquals(listOf(95_000L, 80_000L), times.rests.map { it.realMillis })
        assertEquals(listOf(0, 1), times.rests.map { it.setIndex })
        assertEquals(18_000L, times.waits.single().millis)
        assertEquals(0, times.waits.single().setIndex)
        assertTrue(times.waits.single().fromRest)
        assertEquals(10_000L, times.pauses.single().millis)
        assertEquals(0, times.pauses.single().setIndex)
        assertNull(times.editedAtMillis)
    }

    @Test fun `a corrected rest changes its excess, the rest total and the end`() {
        val s = session()
        val rest = s.restSegments.first()
        val edited = edit(s, PastSessionEdit(rests = listOf(PastRestEdit(rest.id, 90, 120_000))))

        val review = edited.review()
        assertEquals(90, review.rests.first().objectiveSeconds)
        assertEquals(120_000L, review.rests.first().realMillis)
        assertEquals(30_000L, review.rests.first().excessMillis)
        assertEquals(s.review().time!!.restMillis + 25_000, review.time!!.restMillis)
        assertEquals(s.endedAtMillis!! + 25_000, edited.endedAtMillis)
        // The plan's objective stays as the reference.
        assertEquals(75, review.rests.first().originalSeconds)
        assertEquals(s.sessionTimes().setMillis, edited.sessionTimes().setMillis)
        assertEquals(at(5000).wallMillis, edited.timesEditedAtMillis)
    }

    @Test fun `a corrected set duration shows in its set and in the activity total`() {
        val s = session()
        val edited = edit(s, PastSessionEdit(setMillis = mapOf(0 to 45_000L)))
        assertEquals(45_000L, edited.sessionTimes().setMillis[0])
        assertEquals(45_000L, edited.review().exercises.single().sets.first().durationMillis)
        assertEquals(s.review().time!!.activityMillis + 25_000, edited.review().time!!.activityMillis)
        assertEquals(s.endedAtMillis!! + 25_000, edited.endedAtMillis)

        // Shortening takes the time from the last spans first, never below zero.
        val shorter = edit(s, PastSessionEdit(setMillis = mapOf(0 to 3_000L)))
        assertEquals(3_000L, shorter.sessionTimes().setMillis[0])
        assertTrue(shorter.timing!!.segments.all { it.durationMillis >= 0 })
    }

    @Test fun `a corrected pause keeps the active time and moves the end`() {
        val s = session()
        val active = s.activeMillis(at(9999))
        val edited = edit(s, PastSessionEdit(pauseMillis = mapOf(0 to 0L)))
        assertEquals(0L, edited.pausedMillis)
        assertEquals(0L, edited.review().time!!.pauseMillis)
        assertEquals(s.endedAtMillis!! - 10_000, edited.endedAtMillis)
        assertEquals(active, edited.activeMillis(at(9999)))
    }

    @Test fun `a corrected machine wait shows in its rest and in the totals`() {
        val s = session()
        val wait = s.machineWaits.single()
        val edited = edit(s, PastSessionEdit(waitMillis = mapOf(wait.id to 30_000L)))
        assertEquals(30_000L, edited.machineWaits.single().measuredMillis)
        assertEquals(30_000L, edited.review().time!!.waitMillis)
        assertEquals(30_000L, edited.review().rests.first().machineWaitMillis)
        assertEquals(s.endedAtMillis!! + 12_000, edited.endedAtMillis)
    }

    @Test fun `shortened planned cardio becomes partial and moves the end`() {
        val s = session()
        val planned = s.cardio.single { it.plannedCardioId == "bike" }
        val edited = edit(s, PastSessionEdit(cardio = listOf(planned.copy(durationSeconds = 300, notes = " suave "))))
        val corrected = edited.cardio.single { it.id == planned.id }
        assertEquals(300, corrected.durationSeconds)
        assertEquals("suave", corrected.notes)
        assertTrue(corrected.partial)
        assertEquals(at(5000).wallMillis, corrected.editedAtMillis)
        assertEquals(s.endedAtMillis!! - 300_000, edited.endedAtMillis)
        assertEquals(DayStatus.PARTIAL, summarize(edited, 0).status)
        assertEquals(DayStatus.COMPLETED, summarize(s, 0).status)
    }

    @Test fun `manual cardio is corrected without touching the timeline`() {
        val s = session()
        val manual = s.cardio.single { it.id == "manual-1" }
        val edited = edit(s, PastSessionEdit(cardio = listOf(manual.copy(modality = "Trote", distanceTenths = 25, distanceUnit = DistanceUnit.KM))))
        val corrected = edited.cardio.single { it.id == "manual-1" }
        assertEquals("Trote", corrected.modality)
        assertEquals(25, corrected.distanceTenths)
        assertNotNull(corrected.editedAtMillis)
        assertEquals(s.timing, edited.timing)
        assertEquals(s.endedAtMillis, edited.endedAtMillis)
        assertNull(edited.timesEditedAtMillis)
    }

    @Test fun `an unchanged edit returns the same session`() {
        val s = session()
        val rest = s.restSegments.first()
        assertSame(s, reducer.editPastSession(s, PastSessionEdit(rests = listOf(PastRestEdit(rest.id, rest.configuredSeconds, rest.measuredMillis)),
            setMillis = mapOf(0 to 20_000L), cardio = s.cardio), at(5000)))
    }

    @Test fun `out of range or unknown corrections are rejected`() {
        val s = session()
        val rest = s.restSegments.first()
        fun rejects(e: PastSessionEdit) = assertThrows(WorkoutEditException::class.java) { reducer.editPastSession(s, e, at(5000)) }
        rejects(PastSessionEdit(rests = listOf(PastRestEdit(rest.id, MAX_REST_OBJECTIVE_SECONDS + 1, 1000))))
        rejects(PastSessionEdit(rests = listOf(PastRestEdit(rest.id, 75, -1))))
        rejects(PastSessionEdit(rests = listOf(PastRestEdit("nope", 75, 1000))))
        rejects(PastSessionEdit(setMillis = mapOf(7 to 1000L)))
        rejects(PastSessionEdit(setMillis = mapOf(0 to MAX_EDITED_MILLIS + 1)))
        rejects(PastSessionEdit(pauseMillis = mapOf(3 to 1000L)))
        rejects(PastSessionEdit(waitMillis = mapOf("nope" to 1000L)))
        rejects(PastSessionEdit(endMillis = s.endedAtMillis!! + 1000))
        rejects(PastSessionEdit(cardio = listOf(s.cardio.single { it.plannedCardioId == "bike" }.copy(durationSeconds = null))))
        rejects(PastSessionEdit(cardio = listOf(s.cardio.single { it.id == "manual-1" }.copy(modality = " "))))
    }

    @Test fun `a session recorded before the timeline gets its end and rests corrected directly`() {
        val s = session().copy(timing = null)
        val rest = s.restSegments.first()
        assertThrows(WorkoutEditException::class.java) { reducer.editPastSession(s, PastSessionEdit(setMillis = mapOf(0 to 1000L)), at(5000)) }
        val newEnd = s.startedAtMillis!! + 3_600_000
        val edited = edit(s, PastSessionEdit(endMillis = newEnd, rests = listOf(PastRestEdit(rest.id, 75, 60_000))))
        assertEquals(newEnd, edited.endedAtMillis)
        assertEquals(3_600_000L - s.pausedMillis, edited.activeMillis(at(9999)))
        assertEquals(60_000L, edited.restSegments.first().measuredMillis)
        assertThrows(WorkoutEditException::class.java) {
            reducer.editPastSession(s, PastSessionEdit(endMillis = s.startedAtMillis!! - 1), at(5000))
        }
    }

    @Test fun `a new start time moves the session and the times keep their lengths`() {
        val s = session()
        val later = s.startedAtMillis!! + 2 * 3_600_000L
        val edited = edit(s, PastSessionEdit(startMillis = later))
        assertEquals(later, edited.startedAtMillis)
        assertEquals(s.endedAtMillis!! + 2 * 3_600_000L, edited.endedAtMillis)
        assertEquals(s.review().time, edited.review().time)
        assertNull(edited.timesEditedAtMillis)
    }
}
