package com.example.workoutpoc.wearapp

import android.app.Application
import android.os.SystemClock
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
        )
}
