package com.example.workoutpoc.overlay

import android.app.Service
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.workoutpoc.domain.Phase
import com.example.workoutpoc.notification.WorkoutNotificationManager
import com.example.workoutpoc.timer.RestTimer
import com.example.workoutpoc.workoutApp
import kotlinx.coroutines.*

/** Started only by a visible Activity. One service supports both notification and overlay modes. */
class WorkoutOverlayService : Service() {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: WorkoutOverlayController
    private lateinit var notifications: WorkoutNotificationManager
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { overlay.render(workoutApp.repository.state.value) }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = workoutApp.notifications
        overlay = WorkoutOverlayController(this) { command -> scope.launch {
            workoutApp.safely { workoutApp.repository.dispatch(command) }
        } }
        val initial = notifications.build(workoutApp.repository.state.value)
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(WorkoutNotificationManager.ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(WorkoutNotificationManager.ID, initial)
        } catch (e: RuntimeException) {
            Log.e("WorkoutService", "Servicio no permitido", e)
            workoutApp.error.value = "Android no permitió mantener el demo en segundo plano. Vuelve a la aplicación para reanudar."
            stopSelf(); return
        }
        ContextCompat.registerReceiver(this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            workoutApp.safely {
                workoutApp.repository.restore()
                workoutApp.repository.state.collect { state ->
                    notifications.update(state)
                    overlay.render(state)
                    if (state.phase == Phase.Completed || state.phase == Phase.Idle) {
                        overlay.close()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        // stopForeground removed the notification: repost even if the
                        // final snapshot has already been observed by the receiver.
                        if (state.phase == Phase.Completed) notifications.update(state, force = true)
                        stopSelf()
                    }
                }
            }
        }
        scope.launch { workoutApp.safely { RestTimer(workoutApp.repository).run() } }
        scope.launch {
            while (isActive) { overlay.render(workoutApp.repository.state.value); delay(250) }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::overlay.isInitialized) when (intent?.action) {
            SHOW -> overlay.show()
            HIDE -> overlay.close()
        }
        return START_NOT_STICKY
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); overlay.clamp() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        if (::overlay.isInitialized) overlay.close()
        try { unregisterReceiver(screenReceiver) } catch (e: IllegalArgumentException) { Log.d("WorkoutService", "Receiver no registrado", e) }
        super.onDestroy()
    }
    companion object { const val SHOW: String = "show_overlay"; const val HIDE: String = "hide_overlay" }
}
