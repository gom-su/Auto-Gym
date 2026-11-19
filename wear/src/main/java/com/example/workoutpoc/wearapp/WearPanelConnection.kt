package com.example.workoutpoc.wearapp

import com.example.workoutpoc.wearprotocol.ActionRequest
import com.example.workoutpoc.wearprotocol.PanelAction
import com.example.workoutpoc.wearprotocol.PanelControl
import com.example.workoutpoc.wearprotocol.PanelMode
import com.example.workoutpoc.wearprotocol.PanelProtocol
import com.example.workoutpoc.wearprotocol.PanelReply
import com.example.workoutpoc.wearprotocol.PanelSnapshot
import com.example.workoutpoc.wearprotocol.ReplyResult
import com.example.workoutpoc.wearprotocol.SummaryRequest
import com.example.workoutpoc.wearprotocol.TimerKind
import com.example.workoutpoc.wearprotocol.panelCounterText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class WearPanelState(
    val snapshot: PanelSnapshot? = null,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val feedback: String? = null,
    val timerText: String? = null,
    val summary: Map<Int, String> = emptyMap(),
    val summaryLoading: Boolean = false,
    val summaryFailed: Boolean = false,
    internal val panelVisible: Boolean = false,
    internal val snapshotSequence: Long = 0,
    internal val restDeadlineElapsedMillis: Long? = null,
)

