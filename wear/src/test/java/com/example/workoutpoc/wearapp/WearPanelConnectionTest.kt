package com.example.workoutpoc.wearapp

import com.example.workoutpoc.wearprotocol.ActionRequest
import com.example.workoutpoc.wearprotocol.PanelAction
import com.example.workoutpoc.wearprotocol.PanelControl
import com.example.workoutpoc.wearprotocol.PanelMode
import com.example.workoutpoc.wearprotocol.PanelProtocol
import com.example.workoutpoc.wearprotocol.PanelReply
import com.example.workoutpoc.wearprotocol.PanelSnapshot
import com.example.workoutpoc.wearprotocol.PanelTimer
import com.example.workoutpoc.wearprotocol.ReplyResult
import com.example.workoutpoc.wearprotocol.TimerKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WearPanelConnectionTest {
    @Test fun oneCommandAtATimeAndOldSnapshotCannotConfirmRest() = runTest {
        val confirmation = CompletableDeferred<PanelReply>()
        val phone = FakePhone(restReady())
        phone.applyAction = { confirmation.await() }
        val connection = open(phone)

        connection.perform(OK, phone.snapshot)
        connection.perform(OK, phone.snapshot)
        runCurrent()
        assertTrue(connection.state.value.busy)
        assertEquals(listOf(ActionRequest("session", 1, 1, "rest-A", PanelAction.REST_OK)), phone.actions)

        val oldRest = phone.snapshot
        phone.snapshot = restReady().copy(revision = 4, restSegmentId = "rest-B")
        confirmation.complete(PanelReply(ReplyResult.OK, phone.snapshot))
        runCurrent()
        connection.perform(OK, oldRest)
        runCurrent()
        assertFalse(connection.state.value.busy)
        assertEquals(1, phone.actions.size)
        assertEquals("rest-B", connection.state.value.snapshot?.restSegmentId)
    }

    @Test fun acknowledgementTimeoutFetchesSavedStateWithoutReplayingTheCommand() = runTest {
        val unanswered = CompletableDeferred<PanelReply>()
        val phone = FakePhone(restReady())
        phone.applyAction = {
            phone.snapshot = PanelSnapshot(sessionId = "session", revision = 2, mode = PanelMode.SET)
            unanswered.await()
        }
        val connection = open(phone)
        connection.perform(OK, phone.snapshot)
        runCurrent()
        assertTrue(connection.state.value.busy)
        assertEquals(PanelMode.REST, connection.state.value.snapshot?.mode)

        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1, phone.actions.size)
        assertEquals(PanelMode.SET, connection.state.value.snapshot?.mode)
        assertTrue(connection.state.value.connected)
        assertFalse(connection.state.value.busy)
        assertNotNull(connection.state.value.feedback)
    }

    @Test fun disconnectionIgnoresInFlightReplyAndRefreshesOnlyTrustedNode() = runTest {
        val reply = CompletableDeferred<PanelReply>()
        val phone = FakePhone(restReady())
        phone.applyAction = { reply.await() }
        val connection = open(phone)
        val reads = phone.reads
        phone.invalidate("other")
        runCurrent()
        assertEquals(reads, phone.reads)

        connection.perform(OK, phone.snapshot)
        runCurrent()
        phone.disconnect()
        reply.complete(PanelReply(ReplyResult.OK, PanelSnapshot(mode = PanelMode.SET)))
        runCurrent()
        assertFalse(connection.state.value.connected)
        assertEquals(PanelMode.REST, connection.state.value.snapshot?.mode)
        assertNotNull(connection.state.value.feedback)
    }

    @Test fun plannedRestExpiryChecksPhoneWhenNoChangeMarkerArrives() = runTest {
        val phone = FakePhone(restReady().copy(
            alert = false,
            controls = emptyList(),
            timer = PanelTimer(1_500, TimerKind.REST, true),
        ))
        val connection = open(phone)
        connection.monitor("session")
        connection.hide()
        runCurrent()
        val reads = phone.reads
        assertEquals(1_500L, connection.state.value.restDeadlineElapsedMillis)

        advanceTimeBy(1_500)
        assertEquals(reads, phone.reads)
        assertEquals(false, connection.state.value.snapshot?.alert)
        phone.snapshot = restReady()
        runCurrent()
        assertEquals(reads + 1, phone.reads)
        assertEquals(true, connection.state.value.snapshot?.alert)
    }

    private fun TestScope.open(phone: FakePhone): WearPanelConnection =
        WearPanelConnection(phone, backgroundScope) { currentTime }.also {
            it.open()
            runCurrent()
            assertTrue(it.state.value.connected)
        }

    private fun restReady() = PanelSnapshot(
        sessionId = "session",
        index = 1,
        revision = 1,
        restSegmentId = "rest-A",
        mode = PanelMode.REST,
        timer = PanelTimer(0, TimerKind.REST, true),
        alert = true,
        controls = listOf(OK),
    )

    private class FakePhone(var snapshot: PanelSnapshot) : WearPanelTransport {
        private var phones = setOf(WearPhone("phone", nearby = true))
        private var phonesChanged: ((Set<WearPhone>) -> Unit)? = null
        private var panelChanged: ((String) -> Unit)? = null
        var applyAction: suspend (ActionRequest) -> PanelReply = { PanelReply(ReplyResult.OK, snapshot) }
        val actions = mutableListOf<ActionRequest>()
        var reads = 0
            private set

        override fun listen(onPhonesChanged: (Set<WearPhone>) -> Unit, onPanelChanged: (String) -> Unit) {
            phonesChanged = onPhonesChanged
            panelChanged = onPanelChanged
        }

        override fun stopListening() {
            phonesChanged = null
            panelChanged = null
        }

        override suspend fun reachablePhones(): Set<WearPhone> = phones

        override suspend fun request(nodeId: String, path: String, body: ByteArray): ByteArray {
            assertEquals("phone", nodeId)
            val reply = when (path) {
                PanelProtocol.STATE_PATH -> {
                    reads++
                    PanelReply(ReplyResult.OK, snapshot)
                }
                PanelProtocol.ACTION_PATH -> {
                    val action = PanelProtocol.decode<ActionRequest>(body)
                    actions += action
                    applyAction(action)
                }
                else -> error("Unexpected RPC path $path")
            }
            return PanelProtocol.encode(reply)
        }

        fun invalidate(nodeId: String = "phone") = panelChanged?.invoke(nodeId)

        fun disconnect() {
            phones = emptySet()
            phonesChanged?.invoke(phones)
        }
    }

    private companion object {
        val OK = PanelControl(PanelAction.REST_OK, "OK")
    }
}
