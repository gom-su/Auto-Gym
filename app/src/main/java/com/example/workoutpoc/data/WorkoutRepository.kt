package com.example.workoutpoc.data

import com.example.workoutpoc.domain.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

enum class LoadResult { LOADED, SESSION_IN_PROGRESS }

/** Serializes restore, timer ticks and UI commands under the same lock. */
class WorkoutRepository(
    private val storage: WorkoutStorage,
    private val clock: () -> ClockSnapshot,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val history: SessionHistory = MemorySessionHistory()
) {
    private val reducer = WorkoutReducer(DemoWorkout.sets)
    private val mutex = Mutex()
    private var loaded: Boolean = false
    private val mutableState = MutableStateFlow(WorkoutState())
    val state: StateFlow<WorkoutState> = mutableState.asStateFlow()
    private val mutableReady = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()
    private val mutableHistory = MutableStateFlow(WorkoutHistory())
    val archived: StateFlow<WorkoutHistory> = mutableHistory.asStateFlow()

    suspend fun restore(): Unit = mutex.withLock { loadLocked() }

    private suspend fun loadLocked() {
        if (loaded) return
        val saved: WorkoutState = storage.read()
        mutableHistory.value = history.read()
        val restored: WorkoutState = reducer.reduce(saved, saved.command(WorkoutEvent.RestFinished), clock(), idFactory())
        if (restored != saved) storage.write(restored)
        mutableState.value = restored
        loaded = true
        mutableReady.value = true
    }

    suspend fun dispatch(command: WorkoutCommand): Unit = mutex.withLock {
        loadLocked()
        val previous: WorkoutState = mutableState.value
        val next: WorkoutState = reducer.reduce(previous, command, clock(), idFactory())
        if (next != previous) {
            // Resetting discards the snapshot: archive confirmed sets first so none are lost.
            if (command.event == WorkoutEvent.ResetDemo) archiveLocked(previous)
            storage.write(next)
            mutableState.value = next
        }
    }

    /** Loads a planned day as the next session. Never replaces a session in progress. */
    suspend fun load(plan: List<PlannedSet>, source: SessionSource, previousWeekId: String? = null,
                     cardioPlan: List<PlannedCardio> = emptyList()): LoadResult = mutex.withLock {
        loadLocked()
        val previous = mutableState.value
        if (previous.phase.inProgress) return@withLock LoadResult.SESSION_IN_PROGRESS
        val references = previousWeekId?.let {
            previousWorkSets(mutableHistory.value.sessions + previous, source.programId, it, plan)
        }.orEmpty()
        val next = reducer.load(previous, plan, source, idFactory(), references, cardioPlan)
        WorkoutValidation.validate(next)
        archiveLocked(previous)
        storage.write(next)
        mutableState.value = next
        LoadResult.LOADED
    }

    /** Edits one confirmed set of the current planned session. Returns false for a stale snapshot. */
    suspend fun edit(sessionId: String, revision: Long, index: Int, status: SetStatus, values: SetValues, notes: String?,
                     recordedUnit: WeightUnit? = null): Boolean =
        mutex.withLock {
            loadLocked()
            val previous = mutableState.value
            val next = reducer.edit(previous, sessionId, revision, index, status, values, notes, clock(), recordedUnit)
            if (next == previous) return@withLock false
            WorkoutValidation.validate(next)
            storage.write(next)
            mutableState.value = next
            true
        }

    /** Adds cardio to the current planned session. Returns false for a stale snapshot. */
    suspend fun addCardio(sessionId: String, revision: Long, entry: CardioEntry): Boolean =
        mutate { reducer.addCardio(it, sessionId, revision, entry) }

    suspend fun removeCardio(sessionId: String, revision: Long, id: String): Boolean =
        mutate { reducer.removeCardio(it, sessionId, revision, id) }

    private suspend fun mutate(change: (WorkoutState) -> WorkoutState): Boolean = mutex.withLock {
        loadLocked()
        val previous = mutableState.value
        val next = change(previous)
        if (next == previous) return@withLock false
        WorkoutValidation.validate(next)
        storage.write(next)
        mutableState.value = next
        true
    }

    /** Holds off timer, notification and UI commands for the complete backup transaction.
     * Reads raw validated storage without reconciling a rest or writing anything during preflight. */
    suspend fun <T> withBackupAccess(block: suspend (BackupAccess) -> T): T = mutex.withLock {
        block(BackupAccess(storage.read(), history.read()))
    }

    inner class BackupAccess internal constructor(val workout: WorkoutState, val archive: WorkoutHistory) {
        suspend fun replace(state: WorkoutState, archive: WorkoutHistory) = replaceLocked(state, archive)
    }

    /** Only backup rollback may replace an in-progress session. */
    suspend fun replaceAll(state: WorkoutState, archive: WorkoutHistory, allowInProgress: Boolean = false): LoadResult =
        withBackupAccess { access ->
            if (!allowInProgress && access.workout.phase.inProgress)
                LoadResult.SESSION_IN_PROGRESS
            else { access.replace(state, archive); LoadResult.LOADED }
        }

    private suspend fun replaceLocked(state: WorkoutState, archive: WorkoutHistory) {
        WorkoutValidation.validate(state)
        WorkoutHistorySerializer.validate(archive)
        val previousArchive = history.read()
        // Elapsed time and boot count belong to the source device. Rebase runtime marks using wall time,
        // even when both devices happen to have the same boot count, then reconcile before either write.
        val now = clock()
        fun elapsedAt(wallMillis: Long): Long = Math.addExact(now.elapsedMillis, Math.subtractExact(wallMillis, now.wallMillis))
        val portable = state.copy(
            rest = state.rest?.let { it.copy(elapsedMillis = elapsedAt(it.wallMillis), bootCount = now.bootCount) },
            restSegments = state.restSegments.map { segment ->
                if (segment.id == state.openRestSegment?.id)
                    segment.copy(startedAtElapsedMillis = elapsedAt(segment.startedAtMillis), bootCount = now.bootCount)
                else segment
            },
            cardioRun = state.cardioRun?.let { run ->
                run.copy(started = run.started.copy(elapsedMillis = elapsedAt(run.started.wallMillis), bootCount = now.bootCount))
            },
            timing = state.timing?.let { timing ->
                timing.copy(open = timing.open?.let { segment ->
                    segment.copy(start = segment.start.copy(elapsedMillis = elapsedAt(segment.start.wallMillis), bootCount = now.bootCount))
                })
            },
            paused = state.paused?.let { it.copy(elapsedMillis = elapsedAt(it.wallMillis), bootCount = now.bootCount) })
        val restored = reducer.reduce(portable, portable.command(WorkoutEvent.RestFinished), now, idFactory())
        WorkoutValidation.validate(restored)
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            history.replace(archive)
            try { storage.write(restored) }
            catch (e: Exception) {
                try { history.replace(previousArchive) }
                catch (rollback: Exception) {
                    throw com.example.workoutpoc.backup.BackupException("No se pudo revertir el historial tras un fallo de escritura.", e, listOf(rollback))
                }
                throw e
            }
            mutableHistory.value = archive
            mutableState.value = restored
            loaded = true
            mutableReady.value = true
        }
    }

    private suspend fun archiveLocked(session: WorkoutState) {
        if (session.records.isEmpty() && session.cardio.isEmpty() && session.cardioRun == null &&
            session.timing?.open == null && session.timing?.segments.isNullOrEmpty()) return
        // A session reset mid-way is archived as ended early, exactly as recorded so far.
        val now = clock()
        // An open pause is closed at archive time so its duration is not lost.
        val closed = reducer.reduce(session, session.command(WorkoutEvent.FinishWorkout), now, session.sessionId)
        if (history.archive(closed))
            mutableHistory.value = history.read()
    }

    private fun WorkoutState.belongsTo(programId: String, weekId: String) =
        source?.programId == programId && source?.weekId == weekId

    /** Archived sessions and the current session that were recorded in one week of a program (contract §2). */
    suspend fun weekRecordCount(programId: String, weekId: String): Int = mutex.withLock {
        loadLocked()
        val archived = mutableHistory.value.sessions.count { it.belongsTo(programId, weekId) }
        archived + if (mutableState.value.belongsTo(programId, weekId)) 1 else 0
    }

    /**
     * Deletes every record of one week: its archived sessions and the current session when it belongs to that week.
     * Refused while that week's session is in progress. Returns how many sessions were removed.
     */
    suspend fun deleteWeekRecords(programId: String, weekId: String): Int = mutex.withLock {
        loadLocked()
        val current = mutableState.value
        val currentMatches = current.belongsTo(programId, weekId)
        if (currentMatches && (current.phase == Phase.ActiveSet || current.phase == Phase.Resting))
            throw IllegalStateException("Hay una sesión en curso de esa semana. Termínala antes de reemplazarla.")
        deleteRecordsLocked(currentMatches) { it.belongsTo(programId, weekId) }.size
    }

    /** Deletes a program's saved sessions and returns the count and session IDs. */
    suspend fun deleteProgramRecords(programId: String): Pair<Int, List<String>> = mutex.withLock {
        loadLocked()
        val current = mutableState.value
        val currentMatches = current.source?.programId == programId
        check(!currentMatches || !current.phase.inProgress) {
            "Hay una sesión en curso de ese programa. Termínala antes de borrarlo."
        }
        val removed = deleteRecordsLocked(currentMatches) { it.source?.programId == programId }
        removed.size to removed
    }

    private suspend fun deleteRecordsLocked(
        currentMatches: Boolean,
        matches: (WorkoutState) -> Boolean
    ): List<String> {
        val current = mutableState.value
        val archive = mutableHistory.value
        val (removed, kept) = archive.sessions.partition(matches)
        if (removed.isEmpty() && !currentMatches) return emptyList()
        val nextArchive = archive.copy(sessions = kept)
        val cleared = WorkoutState()
        withContext(NonCancellable) {
            if (removed.isNotEmpty()) history.replace(nextArchive)
            if (currentMatches) {
                try {
                    storage.write(cleared)
                } catch (failure: Exception) {
                    if (removed.isNotEmpty()) {
                        try {
                            history.replace(archive)
                        } catch (rollbackFailure: Exception) {
                            failure.addSuppressed(rollbackFailure)
                            try {
                                mutableHistory.value = history.read()
                            } catch (readFailure: Exception) {
                                failure.addSuppressed(readFailure)
                            }
                        }
                    }
                    throw failure
                }
                mutableState.value = cleared
            }
            mutableHistory.value = nextArchive
        }
        return removed.map { it.sessionId } + listOfNotNull(current.sessionId.takeIf { currentMatches })
    }

    suspend fun reconcileRest() {
        dispatch(state.value.command(WorkoutEvent.RestFinished))
    }
}

private fun previousWorkSets(sessions: List<WorkoutState>, programId: String, weekId: String,
                             plan: List<PlannedSet>): Map<String, PreviousWorkSet> = buildMap {
    val exerciseIds = plan.mapNotNull { it.exerciseId }.toSet()
    val previousSessions = sessions.filter {
        it.source?.programId == programId && it.source.weekId == weekId
    }.distinctBy { it.sessionId }.sortedBy { it.startedAtMillis ?: Long.MAX_VALUE }
    for (session in previousSessions) {
        val firstWorkSets = session.plan.withIndex()
            .filter { it.value.setType == SetType.WORK && it.value.exerciseId in exerciseIds }
            .distinctBy { it.value.exerciseId }
        for ((index, step) in firstWorkSets) {
            val exerciseId = step.exerciseId ?: continue
            val record = session.records.getOrNull(index)?.takeIf { it.status == SetStatus.COMPLETED } ?: continue
            putIfAbsent(exerciseId, PreviousWorkSet(record.values.weightTenths, record.recordedUnit ?: step.unit,
                step.basis, record.values.reps, record.values.rir))
        }
    }
}
