package com.example.workoutpoc.wearapp

import android.app.Application
import android.app.NotificationManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

open class WearApplication : Application() {
    val connection: WearPanelConnection by lazy { createConnection() }

    internal open fun createConnection(): WearPanelConnection =
        WearPanelConnection(
            GoogleWearPanelTransport(this),
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            SystemClock::elapsedRealtime,
            ::playWorkoutHaptic,
        )

    private fun playWorkoutHaptic(haptic: WorkoutHaptic) {
        val vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
        if (!vibrator.hasVibrator()) return
        when (haptic) {
            WorkoutHaptic.SET_RECORDED -> vibrator.vibrate(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH),
            )
            WorkoutHaptic.REST_FINISHED -> {
                val channel = getSystemService(NotificationManager::class.java)
                    .getNotificationChannel(WearSessionService.REST_CHANNEL_ID)
                if (channel?.shouldVibrate() == false || channel?.importance == NotificationManager.IMPORTANCE_NONE) return
                vibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 160, 110, 220), -1),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
                )
            }
        }
    }
}
