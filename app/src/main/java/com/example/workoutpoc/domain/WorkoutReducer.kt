package com.example.workoutpoc.domain

/** Pure, deterministic transitions. Storage and Android APIs remain outside this class. */
class WorkoutReducer(private val plan: List<PlannedSet>) {
    init { require(plan.isNotEmpty()) }

    fun reduce(s: WorkoutState, c: WorkoutCommand, now: ClockSnapshot, newSessionId: String): WorkoutState {
        if (c.sessionId != s.sessionId || c.index != s.index || c.revision != s.revision) return s
        val changed: WorkoutState = when (c.event) {
            WorkoutEvent.StartWorkout -> if (s.phase == Phase.Idle) start(newSessionId) else s
            WorkoutEvent.ResetDemo -> WorkoutState(sessionId = newSessionId)
            WorkoutEvent.UndoLastSet -> undo(s)
            WorkoutEvent.FinishWorkout -> if (s.phase == Phase.ActiveSet || s.phase == Phase.Resting)
                s.copy(phase = Phase.Completed, rest = null, endedEarly = true) else s
            WorkoutEvent.AddRestTime -> if (s.phase == Phase.Resting && s.rest != null)
                s.copy(rest = s.rest.copy(elapsedMillis = s.rest.elapsedMillis + 10_000, wallMillis = s.rest.wallMillis + 10_000)) else s
            WorkoutEvent.SkipRest -> if (s.phase == Phase.Resting) advance(s) else s
            WorkoutEvent.RestFinished -> if (s.phase == Phase.Resting && s.rest?.remainingMillis(now) == 0L) advance(s) else s
            else -> if (s.phase == Phase.ActiveSet) active(s, c, now) else s
        }
        return if (changed == s) s else changed.copy(revision = s.revision + 1)
    }

    private fun start(id: String): WorkoutState = WorkoutState(sessionId = id, phase = Phase.ActiveSet, values = values(0))
    private fun values(index: Int): SetValues = plan[index].let { SetValues(it.weightTenths, it.reps, it.rir) }

    private fun active(s: WorkoutState, c: WorkoutCommand, now: ClockSnapshot): WorkoutState = when (c.event) {
        WorkoutEvent.IncrementWeight -> s.copy(values = s.values.copy(weightTenths = (s.values.weightTenths + 25).coerceAtMost(3000)))
        WorkoutEvent.DecrementWeight -> s.copy(values = s.values.copy(weightTenths = (s.values.weightTenths - 25).coerceAtLeast(0)))
        WorkoutEvent.IncrementReps -> s.copy(values = s.values.copy(reps = (s.values.reps + 1).coerceAtMost(30)))
        WorkoutEvent.DecrementReps -> s.copy(values = s.values.copy(reps = (s.values.reps - 1).coerceAtLeast(0)))
        WorkoutEvent.SelectRir -> if (c.rir in 0..4) s.copy(values = s.values.copy(rir = c.rir!!)) else s
        WorkoutEvent.CompleteSet -> if (s.values.reps == 0) s else {
            val p: PlannedSet = plan[s.index]
            val record = SetRecord(s.index, p.exercise, p.number, s.values, now.wallMillis)
            if (s.index == plan.lastIndex) s.copy(phase = Phase.Completed, records = s.records + record, rest = null)
            else s.copy(phase = Phase.Resting, records = s.records + record,
                rest = RestDeadline(now.elapsedMillis + 10_000, now.wallMillis + 10_000, now.bootCount))
        }
        else -> s
    }

    private fun advance(s: WorkoutState): WorkoutState {
        val next: Int = (s.index + 1).coerceAtMost(plan.lastIndex)
        val proposed: SetValues = values(next).let {
            if (plan[next].exercise == plan[s.index].exercise) it.copy(weightTenths = s.values.weightTenths) else it
        }
        return s.copy(phase = Phase.ActiveSet, index = next, values = proposed, rest = null)
    }

    private fun undo(s: WorkoutState): WorkoutState {
        val last: SetRecord = s.records.lastOrNull() ?: return s
        return s.copy(phase = Phase.ActiveSet, index = last.index, values = last.values,
            records = s.records.dropLast(1), rest = null, endedEarly = false)
    }
}
