package com.example.workoutpoc.timer

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.workoutpoc.domain.Phase
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.domain.openRestSegment
import com.example.workoutpoc.domain.remainingMillis

internal class RestHaptics(private val context: Context) {
    @Suppress("DEPRECATION")
    private val vibrator = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else context.getSystemService(Vibrator::class.java)
    private var activeRest: Pair<String, String>? = null

    @Suppress("DEPRECATION")
    fun update(state: WorkoutState) {
        val expired = state.phase == Phase.Resting && state.paused == null &&
            state.rest?.remainingMillis(clockSnapshot(context)) == 0L
        val next = state.openRestSegment?.takeIf { expired }?.let { state.sessionId to it.id }
        if (next == activeRest) return
        stop()
        if (next == null || vibrator?.hasVibrator() != true) return
        activeRest = next
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 220, 1800), 0)
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
        } else {
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        }
    }

    fun stop() {
        if (activeRest != null) vibrator?.cancel()
        activeRest = null
    }
}
