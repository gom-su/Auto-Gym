package com.example.workoutpoc

import com.example.workoutpoc.data.WorkoutRepository
import com.example.workoutpoc.domain.ClockSnapshot
import com.example.workoutpoc.domain.SetType
import com.example.workoutpoc.domain.WorkoutEvent
import com.example.workoutpoc.domain.WorkoutReducer
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.domain.command

/**
 * Completes the current set. A planned work set with no RIR yet (prescription did not state one) must get a RIR first
 * (requirement 6), so the helper selects RIR 2 in that case only. Demo sets and sets that already have a RIR are untouched.
 */
/** Reducer-level counterpart of [completeSet]: selects RIR first when a planned work set has none. */
internal fun WorkoutReducer.reduceRirReady(state: WorkoutState, event: WorkoutEvent, now: ClockSnapshot, rir: Int = 2): WorkoutState {
    val needsRir = event == WorkoutEvent.CompleteSet && state.source != null && state.values.rir == null &&
        state.plan.getOrNull(state.index)?.setType == SetType.WORK
    val ready = if (needsRir) reduce(state, state.command(WorkoutEvent.SelectRir, rir), now, "session") else state
    return reduce(ready, ready.command(event), now, "session")
}

internal suspend fun WorkoutRepository.completeSet(rir: Int = 2) {
    val s = state.value
    val needsRir = s.source != null && s.values.rir == null && s.plan.getOrNull(s.index)?.setType == SetType.WORK
    if (needsRir) dispatch(s.command(WorkoutEvent.SelectRir, rir))
    dispatch(state.value.command(WorkoutEvent.CompleteSet))
}
