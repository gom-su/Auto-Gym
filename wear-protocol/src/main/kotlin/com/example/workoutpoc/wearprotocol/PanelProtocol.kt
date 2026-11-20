package com.example.workoutpoc.wearprotocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

object PanelProtocol {
    const val ROOT = "/autogym/panel/v1"
    const val STATE_PATH = "$ROOT/state"
    const val ACTION_PATH = "$ROOT/action"
    const val SUMMARY_PATH = "$ROOT/summary"
    const val CHANGED_PATH = "$ROOT/changed"
    const val PHONE_CAPABILITY = "auto_gym_phone_v1"
    const val WATCH_CAPABILITY = "auto_gym_watch_v1"

    @PublishedApi
    internal val json = Json { ignoreUnknownKeys = true }

    inline fun <reified T> encode(value: T): ByteArray = json.encodeToString(value).encodeToByteArray()
    inline fun <reified T> decode(bytes: ByteArray): T = json.decodeFromString(bytes.decodeToString())
}

@Serializable
data class PanelSnapshot(
    val sessionId: String = "",
    val index: Int = 0,
    val revision: Long = 0,
    val restSegmentId: String? = null,
    val mode: PanelMode = PanelMode.IDLE,
    val paused: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
    val notice: String? = null,
    val weight: String? = null,
    val reps: Int? = null,
    val rir: Int? = null,
    val completionHint: String? = null,
    val timer: PanelTimer? = null,
    val waitPending: Boolean = false,
    val alert: Boolean = false,
    val controls: List<PanelControl> = emptyList(),
    val summaryPages: Int = 0,
    val watch: WatchWorkout? = null,
)

@Serializable
data class WatchWorkout(
    val activeSet: WatchSet? = null,
    val lastSet: WatchSet? = null,
    val nextTitle: String? = null,
    val nextDetail: String? = null,
    val restAddSeconds: Int = 0,
)

@Serializable
data class WatchSet(
    val index: Int,
    val exercise: String,
    val number: Int,
    val total: Int,
    val typeLabel: String,
    val values: WatchSetValues,
    val loadUnit: WatchLoadUnit,
    val weightStepTenths: Int,
    val maxWeightTenths: Int,
    val maxReps: Int,
    val requiresRir: Boolean,
    val editable: Boolean = false,
)

@Serializable
enum class WatchLoadUnit { KG, LB, MACHINE_SETTING, BODYWEIGHT }

@Serializable
data class WatchSetValues(val weightTenths: Int, val reps: Int, val rir: Int? = null)

@Serializable
enum class PanelMode { IDLE, SET, REST, CARDIO, WAIT, COMPLETED }

@Serializable
data class PanelTimer(val millis: Long, val kind: TimerKind, val running: Boolean)

@Serializable
enum class TimerKind { REST, CARDIO, WAIT }

@Serializable
data class PanelControl(
    val action: PanelAction,
    val label: String,
    val argument: Int? = null,
    val enabled: Boolean = true,
)

@Serializable
enum class PanelAction {
    WEIGHT_DOWN, WEIGHT_UP, REPS_DOWN, REPS_UP, SELECT_RIR, COMPLETE_SET, POSTPONE,
    REST_ADD, REST_REDUCE, REST_SKIP, REST_OK, WAIT_START, WAIT_CANCEL, WAIT_END,
    PAUSE, RESUME, CARDIO_FINISH, EDIT_LAST,
    RECORD_WATCH_SET, CORRECT_WATCH_SET, EXTEND_WATCH_REST,
}

@Serializable
data class ActionRequest(
    val sessionId: String,
    val index: Int,
    val revision: Long,
    val restSegmentId: String?,
    val action: PanelAction,
    val argument: Int? = null,
    val setValues: WatchSetValues? = null,
)

fun PanelSnapshot.allowsWatchAction(request: ActionRequest): Boolean {
    val workout = watch ?: return false
    return when (request.action) {
        PanelAction.RECORD_WATCH_SET -> !paused && mode == PanelMode.SET &&
            request.argument == null && request.setValues?.fits(workout.activeSet) == true
        PanelAction.CORRECT_WATCH_SET -> workout.lastSet?.let { last ->
            last.editable && request.argument == last.index && request.setValues != last.values &&
                request.setValues?.fits(last) == true
        } == true
        PanelAction.EXTEND_WATCH_REST -> !paused && mode == PanelMode.REST && workout.restAddSeconds > 0 &&
            request.argument == null && request.setValues == null
        else -> false
    }
}

private fun WatchSetValues.fits(set: WatchSet?): Boolean =
    set != null && weightTenths in 0..set.maxWeightTenths && reps in 1..set.maxReps &&
        (if (set.requiresRir) rir in 0..4 else rir == null)

@Serializable
data class SummaryRequest(val sessionId: String, val revision: Long, val page: Int)

@Serializable
data class PanelReply(
    val result: ReplyResult,
    val snapshot: PanelSnapshot? = null,
    val summaryPage: String? = null,
)

@Serializable
enum class ReplyResult { OK, STALE, REJECTED, ERROR }

fun panelCounterText(millis: Long, countdown: Boolean = false): String {
    val seconds = if (countdown && millis > 0) (millis + 999) / 1000 else abs(millis) / 1000
    val sign = if (countdown && millis <= -1000) "−" else ""
    return "$sign${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}
