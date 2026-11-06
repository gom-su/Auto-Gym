package com.example.workoutpoc.data

import com.example.workoutpoc.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    suspend fun load(plan: List<PlannedSet>, source: SessionSource): LoadResult = mutex.withLock {
        loadLocked()
        val previous = mutableState.value
        if (previous.phase == Phase.ActiveSet || previous.phase == Phase.Resting) return@withLock LoadResult.SESSION_IN_PROGRESS
        val next = reducer.load(previous, plan, source, idFactory())
        WorkoutValidation.validate(next)
        archiveLocked(previous)
        storage.write(next)
        mutableState.value = next
        LoadResult.LOADED
    }

    /** Edits one confirmed set of the current planned session. Returns false for a stale snapshot. */
    suspend fun edit(sessionId: String, revision: Long, index: Int, status: SetStatus, values: SetValues, notes: String?): Boolean =
        mutex.withLock {
            loadLocked()
            val previous = mutableState.value
            val next = reducer.edit(previous, sessionId, revision, index, status, values, notes, clock())
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

    private suspend fun archiveLocked(session: WorkoutState) {
        if (session.records.isEmpty()) return
        // A session reset mid-way is archived as ended early, exactly as recorded so far.
        val now = clock()
        // An open pause is closed at archive time so its duration is not lost.
        if (history.archive(session.copy(phase = Phase.Completed, rest = null, paused = null,
                pausedMillis = session.pausedMillis + (session.paused?.elapsedSince(now) ?: 0),
                endedEarly = session.endedEarly || session.phase != Phase.Completed,
                endedAtMillis = session.endedAtMillis ?: now.wallMillis)))
            mutableHistory.value = history.read()
    }

    suspend fun reconcileRest() {
        dispatch(state.value.command(WorkoutEvent.RestFinished))
    }
}
