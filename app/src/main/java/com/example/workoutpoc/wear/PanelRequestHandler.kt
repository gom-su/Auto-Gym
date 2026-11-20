package com.example.workoutpoc.wear

import com.example.workoutpoc.catalog.CatalogExercise
import com.example.workoutpoc.data.WorkoutRepository
import com.example.workoutpoc.domain.WorkoutCommand
import com.example.workoutpoc.domain.SetValues
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.history.review
import com.example.workoutpoc.presentation.overlayText
import com.example.workoutpoc.presentation.panelSnapshot
import com.example.workoutpoc.presentation.workoutEvent
import com.example.workoutpoc.wearprotocol.ActionRequest
import com.example.workoutpoc.wearprotocol.PanelAction
import com.example.workoutpoc.wearprotocol.PanelMode
import com.example.workoutpoc.wearprotocol.PanelProtocol
import com.example.workoutpoc.wearprotocol.PanelReply
import com.example.workoutpoc.wearprotocol.PanelSnapshot
import com.example.workoutpoc.wearprotocol.ReplyResult
import com.example.workoutpoc.wearprotocol.SummaryRequest
import com.example.workoutpoc.wearprotocol.WatchSetValues
import com.example.workoutpoc.wearprotocol.allowsWatchAction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PanelRequestHandler(
    private val repository: WorkoutRepository,
    private val catalog: () -> List<CatalogExercise>,
    private val requestEdit: (sessionId: String, recordIndex: Int) -> Boolean,
) {
    private val requests = Mutex()
    private var reviewedState: WorkoutState? = null
    private var reviewPages: List<String> = emptyList()

    suspend fun reply(path: String, payload: ByteArray): PanelReply = requests.withLock {
        when (path) {
            PanelProtocol.STATE_PATH -> if (payload.isEmpty()) readState() else PanelReply(ReplyResult.REJECTED)
            PanelProtocol.ACTION_PATH -> action(PanelProtocol.decode<ActionRequest>(payload))
            PanelProtocol.SUMMARY_PATH -> summary(PanelProtocol.decode<SummaryRequest>(payload))
            else -> PanelReply(ReplyResult.REJECTED)
        }
    }

    private suspend fun readState(result: ReplyResult = ReplyResult.OK): PanelReply {
        val (state, now) = repository.readLiveState()
        val panel = state.panelSnapshot(now, catalog())
        val pages = pagesFor(state.takeIf { panel.mode == PanelMode.COMPLETED })
        return PanelReply(result, panel.copy(summaryPages = pages.size))
    }

    private suspend fun action(request: ActionRequest): PanelReply {
        if (request.action == PanelAction.CORRECT_WATCH_SET) return correctLast(request)
        if (request.action == PanelAction.EDIT_LAST) {
            val (state, now) = repository.readLiveState()
            val panel = state.panelSnapshot(now)
            if (!request.matches(panel)) return readState(ReplyResult.STALE)
            val record = state.records.lastOrNull()
            val sent = record != null && request.allowedOn(panel) && requestEdit(state.sessionId, record.index)
            return readState(if (sent) ReplyResult.OK else ReplyResult.REJECTED)
        }
        val event = request.action.workoutEvent() ?: return PanelReply(ReplyResult.REJECTED)
        val command = WorkoutCommand(
            event, request.sessionId, request.index, request.revision, request.argument, request.restSegmentId,
            setValues = request.setValues?.takeIf { request.action == PanelAction.RECORD_WATCH_SET }?.domainValues(),
            restAdjustmentSeconds = 30.takeIf { request.action == PanelAction.EXTEND_WATCH_REST },
        )
        var result = ReplyResult.REJECTED
        val saved = repository.dispatchPanelAction(command) { state, now ->
            val panel = state.panelSnapshot(now)
            if (!request.matches(panel)) result = ReplyResult.STALE
            request.matches(panel) && request.allowedOn(panel)
        }
        return readState(if (saved) ReplyResult.OK else result)
    }

    private suspend fun correctLast(request: ActionRequest): PanelReply {
        val (state, now) = repository.readLiveState()
        val panel = state.panelSnapshot(now)
        if (!request.matches(panel)) return readState(ReplyResult.STALE)
        if (!panel.allowsWatchAction(request)) return readState(ReplyResult.REJECTED)
        val record = state.records[checkNotNull(request.argument)]
        val saved = repository.edit(
            state.sessionId, state.revision, record.index, record.status,
            checkNotNull(request.setValues).domainValues(), record.notes, record.recordedUnit,
        )
        return readState(if (saved) ReplyResult.OK else ReplyResult.STALE)
    }

    private suspend fun summary(request: SummaryRequest): PanelReply {
        val reply = readState()
        val panel = requireNotNull(reply.snapshot)
        if (panel.mode != PanelMode.COMPLETED || panel.sessionId != request.sessionId ||
            panel.revision != request.revision) return reply.copy(result = ReplyResult.STALE)
        val text = reviewPages.getOrNull(request.page) ?: return PanelReply(ReplyResult.REJECTED)
        return PanelReply(ReplyResult.OK, summaryPage = text)
    }

    private fun pagesFor(state: WorkoutState?): List<String> {
        if (reviewedState != state) {
            reviewPages = state?.review()?.overlayText()?.let(::splitPanelSummary).orEmpty()
            reviewedState = state
        }
        return reviewPages
    }
}

private fun ActionRequest.matches(snapshot: PanelSnapshot): Boolean =
    sessionId == snapshot.sessionId && revision == snapshot.revision && index == snapshot.index &&
        restSegmentId == snapshot.restSegmentId

private fun ActionRequest.allowedOn(snapshot: PanelSnapshot): Boolean =
    snapshot.allowsWatchAction(this) ||
        (setValues == null && snapshot.controls.any { it.action == action && it.argument == argument && it.enabled })

private fun WatchSetValues.domainValues() = SetValues(weightTenths, reps, rir)

/** Keep each JSON reply well below the message limit, including escaped characters. */
internal fun splitPanelSummary(text: String): List<String> = buildList {
    var start = 0
    while (start < text.length) {
        val limit = (start + 8_000).coerceAtMost(text.length)
        val newline = if (limit < text.length) text.lastIndexOf('\n', limit - 1) + 1 else limit
        val safeLimit = if (limit < text.length && text[limit].isLowSurrogate()) limit - 1 else limit
        val end = if (newline > start) newline else safeLimit
        add(text.substring(start, end))
        start = end
    }
}
