package com.example.workoutpoc.domain

/**
 * Pure, deterministic transitions. Storage and Android APIs remain outside this class.
 * Each session carries its own plan snapshot; an empty snapshot uses [defaultPlan] (the demo).
 */
class WorkoutReducer(private val defaultPlan: List<PlannedSet>) {
    init { require(defaultPlan.isNotEmpty()) }

    fun planOf(s: WorkoutState): List<PlannedSet> = s.plan.ifEmpty { defaultPlan }

    fun reduce(s: WorkoutState, c: WorkoutCommand, now: ClockSnapshot, newSessionId: String): WorkoutState {
        if (c.sessionId != s.sessionId || c.index != s.index || c.revision != s.revision) return s
        val plan = planOf(s)
        val changed: WorkoutState = when (c.event) {
            WorkoutEvent.StartWorkout -> if (s.phase == Phase.Idle) start(s, plan, newSessionId, now) else s
            WorkoutEvent.ResetDemo -> WorkoutState(sessionId = newSessionId)
            WorkoutEvent.UndoLastSet -> undo(s)
            WorkoutEvent.FinishWorkout -> if (s.phase == Phase.ActiveSet || s.phase == Phase.Resting)
                s.copy(phase = Phase.Completed, rest = null, endedEarly = true, endedAtMillis = now.wallMillis) else s
            WorkoutEvent.AddRestTime -> if (s.phase == Phase.Resting && s.rest != null)
                s.copy(rest = s.rest.copy(elapsedMillis = s.rest.elapsedMillis + 10_000, wallMillis = s.rest.wallMillis + 10_000)) else s
            WorkoutEvent.SkipRest -> if (s.phase == Phase.Resting) advance(s, plan) else s
            WorkoutEvent.RestFinished -> if (s.phase == Phase.Resting && s.rest?.remainingMillis(now) == 0L) advance(s, plan) else s
            else -> if (s.phase == Phase.ActiveSet) active(s, plan, c, now) else s
        }
        return if (changed == s) s else changed.copy(revision = s.revision + 1)
    }

    /**
     * Loads a planned day without starting it. Only an idle or finished session may be replaced, so an
     * in-progress session can never be discarded by loading another day; callers archive finished records first.
     */
    fun load(s: WorkoutState, plan: List<PlannedSet>, source: SessionSource, newSessionId: String): WorkoutState {
        require(plan.isNotEmpty()) { "El día no contiene series." }
        if (s.phase == Phase.ActiveSet || s.phase == Phase.Resting) return s
        return WorkoutState(sessionId = newSessionId, revision = s.revision + 1, values = values(plan, 0),
            plan = plan, source = source)
    }

    private fun start(s: WorkoutState, plan: List<PlannedSet>, id: String, now: ClockSnapshot): WorkoutState =
        s.copy(sessionId = id, phase = Phase.ActiveSet, index = 0, values = values(plan, 0), records = emptyList(),
            rest = null, endedEarly = false, startedAtMillis = now.wallMillis, endedAtMillis = null)

    private fun values(plan: List<PlannedSet>, index: Int): SetValues = plan[index].let { SetValues(it.weightTenths, it.reps, it.rir) }

    private fun active(s: WorkoutState, plan: List<PlannedSet>, c: WorkoutCommand, now: ClockSnapshot): WorkoutState {
        val p: PlannedSet = plan[s.index]
        return when (c.event) {
            WorkoutEvent.IncrementWeight -> s.copy(values = s.values.copy(weightTenths = (s.values.weightTenths + p.weightStepTenths).coerceAtMost(p.maxWeightTenths)))
            WorkoutEvent.DecrementWeight -> s.copy(values = s.values.copy(weightTenths = (s.values.weightTenths - p.weightStepTenths).coerceAtLeast(0)))
            WorkoutEvent.IncrementReps -> s.copy(values = s.values.copy(reps = (s.values.reps + 1).coerceAtMost(p.maxReps)))
            WorkoutEvent.DecrementReps -> s.copy(values = s.values.copy(reps = (s.values.reps - 1).coerceAtLeast(0)))
            WorkoutEvent.SelectRir -> if (c.rir in 0..4) s.copy(values = s.values.copy(rir = c.rir!!)) else s
            WorkoutEvent.CompleteSet -> if (s.values.reps == 0) s else {
                val record = SetRecord(s.index, p.exercise, p.number, s.values, now.wallMillis, p.prescriptionId)
                when {
                    s.index == plan.lastIndex ->
                        s.copy(phase = Phase.Completed, records = s.records + record, rest = null, endedAtMillis = now.wallMillis)
                    p.restSeconds == 0 -> advance(s.copy(records = s.records + record), plan)
                    else -> s.copy(phase = Phase.Resting, records = s.records + record,
                        rest = RestDeadline(now.elapsedMillis + p.restSeconds * 1000L, now.wallMillis + p.restSeconds * 1000L, now.bootCount))
                }
            }
            else -> s
        }
    }

    private fun advance(s: WorkoutState, plan: List<PlannedSet>): WorkoutState {
        val next: Int = (s.index + 1).coerceAtMost(plan.lastIndex)
        val current = plan[s.index]
        val proposed: SetValues = values(plan, next).let {
            // Carry the last real load only to the same exercise, unit and prescribed load (a repeated deviation);
            // a set that prescribes a different load prefills that prescription instead.
            val n = plan[next]
            val same = n.exercise == current.exercise && n.exerciseId == current.exerciseId && n.unit == current.unit &&
                n.weightTenths == current.weightTenths && n.loadPrescribed == current.loadPrescribed
            if (same) it.copy(weightTenths = s.values.weightTenths.coerceAtMost(plan[next].maxWeightTenths)) else it
        }
        return s.copy(phase = Phase.ActiveSet, index = next, values = proposed, rest = null)
    }

    private fun undo(s: WorkoutState): WorkoutState {
        val last: SetRecord = s.records.lastOrNull() ?: return s
        return s.copy(phase = Phase.ActiveSet, index = last.index, values = last.values,
            records = s.records.dropLast(1), rest = null, endedEarly = false, endedAtMillis = null)
    }
}
