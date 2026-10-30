package com.example.workoutpoc

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.example.workoutpoc.data.*
import com.example.workoutpoc.timer.clockSnapshot
import com.example.workoutpoc.notification.WorkoutNotificationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class WorkoutApplication : Application() {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val error: MutableStateFlow<String?> = MutableStateFlow(null)
    /** Shared publisher deduplicates receiver and service observations of the same snapshot. */
    val notifications: WorkoutNotificationManager by lazy { WorkoutNotificationManager(this) }
    val repository: WorkoutRepository by lazy {
        val store = DataStoreFactory.create(WorkoutSerializer, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            dataStoreFile("workout.json")
        }
        WorkoutRepository(DataStoreWorkoutStorage(store), { clockSnapshot(this) })
    }
    override fun onCreate() {
        super.onCreate()
        scope.launch { safely { repository.restore() } }
    }
    suspend fun safely(block: suspend () -> Unit) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.e("WorkoutPoC", "Operación recuperable fallida", e)
            error.value = "No se pudo guardar o recuperar la sesión. Reintenta desde la aplicación."
        }
    }
}

val Context.workoutApp: WorkoutApplication get() = applicationContext as WorkoutApplication
