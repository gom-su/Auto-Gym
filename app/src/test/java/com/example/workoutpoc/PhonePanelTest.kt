package com.example.workoutpoc

import com.example.workoutpoc.data.DemoWorkout
import com.example.workoutpoc.data.WorkoutRepository
import com.example.workoutpoc.data.WorkoutStorage
import com.example.workoutpoc.domain.ClockSnapshot
import com.example.workoutpoc.domain.Phase
import com.example.workoutpoc.domain.PlannedCardio
import com.example.workoutpoc.domain.SessionSource
import com.example.workoutpoc.domain.WorkoutEvent
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.domain.command
import com.example.workoutpoc.domain.openRestSegment
import com.example.workoutpoc.wear.PanelRequestHandler
import com.example.workoutpoc.wear.splitPanelSummary
import com.example.workoutpoc.wearprotocol.ActionRequest
import com.example.workoutpoc.wearprotocol.PanelAction
import com.example.workoutpoc.wearprotocol.PanelAction.*
import com.example.workoutpoc.wearprotocol.PanelMode
import com.example.workoutpoc.wearprotocol.PanelProtocol
import com.example.workoutpoc.wearprotocol.PanelReply
import com.example.workoutpoc.wearprotocol.PanelSnapshot
import com.example.workoutpoc.wearprotocol.ReplyResult
import com.example.workoutpoc.wearprotocol.SummaryRequest
import com.example.workoutpoc.wearprotocol.WatchSetValues
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PhonePanelTest {
    @Test fun `watch records the selected values in one write and corrects the last set with history`() = runTest {
        val fixture = Fixture()
        fixture.begin()
        val active = fixture.panel()
        val performed = WatchSetValues(305, 7, 0)
        assertEquals(ReplyResult.REJECTED, fixture.action(active, RECORD_WATCH_SET, values = performed.copy(rir = null)).result)
        assertTrue(fixture.storage.saved.records.isEmpty())
        val rest = requireNotNull(fixture.action(active, RECORD_WATCH_SET, values = performed).snapshot)
        assertEquals(PanelMode.REST, rest.mode)
        assertEquals(performed, rest.watch?.lastSet?.values)
        assertEquals(ReplyResult.STALE, fixture.action(active, RECORD_WATCH_SET, values = performed).result)

        fixture.seconds = 20
        val extended = requireNotNull(fixture.action(rest, EXTEND_WATCH_REST).snapshot)
        assertEquals(105, fixture.storage.saved.openRestSegment?.configuredSeconds)
        assertEquals(85_000L, extended.timer?.millis)
        assertEquals(15, extended.watch?.restAddSeconds)
        val corrected = performed.copy(reps = 8)
        assertEquals(ReplyResult.STALE, fixture.action(rest, CORRECT_WATCH_SET, 0, corrected).result)
        assertEquals(ReplyResult.OK, fixture.action(extended, CORRECT_WATCH_SET, 0, corrected).result)
        val record = fixture.storage.saved.records.single()
        assertEquals(8, record.values.reps)
        assertEquals(7, record.revisions.single().values.reps)
        assertEquals(105, fixture.storage.saved.openRestSegment?.configuredSeconds)
    }

    @Test fun `expired rest is saved once when phone and watch confirm together`() = runTest {
        val fixture = Fixture()
        fixture.beginRest()
        fixture.seconds = 92
        val expired = fixture.panel()
        assertTrue(expired.alert)
        assertEquals("OK", expired.controls.single { it.action == REST_OK }.label)
        val phoneCommand = fixture.repository.state.value.command(WorkoutEvent.ConfirmRest)

        val watch = async { fixture.action(expired, REST_OK) }
        val phone = async { fixture.repository.dispatch(phoneCommand) }
        assertTrue(watch.await().result in setOf(ReplyResult.OK, ReplyResult.STALE))
        phone.await()
        assertEquals(ReplyResult.STALE, fixture.action(expired, REST_OK).result)
        assertEquals(PanelMode.SET, fixture.panel().mode)
        assertEquals(1, fixture.storage.saved.index)
        assertEquals(92L, fixture.storage.saved.restSegments.single().actualRestSeconds)
        assertEquals(1, fixture.storage.saved.records.size)
    }

    @Test fun `wait takes priority at expiry and a delayed skip is rejected`() = runTest {
        val fixture = Fixture()
        fixture.beginRest()
        val rest = fixture.panel()
        val pending = requireNotNull(fixture.action(rest, WAIT_START).snapshot)
        assertTrue(pending.waitPending)
        assertTrue(pending.controls.any { it.action == WAIT_CANCEL })

        fixture.seconds = 75
        assertEquals(ReplyResult.REJECTED, fixture.action(pending, REST_SKIP).result)
        val waiting = fixture.panel()
        assertEquals(PanelMode.WAIT, waiting.mode)
        assertFalse(waiting.alert)
        assertFalse(waiting.controls.any { it.action == REST_OK })
        assertEquals(ReplyResult.OK, fixture.action(waiting, WAIT_END).result)
        assertTrue(fixture.panel().alert)
    }

    @Test fun `demo retains ten second adjustment and automatic advance`() = runTest {
        val fixture = Fixture()
        fixture.event(WorkoutEvent.StartWorkout)
        fixture.completeSet()
        val rest = fixture.panel()
        assertEquals("+10 s", rest.controls.single { it.action == REST_ADD }.label)
        assertTrue(rest.controls.none { it.action in setOf(REST_REDUCE, WAIT_START, EDIT_LAST, PAUSE) })
        fixture.seconds = 600
        val next = fixture.panel()
        assertEquals(PanelMode.SET, next.mode)
        assertFalse(next.alert)
    }

    @Test fun `failed write keeps the rest pending and publishes no confirmation`() = runTest {
        val fixture = Fixture()
        fixture.beginRest()
        fixture.seconds = 92
        val expired = fixture.panel()
        fixture.storage.fail = true
        try {
            fixture.action(expired, REST_OK)
            fail("Expected the save to fail")
        } catch (_: IOException) {
            assertEquals(Phase.Resting, fixture.storage.saved.phase)
            assertTrue(fixture.panel().alert)
        }
    }

    @Test fun `edit hands off to the phone and cardio completion exposes the summary`() = runTest {
        val fixture = Fixture()
        fixture.beginRest(cardio = true)
        val rest = fixture.panel()
        assertEquals(ReplyResult.OK, fixture.action(rest, EDIT_LAST).result)
        assertEquals(rest.sessionId to 0, fixture.editorTarget)
        assertEquals(ReplyResult.OK, fixture.action(rest, REST_SKIP).result)
        fixture.completeSet()
        assertEquals(ReplyResult.OK, fixture.action(fixture.panel(), REST_SKIP).result)
        val cardio = fixture.panel()
        assertEquals(PanelMode.CARDIO, cardio.mode)
        val paused = requireNotNull(fixture.action(cardio, PAUSE).snapshot)
        assertTrue(paused.paused)
        val summary = requireNotNull(fixture.action(paused, CARDIO_FINISH).snapshot)
        assertEquals(PanelMode.COMPLETED, summary.mode)
        assertTrue(summary.summaryPages > 0)
        val page = fixture.handler.reply(PanelProtocol.SUMMARY_PATH,
            PanelProtocol.encode(SummaryRequest(summary.sessionId, summary.revision, 0)))
        assertEquals(ReplyResult.OK, page.result)
        assertTrue(page.summaryPage.orEmpty().contains("Bici"))
        assertEquals(0, summary.controls.size)
    }

    @Test fun `summary pages preserve line breaks and unicode within the transport limit`() {
        val text = "A".repeat(7_999) + "🏋️\n" + "á\n".repeat(9_000)
        val pages = splitPanelSummary(text)
        assertEquals(text, pages.joinToString(""))
        assertTrue(pages.size > 2)
        pages.forEach {
            val encoded = PanelProtocol.encode(PanelReply(ReplyResult.OK, summaryPage = it))
            assertTrue(encoded.size <= 48_100)
            assertEquals(it, PanelProtocol.decode<PanelReply>(encoded).summaryPage)
        }
    }

    private class Fixture {
        val storage = Storage()
        var seconds = 0L
        var editorTarget: Pair<String, Int>? = null
        val repository = WorkoutRepository(storage,
            { ClockSnapshot(seconds * 1000, 100_000 + seconds * 1000, 1) }, { "current" })
        val handler = PanelRequestHandler(repository, { emptyList() }) { id, index ->
            editorTarget = id to index
            true
        }

        suspend fun beginRest(cardio: Boolean = false) {
            begin(cardio)
            completeSet()
        }

        suspend fun begin(cardio: Boolean = false) {
            val source = SessionSource("program", "Programa", "block", "week", "Semana", 1, "day", "Día")
            repository.load(DemoWorkout.sets.take(2).map { it.copy(restSeconds = 75) }, source,
                cardioPlan = if (cardio) listOf(PlannedCardio("bike", "Bici", 1_800)) else emptyList())
            event(WorkoutEvent.StartWorkout)
        }

        suspend fun completeSet() {
            repository.dispatch(repository.state.value.command(WorkoutEvent.SelectRir, 2))
            event(WorkoutEvent.CompleteSet)
        }

        suspend fun event(event: WorkoutEvent) = repository.dispatch(repository.state.value.command(event))

        suspend fun panel(): PanelSnapshot =
            requireNotNull(handler.reply(PanelProtocol.STATE_PATH, byteArrayOf()).snapshot)

        suspend fun action(panel: PanelSnapshot, action: PanelAction, argument: Int? = null,
                           values: WatchSetValues? = null): PanelReply = handler.reply(
            PanelProtocol.ACTION_PATH, PanelProtocol.encode(
                ActionRequest(panel.sessionId, panel.index, panel.revision, panel.restSegmentId, action, argument, values)),
        )
    }

    private class Storage : WorkoutStorage {
        var saved = WorkoutState()
        var fail = false
        override suspend fun read(): WorkoutState = saved
        override suspend fun write(state: WorkoutState) {
            if (fail) throw IOException("disk full")
            saved = state
        }
    }
}
