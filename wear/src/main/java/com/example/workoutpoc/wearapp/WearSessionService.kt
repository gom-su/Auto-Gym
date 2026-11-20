package com.example.workoutpoc.wearapp

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.example.workoutpoc.wearprotocol.ActionRequest
import com.example.workoutpoc.wearprotocol.PanelAction
import com.example.workoutpoc.wearprotocol.PanelMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Keeps a user-opened session available from the watch face. */
class WearSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connection get() = (application as WearApplication).connection
    private val alarms by lazy { getSystemService(AlarmManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val vibrator by lazy { getSystemService(VibratorManager::class.java).defaultVibrator }
    private var sessionId: String? = null
    private var observation: Job? = null
    private var disconnectTimeout: Job? = null
    private var restNotice: RestNotice? = null
    private var vibratingRest: Pair<String, String>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var restDeadline: Long? = null
    private var pendingRestWakeup: AlarmManager.OnAlarmListener? = null
    private var lastTitle: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CONFIRM) {
            if (sessionId == null) stopSelf()
            else intent.confirmRequest()?.let(connection::confirmRest)
            return START_NOT_STICKY
        }
        val requested = intent?.getStringExtra(EXTRA_SESSION)
        if (intent?.action != ACTION_MONITOR || requested.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (sessionId == requested) return START_NOT_STICKY
        removeObservation()
        sessionId = requested
        val initialTitle = getString(R.string.wear_monitor_syncing)
        createNotificationChannels()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, monitorNotification(initialTitle), type)
        lastTitle = initialTitle
        val lastSample = connection.state.value.snapshotSequence
        connection.monitor(requested)
        observation = scope.launch {
            connection.state.collect { state ->
                if (!state.connected || state.snapshotSequence > lastSample) update(state, requested)
            }
        }
        return START_NOT_STICKY
    }

    private fun update(state: WearPanelState, monitoredSession: String) {
        val snapshot = state.snapshot
        if (state.connected && (snapshot?.sessionId != monitoredSession ||
                snapshot.mode == PanelMode.IDLE || snapshot.mode == PanelMode.COMPLETED)
        ) {
            removeObservation()
            stopSelf()
            return
        }
        if (state.connected) {
            disconnectTimeout?.cancel()
            disconnectTimeout = null
        } else if (disconnectTimeout == null) {
            disconnectTimeout = scope.launch {
                delay(DISCONNECTED_GRACE_MS)
                if (!connection.state.value.connected) stopSelf()
            }
        }
        updateRestWakeup(state.restDeadlineElapsedMillis)
        val rest = snapshot?.restSegmentId
        val alert = if (!state.advancingRest && state.connected && snapshot?.sessionId == monitoredSession && snapshot.alert && rest != null &&
            snapshot.controls.any { it.action == PanelAction.REST_OK && it.enabled }
        ) {
            RestNotice(snapshot.notice,
                ActionRequest(snapshot.sessionId, snapshot.index, snapshot.revision, rest, PanelAction.REST_OK))
        } else null
        val allowedAlert = alert?.takeIf { notificationsAllowed(REST_CHANNEL_ID) }
        updateRestNotice(allowedAlert)
        updateVibration(allowedAlert?.confirm?.takeIf {
            notifications.getNotificationChannel(REST_CHANNEL_ID)?.shouldVibrate() == true
        })

        val title = if (!state.connected || snapshot == null) {
            getString(R.string.wear_monitor_disconnected)
        } else {
            snapshot.title
        }
        if (title != lastTitle && notificationsAllowed(CHANNEL_ID)) {
            notifications.notify(NOTIFICATION_ID, monitorNotification(title))
            lastTitle = title
        }
    }

    private fun notificationsAllowed(channelId: String): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun createNotificationChannels() {
        val monitor = NotificationChannel(CHANNEL_ID, getString(R.string.wear_monitor_channel),
            NotificationManager.IMPORTANCE_LOW)
        val rest = NotificationChannel(REST_CHANNEL_ID, getString(R.string.wear_rest_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            vibrationPattern = longArrayOf(0, 220, 1_800)
            enableVibration(true)
        }
        notifications.createNotificationChannels(listOf(monitor, rest))
    }

    private fun openPanelIntent(): PendingIntent =
        PendingIntent.getActivity(this, 0,
            Intent(this, WearActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)

    private fun monitorNotification(title: String): Notification {
        val openPanel = openPanelIntent()
        val builder = baseNotification(CHANNEL_ID, title, openPanel)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSilent(true)
        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_workout_notification)
            .setTouchIntent(openPanel)
            .setStatus(Status.forPart(Status.TextPart(title)))
            .build()
            .apply(this)
        return builder.build()
    }

    private fun updateRestNotice(notice: RestNotice?) {
        val previous = restNotice
        if (notice == previous) return
        restNotice = notice
        if (notice == null) {
            notifications.cancel(REST_NOTIFICATION_ID)
            return
        }
        val title = getString(R.string.wear_monitor_ready)
        val notification = baseNotification(REST_CHANNEL_ID, title, openPanelIntent())
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSilent(true)
            .addAction(R.drawable.ic_workout_notification, getString(R.string.wear_monitor_ok),
                confirmationIntent(notice.confirm))
            .apply {
                notice.details?.let { setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
            }
            .build()
        notifications.notify(REST_NOTIFICATION_ID, notification)
    }

    private fun updateVibration(confirm: ActionRequest?) {
        val target = confirm?.restSegmentId?.let { confirm.sessionId to it }
        if (target == vibratingRest) return
        if (vibratingRest != null) vibrator.cancel()
        vibratingRest = target
        if (target == null || !vibrator.hasVibrator()) return
        // A user-started countdown expired. Use the watch's timer alarm vibration settings.
        val pulse = VibrationEffect.createWaveform(longArrayOf(0, 220, 1_800), 0)
        vibrator.vibrate(pulse, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
    }

    private fun baseNotification(channel: String, title: String, openPanel: PendingIntent) =
        NotificationCompat.Builder(this, channel)
            .setContentTitle(getString(R.string.wear_app_name))
            .setContentText(title)
            .setContentIntent(openPanel)
            .setSmallIcon(R.drawable.ic_workout_notification)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setShowWhen(false)

    private fun confirmationIntent(request: ActionRequest): PendingIntent {
        val identity = Uri.Builder().scheme("autogym").authority("wear-confirm")
            .appendPath(request.sessionId)
            .appendPath(request.index.toString())
            .appendPath(request.revision.toString())
            .appendPath(checkNotNull(request.restSegmentId))
            .build()
        val intent = Intent(this, WearSessionService::class.java).setAction(ACTION_CONFIRM).setData(identity)
        return PendingIntent.getService(this, 1, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun Intent.confirmRequest(): ActionRequest? {
        val parts = data?.pathSegments ?: return null
        if (parts.size != 4) return null
        val index = parts[1].toIntOrNull() ?: return null
        val revision = parts[2].toLongOrNull() ?: return null
        return ActionRequest(parts[0], index, revision, parts[3], PanelAction.REST_OK)
    }

    private fun updateRestWakeup(deadline: Long?) {
        val previous = restDeadline
        if (previous != null && deadline != null && (wakeLock?.isHeld == true || pendingRestWakeup != null) &&
            abs(deadline - previous) < DEADLINE_JITTER_MS
        ) return
        val pending = pendingRestWakeup
        pendingRestWakeup = null
        pending?.let(alarms::cancel)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        restDeadline = deadline
        if (deadline == null) return

        val exact = Build.VERSION.SDK_INT >= 34
        val now = SystemClock.elapsedRealtime()
        val wakeTime = restWakeTime(deadline, exact)
        if (wakeTime <= now) {
            holdForRestExpiry(deadline)
            if (deadline <= now) connection.refresh()
            return
        }

        val listener = object : AlarmManager.OnAlarmListener {
            override fun onAlarm() {
                if (pendingRestWakeup !== this) return
                pendingRestWakeup = null
                holdForRestExpiry(deadline)
                // Re-arm the in-process countdown after sleeping, which pauses uptime.
                connection.refresh()
            }
        }
        pendingRestWakeup = listener
        if (Build.VERSION.SDK_INT >= 34) {
            // This listener is scoped to the active foreground service.
            alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, wakeTime, REST_WAKEUP_TAG, listener, null)
        } else {
            // Older Android versions restrict exact listener alarms too.
            alarms.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, wakeTime,
                LEGACY_ALARM_WINDOW_MS, REST_WAKEUP_TAG, listener, null)
        }
    }

    private fun holdForRestExpiry(deadline: Long) {
        val remaining = deadline - SystemClock.elapsedRealtime()
        // Give the expiry refresh time to reach the phone while the watch display sleeps.
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:wear-rest").apply {
            setReferenceCounted(false)
            acquire(remaining.coerceAtLeast(0L) + EXPIRY_RPC_GRACE_MS)
        }
    }

    private fun removeObservation() {
        observation?.cancel()
        observation = null
        disconnectTimeout?.cancel()
        disconnectTimeout = null
        notifications.cancel(REST_NOTIFICATION_ID)
        restNotice = null
        updateVibration(null)
        updateRestWakeup(null)
        sessionId?.let(connection::stopMonitoring)
        sessionId = null
    }

    override fun onDestroy() {
        removeObservation()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private data class RestNotice(val details: String?, val confirm: ActionRequest)

    companion object {
        private const val ACTION_MONITOR = "com.example.workoutpoc.wear.MONITOR"
        private const val ACTION_CONFIRM = "com.example.workoutpoc.wear.CONFIRM_REST"
        private const val EXTRA_SESSION = "session"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "wear_workout_monitor"
        private const val REST_NOTIFICATION_ID = 3
        internal const val REST_CHANNEL_ID = "wear_rest_ready"
        private const val DISCONNECTED_GRACE_MS = 120_000L
        private const val EXPIRY_RPC_GRACE_MS = 12_000L
        private const val DEADLINE_JITTER_MS = 500L
        private const val REST_WAKEUP_TAG = "AutoGymWearRest"

        fun start(context: Context, sessionId: String) {
            val intent = Intent(context, WearSessionService::class.java)
                .setAction(ACTION_MONITOR).putExtra(EXTRA_SESSION, sessionId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun close(context: Context) {
            (context.applicationContext as WearApplication).connection.dismissPanel()
            context.stopService(Intent(context, WearSessionService::class.java))
        }
    }
}

private const val REST_WAKE_WINDOW_MS = 120_000L
internal const val LEGACY_ALARM_WINDOW_MS = 600_000L

/** Arm close to expiry. On API 33 the wake-up has a ten-minute delivery window. */
internal fun restWakeTime(deadline: Long, exact: Boolean): Long =
    deadline - REST_WAKE_WINDOW_MS - if (exact) 0L else LEGACY_ALARM_WINDOW_MS
