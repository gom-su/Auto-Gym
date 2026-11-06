package com.example.workoutpoc

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.example.workoutpoc.data.*
import com.example.workoutpoc.planning.ProgramRepository
import com.example.workoutpoc.planning.ProgramLibrarySerializer
import com.example.workoutpoc.timer.clockSnapshot
import com.example.workoutpoc.notification.WorkoutNotificationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class WorkoutApplication : Application() {
    val migrations: com.example.workoutpoc.migration.MigrationRepository by lazy {
        com.example.workoutpoc.migration.MigrationRepository(DataStoreFactory.create(
            com.example.workoutpoc.migration.MigrationArchiveSerializer,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { dataStoreFile("migrations-v1.json") })
    }
    val catalog: com.example.workoutpoc.catalog.CatalogRepository by lazy {
        com.example.workoutpoc.catalog.CatalogRepository(DataStoreFactory.create(
            com.example.workoutpoc.catalog.ExerciseCatalogSerializer,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { dataStoreFile("exercises-v1.json") })
    }
    val proposals: com.example.workoutpoc.proposal.ProposalRepository by lazy {
        com.example.workoutpoc.proposal.ProposalRepository(DataStoreFactory.create(
            com.example.workoutpoc.proposal.ProposalStateSerializer,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { dataStoreFile("proposals-v1.json") },
            programs, exchanges, {
                // Exercise IDs known from saved programs, the catalog and recorded sessions.
                programs.restore(); catalog.restore(); repository.restore()
                val sessions = repository.archived.value.sessions + repository.state.value
                (programs.library.value.programs.flatMap { p -> p.blocks.flatMap { b -> b.weeks.flatMap { w -> w.days.flatMap { d -> d.exercises.map { it.exerciseId } } } } } +
                    catalog.catalog.value.exercises.map { it.id } + sessions.flatMap { s -> s.plan.mapNotNull { it.exerciseId } }).toSet()
            })
    }
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val error: MutableStateFlow<String?> = MutableStateFlow(null)
    val programs: ProgramRepository by lazy {
        ProgramRepository(DataStoreFactory.create(ProgramLibrarySerializer,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { dataStoreFile("programs-v1.json") })
    }
    val exchanges: com.example.workoutpoc.exchange.ExchangeRepository by lazy {
        com.example.workoutpoc.exchange.ExchangeRepository(DataStoreFactory.create(
            com.example.workoutpoc.exchange.ExchangeArchiveSerializer,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { dataStoreFile("exchanges-v1.json") })
    }
    /** Shared publisher deduplicates receiver and service observations of the same snapshot. */
    val notifications: WorkoutNotificationManager by lazy { WorkoutNotificationManager(this) }
    val repository: WorkoutRepository by lazy {
        val store = DataStoreFactory.create(WorkoutSerializer, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            dataStoreFile("workout.json")
        }
        val historyStore = DataStoreFactory.create(WorkoutHistorySerializer, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            dataStoreFile("history-v1.json")
        }
        WorkoutRepository(DataStoreWorkoutStorage(store), { clockSnapshot(this) },
            history = DataStoreSessionHistory(historyStore))
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
