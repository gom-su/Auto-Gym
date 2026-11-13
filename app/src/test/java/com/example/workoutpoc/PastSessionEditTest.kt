package com.example.workoutpoc

import com.example.workoutpoc.domain.*
import com.example.workoutpoc.history.review
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Editing an archived planned session (requirement 9). */
class PastSessionEditTest {
    private val source = SessionSource("program", "Programa", "block", "week-1", "Semana", 1, "day", "Día")
    private fun set(n: Int) = PlannedSet("Remo", n, 2, 300, 8, 2, "p$n", "row", restSeconds = 75)
    private fun at(seconds: Long) = ClockSnapshot(seconds * 1000, 1_000_000 + seconds * 1000, 1)

    /** A completed two-set session: set 1 at 10 s, rest confirmed at 85 s, set 2 at 100 s. */
    private fun completedSession(): WorkoutState {
        val reducer = WorkoutReducer(listOf(set(1), set(2)))
        val loaded = reducer.load(WorkoutState(), listOf(set(1), set(2)), source, "s-past")
        var s = reducer.reduce(loaded, loaded.command(WorkoutEvent.StartWorkout), at(0), "s-past")
        s = reducer.reduceRirReady(s, WorkoutEvent.CompleteSet, at(10))
        s = reducer.reduceRirReady(s, WorkoutEvent.ConfirmRest, at(85))
        s = reducer.reduceRirReady(s, WorkoutEvent.CompleteSet, at(100))
        assertEquals(Phase.Completed, s.phase)
        return s
    }

    @Test fun `a corrected date moves the session and keeps its durations and plan link`() {
        val reducer = WorkoutReducer(listOf(set(1), set(2)))
        val past = completedSession()
        val oldStart = past.startedAtMillis!!
        val oldRest = past.review().time!!.restMillis
        val day = 86_400_000L

        val edited = reducer.editPastSession(past, oldStart + day,
            listOf(PastSetEdit(0, SetStatus.COMPLETED, SetValues(275, 8, 1), "corregida")), at(200))

        assertEquals(oldStart + day, edited.startedAtMillis)
        assertEquals(past.source, edited.source)
        assertEquals(oldRest, edited.review().time!!.restMillis)
        assertEquals(past.records[0].completedAtMillis + day, edited.records[0].completedAtMillis)
        // The load and RIR are corrected, the replaced version is kept, and the note is stored.
        assertEquals(SetValues(275, 8, 1), edited.records[0].values)
        assertEquals("corregida", edited.records[0].notes)
        assertEquals(SetValues(300, 8, 2), edited.records[0].revisions.single().values)
        assertNotEquals(past.records[0].values, edited.records[0].values)
    }

    @Test fun `a session without a recorded start gets the corrected date as its start`() {
        val reducer = WorkoutReducer(listOf(set(1), set(2)))
        val undated = completedSession().copy(startedAtMillis = null)
        val date = 1_700_000_000_000L
        val edited = reducer.editPastSession(undated, date, emptyList(), at(200))
        assertEquals(date, edited.startedAtMillis)
        assertEquals(undated.records, edited.records)
    }

    @Test fun `an archived session without a recorded end has no invented duration`() {
        val undated = completedSession().copy(endedAtMillis = null)
        assertEquals(null, undated.activeMillis(at(999)))
    }

    @Test fun `a work set cannot be saved without a RIR`() {
        val reducer = WorkoutReducer(listOf(set(1), set(2)))
        val past = completedSession()
        assertThrows(WorkoutEditException::class.java) {
            reducer.editPastSession(past, null,
                listOf(PastSetEdit(0, SetStatus.COMPLETED, SetValues(275, 8, null), null)), at(200))
        }
    }

    @Test fun `RIR 0 is a valid correction`() {
        val reducer = WorkoutReducer(listOf(set(1), set(2)))
        val edited = reducer.editPastSession(completedSession(), null,
            listOf(PastSetEdit(1, SetStatus.COMPLETED, SetValues(300, 8, 0), null)), at(200))
        assertEquals(0, edited.records[1].values.rir)
    }
}
