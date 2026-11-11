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
    val backups: com.example.workoutpoc.backup.BackupRepository by lazy {
        com.example.workoutpoc.backup.BackupRepository(repository, programs, outlines, exchanges, proposals,
            measurements, catalog, planningAnswers, weekAnnotations, migrations, settings)
    }
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
            }, sessions = { repository.restore(); repository.archived.value.sessions + repository.state.value },
            outlines = { outlines.restore(); outlines.outlines.value.active })
    }
    val outlines: com.example.workoutpoc.planning.PlanOutlineRepository by lazy {
        com.example.workoutpoc.planning.PlanOutlineRepository(DataStoreFactory.create(
            com.example.workoutpoc.planning.PlanOutlineStoreSerializer,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { dataStoreFile("plan-outlines-v1.json") })
    }
    val settings: com.example.workoutpoc.settings.AppSettingsRepository by lazy {
        com.example.workoutpoc.settings.AppSettingsRepository(DataStoreFactory.create(
            com.example.workoutpoc.settings.AppSettingsSerializer,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { dataStoreFile("app-settings-v1.json") })
    }
    val weekAnnotations: com.example.workoutpoc.planning.WeekAnnotationRepository by lazy {
        com.example.workoutpoc.planning.WeekAnnotationRepository(DataStoreFactory.create(
            com.example.workoutpoc.planning.WeekAnnotationsSerializer,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { dataStoreFile("week-annotations-v1.json") })
    }
    val measurements: com.example.workoutpoc.body.MeasurementRepository by lazy {
        com.example.workoutpoc.body.MeasurementRepository(DataStoreFactory.create(
            com.example.workoutpoc.body.MeasurementLogSerializer,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { dataStoreFile("measurements-v1.json") })
    }
    val planningAnswers: com.example.workoutpoc.questions.PlanningAnswerRepository by lazy {
        com.example.workoutpoc.questions.PlanningAnswerRepository(DataStoreFactory.create(
            com.example.workoutpoc.questions.PlanningAnswersSerializer,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { dataStoreFile("planning-answers-v1.json") })
    }
    val sync: com.example.workoutpoc.sync.SyncRepository by lazy {
        com.example.workoutpoc.sync.SyncRepository(DataStoreFactory.create(
            com.example.workoutpoc.sync.SyncStateSerializer,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { dataStoreFile("sync-v1.json") })
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
