package com.example.workoutpoc.data

import com.example.workoutpoc.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Serializes restore, timer ticks and UI commands under the same lock. */
class WorkoutRepository(
    private val storage: WorkoutStorage,
    private val clock: () -> ClockSnapshot,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private val reducer = WorkoutReducer(DemoWorkout.sets)
    private val mutex = Mutex()
    private var loaded: Boolean = false
    private val mutableState = MutableStateFlow(WorkoutState())
    val state: StateFlow<WorkoutState> = mutableState.asStateFlow()
    private val mutableReady = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    suspend fun restore(): Unit = mutex.withLock { loadLocked() }

    private suspend fun loadLocked() {
        if (loaded) return
        val saved: WorkoutState = storage.read()
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
            storage.write(next)
            mutableState.value = next
        }
    }

    suspend fun reconcileRest() {
        dispatch(state.value.command(WorkoutEvent.RestFinished))
    }
}
