package com.example.workoutpoc.timer

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.example.workoutpoc.data.WorkoutRepository
import com.example.workoutpoc.domain.ClockSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

fun clockSnapshot(context: Context): ClockSnapshot = ClockSnapshot(
    SystemClock.elapsedRealtime(), System.currentTimeMillis(),
    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
)

/** Ticks trigger reconciliation only. The persisted deadline, not tick count, owns time. */
class RestTimer(private val repository: WorkoutRepository) {
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            repository.reconcileRest()
            delay(250)
        }
    }
}
