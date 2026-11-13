package com.example.workoutpoc

import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.history.history
import com.example.workoutpoc.planning.DayStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Requirement 1: a full and a partial cardio-only session remain readable after archive and process recreation. */
class CardioHistoryAcceptanceTest {
    private class Bytes(var state: ByteArray? = null, var history: ByteArray? = null)
    private class StateStore(private val bytes: Bytes) : WorkoutStorage {
        override suspend fun read() = bytes.state?.let { WorkoutSerializer.readFrom(ByteArrayInputStream(it)) } ?: WorkoutState()
        override suspend fun write(state: WorkoutState) {
            WorkoutValidation.validate(state)
            bytes.state = ByteArrayOutputStream().also { WorkoutSerializer.writeTo(state, it) }.toByteArray()
        }
    }
    private class HistoryStore(private val bytes: Bytes) : SessionHistory {
        override suspend fun read() = bytes.history?.let { WorkoutHistorySerializer.readFrom(ByteArrayInputStream(it)) } ?: WorkoutHistory()
        override suspend fun archive(session: WorkoutState): Boolean {
            val old = read()
            if (old.sessions.any { it.sessionId == session.sessionId }) return false
            replace(old.copy(sessions = old.sessions + session))
            return true
        }
        override suspend fun replace(history: WorkoutHistory) {
            WorkoutHistorySerializer.validate(history)
            bytes.history = ByteArrayOutputStream().also { WorkoutHistorySerializer.writeTo(history, it) }.toByteArray()
        }
    }

    @Test fun `full and partial cardio sessions appear newest first and retain their measured details after reopening`() = runTest {
        val bytes = Bytes()
        var serial = 0
        fun at(seconds: Long) = ClockSnapshot(seconds * 1000, 1_000_000 + seconds * 1000, 1)
        var clock = at(0)
        val source = SessionSource("program", "Programa", "block", "week", "Semana", 1, "day-d", "Día D")
        val cardio = PlannedCardio("bike", "Bicicleta", 60)
        var repo = WorkoutRepository(StateStore(bytes), { clock }, { "session-${++serial}" }, HistoryStore(bytes))
        suspend fun go(event: WorkoutEvent) = repo.dispatch(repo.state.value.command(event))

        repo.restore()
        repo.load(emptyList(), source, cardioPlan = listOf(cardio))
        go(WorkoutEvent.StartWorkout)
        clock = at(60)
        go(WorkoutEvent.FinishCardio)
        val completeId = repo.state.value.sessionId
        go(WorkoutEvent.ResetDemo)

        clock = at(86_400)
        repo.load(emptyList(), source, cardioPlan = listOf(cardio))
        go(WorkoutEvent.StartWorkout)
        clock = at(86_430)
        go(WorkoutEvent.FinishWorkout)
        val partialId = repo.state.value.sessionId
        go(WorkoutEvent.ResetDemo)

        // New stores and repository read only the bytes written by the production serializers.
        repo = WorkoutRepository(StateStore(bytes), { clock }, { "reopened-${++serial}" }, HistoryStore(bytes))
        repo.restore()
        val listed = history(repo.archived.value.sessions, repo.state.value, clock.wallMillis)
        assertEquals(listOf(partialId, completeId), listed.map { it.sessionId })
        val partial = listed.first()
        val complete = listed.last()
        assertEquals(DayStatus.PARTIAL, partial.status)
        assertEquals(DayStatus.COMPLETED, complete.status)
        for ((summary, seconds) in listOf(partial to 30, complete to 60)) {
            assertTrue(summary.title.contains("Programa"))
            assertTrue(summary.title.contains("Día D"))
            assertTrue(summary.sets.isEmpty())
            assertEquals(seconds, summary.cardio.single().durationSeconds)
            assertEquals("Bicicleta", summary.cardio.single().modality)
            assertEquals(seconds * 1000L, summary.activeMillis)
            val detail = summary.review!!
            assertTrue(detail.exercises.isEmpty())
            assertEquals(summary.cardio, detail.cardio)
            assertEquals(seconds * 1000L, detail.time!!.activityMillis)
            assertEquals(seconds * 1000L, detail.time!!.totalMillis)
            assertEquals(TimedActivity.CARDIO, detail.timeline.single().focus.activity)
        }
    }
}
