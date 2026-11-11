package com.example.workoutpoc.backup

import com.example.workoutpoc.body.MeasurementRepository
import com.example.workoutpoc.catalog.CatalogRepository
import com.example.workoutpoc.data.WorkoutRepository
import com.example.workoutpoc.exchange.ExchangeRepository
import com.example.workoutpoc.migration.MigrationRepository
import com.example.workoutpoc.planning.*
import com.example.workoutpoc.proposal.ProposalRepository
import com.example.workoutpoc.questions.PlanningAnswerRepository
import com.example.workoutpoc.settings.AppSettingsRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** A binding to a repository, also usable with fault-injecting stores in persistence tests. */
internal class BackupStore(val mutex: Mutex, val read: suspend (BackupData) -> BackupData, val replace: suspend (BackupData) -> Unit)

/** Validates everything before writing; failed restores undo attempted writes in reverse order. */
class BackupRepository internal constructor(private val workout: WorkoutRepository, private val stores: List<BackupStore>) {
    private val mutex = Mutex()

    constructor(workout: WorkoutRepository, programs: ProgramRepository, outlines: PlanOutlineRepository,
                exchanges: ExchangeRepository, proposals: ProposalRepository, measurements: MeasurementRepository,
                catalog: CatalogRepository, answers: PlanningAnswerRepository, annotations: WeekAnnotationRepository,
                migrations: MigrationRepository, settings: AppSettingsRepository) : this(workout, listOf(
        // Proposal approval calls other repositories while holding its own mutex: acquire it first.
        proposals.backupStore, programs.backupStore, outlines.backupStore, exchanges.backupStore,
        measurements.backupStore, catalog.backupStore, answers.backupStore, annotations.backupStore,
        migrations.backupStore, settings.backupStore
    ))

    private suspend fun <T> withStoreLocks(index: Int = 0, block: suspend () -> T): T =
        if (index == stores.size) block() else stores[index].mutex.withLock { withStoreLocks(index + 1, block) }

    private suspend fun read(access: WorkoutRepository.BackupAccess): BackupData {
        var data = BackupData(workout = access.workout, history = access.archive)
        for (store in stores) data = store.read(data)
        return data
    }

    suspend fun snapshot(): BackupData = mutex.withLock { withStoreLocks { workout.withBackupAccess { read(it) } } }

    /** [reviewedCurrent] binds confirmation to the device data shown in the review. */
    suspend fun restore(data: BackupData, reviewedCurrent: BackupData) = mutex.withLock {
        // Also validate callers that supplied a typed snapshot rather than decoding a file.
        BackupCodec.encode(data, Instant.EPOCH, BackupApp("validation", 1))
        withStoreLocks { workout.withBackupAccess { access ->
            val before = read(access) // corruption aborts here, with zero disk writes
            if (before.sessionInProgress) throw BackupException("Hay una sesión en curso. Termínala o ciérrala antes de restaurar. No se reemplazó nada.")
            if (before != reviewedCurrent) throw BackupException("Los datos del dispositivo cambiaron. Vuelve a elegir el respaldo y revisa la comparación. No se reemplazó nada.")
            // Once writes start, lifecycle cancellation cannot abandon a half-restored transaction.
            withContext(NonCancellable) {
                val attempted = mutableListOf<BackupStore>()
                try {
                    for (store in stores) { attempted += store; store.replace(data) }
                    // Last, while still holding the workout lock: notification commands cannot start a session between checks.
                    access.replace(data.workout, data.history)
                } catch (failure: Exception) {
                    val rollbackErrors = mutableListOf<Exception>()
                    for (store in attempted.asReversed()) {
                        try { store.replace(before) } catch (e: Exception) { rollbackErrors += e }
                    }
                    // replaceLocked itself rolls back history if the session write fails.
                    if (failure is BackupException) rollbackErrors += failure.rollbackFailures
                    if (rollbackErrors.isNotEmpty()) {
                        val error = BackupException("No se pudo completar la restauración ni revertir todos los almacenes. Conserva el archivo de respaldo y reintenta antes de registrar datos nuevos.", failure, rollbackErrors.toList())
                        rollbackErrors.forEach(error::addSuppressed)
                        throw error
                    }
                    throw BackupException("No se pudo restaurar el respaldo. Se revirtieron los cambios y tus datos anteriores se conservan.", failure)
                }
            }
        } }
    }
}