/** Main-thread state shared by the visible panel and the foreground session service. */
class WearPanelConnection internal constructor(
    private val transport: WearPanelTransport,
    private val scope: CoroutineScope,
    private val elapsedMillis: () -> Long,
) {
    private val mutableState = MutableStateFlow(WearPanelState())
    val state = mutableState.asStateFlow()
    private val requests = Mutex()
    private val refreshSignals = Channel<Unit>(Channel.CONFLATED)
    private var refreshLoop: Job? = null
    private var tickLoop: Job? = null
    private var nextRefresh: Job? = null
    private var activityOpen = false
    private var monitoredSession: String? = null
    private var phone: String? = null
    private var snapshotPhone: String? = null
    private var phoneGeneration = 0L
    private var anchorElapsedMillis = 0L

    fun open() {
        activityOpen = true
        mutableState.value = mutableState.value.copy(panelVisible = true)
        updateLifecycle()
        refresh()
    }

    fun hide() {
        activityOpen = false
        mutableState.value = mutableState.value.copy(panelVisible = false)
        updateLifecycle()
    }

    internal fun monitor(sessionId: String) {
        monitoredSession = sessionId
        updateLifecycle()
        refresh()
    }

    internal fun stopMonitoring(sessionId: String) {
        if (monitoredSession != sessionId) return
        monitoredSession = null
        updateLifecycle()
    }

    internal fun dismissPanel() {
        activityOpen = false
        monitoredSession = null
        mutableState.value = mutableState.value.copy(panelVisible = false)
        updateLifecycle()
    }

    fun refresh() {
        if (refreshLoop != null) refreshSignals.trySend(Unit)
    }

    private fun updateLifecycle() {
        if (activityOpen && tickLoop == null) {
            tickLoop = scope.launch {
                while (isActive) {
                    updateTimerText()
                    delay(TIMER_TICK_MS)
                }
            }
        } else if (!activityOpen) {
            tickLoop?.cancel()
            tickLoop = null
        }
        val active = activityOpen || monitoredSession != null
        if (active && refreshLoop == null) {
            refreshLoop = scope.launch {
                for (signal in refreshSignals) requests.withLock { readPanel() }
            }
            transport.listen(
                onPhonesChanged = { phones ->
                    if (refreshLoop != null) {
                        selectPhone(phones)
                        refresh()
                    }
                },
                onPanelChanged = { nodeId -> if (nodeId == phone) refresh() },
            )
        } else if (!active && refreshLoop != null) {
            refreshLoop?.cancel()
            refreshLoop = null
            transport.stopListening()
            nextRefresh?.cancel()
            nextRefresh = null
            phone = null
            phoneGeneration++
            setDisconnected()
        }
    }

    private fun selectPhone(phones: Set<WearPhone>) {
        val availableNearby = phones.filter { it.nearby }
        val candidates = availableNearby.ifEmpty { phones.toList() }
        val selected = phone?.takeIf { id -> candidates.any { it.id == id } }
            ?: candidates.minByOrNull { it.id }?.id
        if (selected == phone) return
        phone = selected
        phoneGeneration++
        setDisconnected()
    }

    private suspend fun readPanel() {
        val discoveringFor = phoneGeneration
        val discovered = withTransportTimeout { transport.reachablePhones() }
        if (discoveringFor != phoneGeneration) return
        if (discovered == null) {
            reconnect()
            return
        }
        selectPhone(discovered)
        val node = phone
        if (node == null) {
            reconnect()
            return
        }
        val generation = phoneGeneration
        val sample = call(node, PanelProtocol.STATE_PATH)
        if (generation != phoneGeneration) return
        val snapshot = sample?.reply?.takeIf { it.result == ReplyResult.OK }?.snapshot
        if (sample == null || snapshot == null) {
            reconnect()
            return
        }
        acceptSnapshot(snapshot, sample.anchorElapsedMillis)
    }

    fun perform(control: PanelControl, displayedSnapshot: PanelSnapshot) {
        performRequest(displayedSnapshot.toRequest(control.action, control.argument))
    }

    internal fun confirmRest(request: ActionRequest) {
        if (request.action == PanelAction.REST_OK) performRequest(request)
    }

    private fun performRequest(request: ActionRequest) {
        val before = mutableState.value
        if (before.busy) return
        val node = phone
        if (node == null || !before.connected || before.snapshot?.allows(request) != true) {
            mutableState.value = before.copy(feedback = ACTION_UNCONFIRMED)
            refresh()
            return
        }
        val generation = phoneGeneration
        mutableState.value = before.copy(busy = true, feedback = null)
        scope.launch {
            try {
                requests.withLock {
                    val current = mutableState.value
                    if (generation != phoneGeneration || !current.connected || current.snapshot?.allows(request) != true) {
                        mutableState.value = current.copy(feedback = ACTION_UNCONFIRMED)
                        return@withLock
                    }
                    val sample = call(node, PanelProtocol.ACTION_PATH, PanelProtocol.encode(request))
                    val snapshot = sample?.reply?.snapshot
                    when {
                        generation != phoneGeneration -> {
                            mutableState.value = mutableState.value.copy(feedback = ACTION_UNCONFIRMED)
                        }
                        sample == null || snapshot == null -> {
                            setDisconnected(ACTION_UNCONFIRMED)
                            refresh()
                        }
                        sample.reply.result == ReplyResult.OK -> {
                            acceptSnapshot(snapshot, sample.anchorElapsedMillis)
                            if (request.action == PanelAction.EDIT_LAST) {
                                mutableState.value = mutableState.value.copy(feedback = "Abre la notificación en el teléfono")
                            }
                        }
                        else -> {
                            // The returned state may explain a superseded control. Refresh before accepting another tap.
                            setDisconnected(ACTION_UNCONFIRMED)
                            refresh()
                        }
                    }
                }
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    fun loadSummary(page: Int) {
        val current = mutableState.value
        val snapshot = current.snapshot ?: return
        val node = phone ?: return
        if (!current.connected || snapshot.mode != PanelMode.COMPLETED ||
            page !in 0 until snapshot.summaryPages || page in current.summary || current.summaryLoading
        ) return
        val generation = phoneGeneration
        mutableState.value = current.copy(summaryLoading = true, summaryFailed = false)
        scope.launch {
            try {
                requests.withLock {
                    if (generation != phoneGeneration || !mutableState.value.connected || !isCurrentSummary(snapshot)) return@withLock
                    val request = SummaryRequest(snapshot.sessionId, snapshot.revision, page)
                    val sample = call(node, PanelProtocol.SUMMARY_PATH, PanelProtocol.encode(request))
                    val reply = sample?.reply ?: run {
                        if (generation == phoneGeneration) {
                            setDisconnected()
                            refresh()
                        }
                        return@withLock
                    }
                    if (generation != phoneGeneration || !isCurrentSummary(snapshot)) return@withLock
                    val summaryPage = reply.summaryPage
                    if (reply.result == ReplyResult.OK && summaryPage != null) {
                        val latest = mutableState.value
                        mutableState.value = latest.copy(summary = latest.summary + (page to summaryPage))
                    } else {
                        mutableState.value = mutableState.value.copy(summaryFailed = true)
                        if (reply.result == ReplyResult.STALE) refresh()
                    }
                }
            } finally {
                mutableState.value = mutableState.value.copy(summaryLoading = false)
            }
        }
    }

    private fun isCurrentSummary(snapshot: PanelSnapshot): Boolean {
        val current = mutableState.value.snapshot ?: return false
        return current.mode == PanelMode.COMPLETED && current.sessionId == snapshot.sessionId &&
            current.revision == snapshot.revision
    }

    private fun acceptSnapshot(snapshot: PanelSnapshot, anchor: Long) {
        val previous = mutableState.value
        anchorElapsedMillis = anchor
        val sameSummary = snapshotPhone == phone && isCurrentSummary(snapshot)
        snapshotPhone = phone
        val timer = snapshot.timer
        val restDeadline = if (timer != null && timer.kind == TimerKind.REST && timer.running && timer.millis > 0) {
            anchor + timer.millis
        } else null
        mutableState.value = previous.copy(
            snapshot = snapshot,
            connected = true,
            feedback = previous.feedback?.takeUnless { it == PHONE_UNAVAILABLE },
            timerText = snapshot.timerText(elapsedMillis() - anchor),
            summary = if (sameSummary) previous.summary else emptyMap(),
            summaryFailed = sameSummary && previous.summaryFailed,
            snapshotSequence = previous.snapshotSequence + 1,
            restDeadlineElapsedMillis = restDeadline.takeIf { snapshot.restSegmentId != null },
        )
        when {
            restDeadline != null -> scheduleRefresh((restDeadline - elapsedMillis()).coerceAtLeast(TIMER_TICK_MS))
            snapshot.alert -> scheduleRefresh(ALERT_RECHECK_MS)
            else -> {
                nextRefresh?.cancel()
                nextRefresh = null
            }
        }
    }

    private fun updateTimerText() {
        val current = mutableState.value
        val text = current.snapshot?.timerText(elapsedMillis() - anchorElapsedMillis, current.connected)
        if (text != null && current.timerText != text) mutableState.value = current.copy(timerText = text)
    }

    private fun setDisconnected(feedback: String? = mutableState.value.feedback) {
        nextRefresh?.cancel()
        nextRefresh = null
        mutableState.value = mutableState.value.copy(
            connected = false,
            feedback = feedback,
            timerText = null,
            restDeadlineElapsedMillis = null,
        )
    }

    private fun reconnect() {
        val current = mutableState.value
        val feedback = if (current.snapshot == null && current.feedback == null) PHONE_UNAVAILABLE else current.feedback
        setDisconnected(feedback)
        scheduleRefresh(RECONNECT_MS)
    }

    private fun scheduleRefresh(delayMillis: Long) {
        nextRefresh?.cancel()
        nextRefresh = scope.launch {
            delay(delayMillis)
            refresh()
        }
    }

    private suspend fun call(node: String, path: String, body: ByteArray = ByteArray(0)): ReplySample? {
        val sent = elapsedMillis()
        val reply = withTransportTimeout {
            PanelProtocol.decode<PanelReply>(transport.request(node, path, body))
        } ?: return null
        // The phone samples during the RPC. Approximate that instant on the local clock for display only.
        return ReplySample(reply, sent + (elapsedMillis() - sent) / 2)
    }

    private data class ReplySample(val reply: PanelReply, val anchorElapsedMillis: Long)

    private companion object {
        const val RECONNECT_MS = 4_000L
        const val ALERT_RECHECK_MS = 12_000L
        const val TIMER_TICK_MS = 250L
        const val PHONE_UNAVAILABLE = "Conecta el teléfono vinculado con Auto-Gym actualizado."
        const val ACTION_UNCONFIRMED = "Acción sin confirmar. Comprueba el estado antes de reintentar."
    }
}

private fun PanelSnapshot.toRequest(action: PanelAction, argument: Int?) =
    ActionRequest(sessionId, index, revision, restSegmentId, action, argument)

private fun PanelSnapshot.allows(request: ActionRequest): Boolean =
    request == toRequest(request.action, request.argument) &&
        controls.any { it.action == request.action && it.argument == request.argument && it.enabled }

private fun PanelSnapshot.timerText(elapsedMillis: Long, connected: Boolean = true): String? {
    val timer = timer ?: return null
    if (!connected) return null
    val progressed = if (timer.running) elapsedMillis.coerceAtLeast(0) else 0
    return when (timer.kind) {
        TimerKind.REST -> panelCounterText(timer.millis - progressed, countdown = true)
        TimerKind.CARDIO -> panelCounterText((timer.millis - progressed).coerceAtLeast(0), countdown = true)
        TimerKind.WAIT -> panelCounterText(timer.millis + progressed)
    }
}

private suspend fun <T> withTransportTimeout(block: suspend () -> T): T? = try {
    withTimeout(5_000L) { block() }
} catch (_: TimeoutCancellationException) {
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
