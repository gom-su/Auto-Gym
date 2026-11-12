package com.example.workoutpoc.domain

/**
 * Pure, deterministic transitions. Storage and Android APIs remain outside this class.
 * Each session carries its own plan snapshot; an empty snapshot uses [defaultPlan] (the demo).
 */
class WorkoutEditException(message: String) : IllegalArgumentException(message)

const val MAX_PLAN_SIZE = 1000

/** Shared by the reducer and storage validation. Throws [WorkoutEditException] with a user-facing message. */
fun validateCardio(c: CardioEntry) {
    fun check(ok: Boolean, message: String) { if (!ok) throw WorkoutEditException(message) }
    check(c.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")), "Identificador de cardio no válido.")
    check(c.modality.isNotBlank() && c.modality.trim().length <= 200, "Indica la modalidad de cardio (máximo 200 caracteres).")
    check(c.durationSeconds == null || c.durationSeconds in 0..604_800, "Duración de cardio fuera de rango.")
    check((c.distanceTenths == null) == (c.distanceUnit == null), "La distancia necesita valor y unidad.")
    check(c.distanceTenths == null || c.distanceTenths in 0..10_000_000, "Distancia fuera de rango.")
    check(c.durationSeconds != null || c.distanceTenths != null || !c.notes.isNullOrBlank(), "Registra al menos duración, distancia o una nota.")
    check(c.notes == null || c.notes.trim().length <= MAX_NOTE_LENGTH, "La nota supera $MAX_NOTE_LENGTH caracteres.")
}

class WorkoutReducer(private val defaultPlan: List<PlannedSet>) {
    init { require(defaultPlan.isNotEmpty()) }

    fun planOf(s: WorkoutState): List<PlannedSet> = s.plan.ifEmpty { defaultPlan }

    fun reduce(s: WorkoutState, c: WorkoutCommand, now: ClockSnapshot, newSessionId: String): WorkoutState {
        if (c.sessionId != s.sessionId || c.index != s.index || c.revision != s.revision) return s
        if (c.event in restActions && c.restSegmentId != s.openRestSegment?.id) return s
        // A paused session only resumes, finishes or closes; stale taps from the overlay/notification are inert.
        if (s.paused != null && c.event !in setOf(WorkoutEvent.ResumeSession, WorkoutEvent.FinishWorkout, WorkoutEvent.ResetDemo)) return s
        val plan = planOf(s)
        val changed: WorkoutState = when (c.event) {
            WorkoutEvent.StartWorkout -> if (s.phase == Phase.Idle) start(s, plan, newSessionId, now) else s
            WorkoutEvent.ResetDemo -> WorkoutState(sessionId = newSessionId)
            WorkoutEvent.UndoLastSet -> undo(s)
            WorkoutEvent.FinishWorkout -> if (s.phase == Phase.ActiveSet || s.phase == Phase.Resting)
                closeRest(s, now, interrupted = true).copy(phase = Phase.Completed, rest = null, endedEarly = true, endedAtMillis = now.wallMillis,
                    paused = null, pausedMillis = s.pausedMillis + (s.paused?.elapsedSince(now) ?: 0)) else s
            WorkoutEvent.PauseSession -> if (s.source != null && s.paused == null && (s.phase == Phase.ActiveSet || s.phase == Phase.Resting))
                s.copy(paused = PauseMark(now.elapsedMillis, now.wallMillis, now.bootCount)) else s
            WorkoutEvent.ResumeSession -> s.paused?.let { mark ->
                val pause = mark.elapsedSince(now)
                updateRestSegment(s) { it.copy(pausedMillis = it.pausedMillis + pause) }.copy(
                    paused = null, pausedMillis = s.pausedMillis + pause,
                    rest = s.rest?.let { it.copy(elapsedMillis = it.elapsedMillis + pause, wallMillis = it.wallMillis + pause) })
            } ?: s
            WorkoutEvent.AddWarmupSet -> if (s.source != null && plan.size < MAX_PLAN_SIZE &&
                (s.phase == Phase.ActiveSet || (s.phase == Phase.Resting && s.index < plan.lastIndex))) addWarmup(s, plan) else s
            WorkoutEvent.AddRestTime -> adjustRest(s, if (s.source == null) 10 else 15)
            WorkoutEvent.ReduceRestTime -> if (s.source != null) adjustRest(s, -15) else s
            WorkoutEvent.SkipRest -> if (s.phase == Phase.Resting) advance(closeRest(s, now, skipped = true), plan) else s
            WorkoutEvent.ConfirmRest -> if (s.phase == Phase.Resting && s.rest?.remainingMillis(now) == 0L)
                advance(closeRest(s, now), plan) else s
            WorkoutEvent.RestFinished -> if (s.source == null && s.phase == Phase.Resting && s.rest?.remainingMillis(now) == 0L)
                advance(s, plan) else s
            // Deviations apply to planned sessions; the demo keeps its fixed proof-of-concept plan.
            WorkoutEvent.SkipSet -> if (s.source != null && s.phase == Phase.ActiveSet) skip(s, plan, now) else s
            WorkoutEvent.AddSet -> if (s.source != null && s.phase != Phase.Idle && plan.size < MAX_PLAN_SIZE) addSet(s, plan) else s
            WorkoutEvent.RemoveExtraSet -> if (s.source != null && s.phase == Phase.ActiveSet && plan[s.index].extra) removeExtra(s, plan, now) else s
            WorkoutEvent.PostponeExercise -> s.postponeTarget(plan)?.let { postpone(s, plan, it) } ?: s
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

    /**
     * Replaces the recorded values, status and notes of one confirmed set of a planned session.
     * The prescription is untouched. Stale snapshots are ignored; out-of-range values are rejected.
     */
    fun edit(s: WorkoutState, sessionId: String, revision: Long, index: Int, status: SetStatus,
             values: SetValues, notes: String?, now: ClockSnapshot): WorkoutState {
        if (sessionId != s.sessionId || revision != s.revision || s.source == null) return s
        val record = s.records.getOrNull(index) ?: throw WorkoutEditException("La serie no está registrada.")
        val p = s.plan[index]
        val cleanNotes = notes?.trim()?.takeIf { it.isNotEmpty() }
        if (values.weightTenths !in 0..p.maxWeightTenths) throw WorkoutEditException("Carga fuera de rango.")
        if (values.reps !in (if (status == SetStatus.COMPLETED) 1 else 0)..p.maxReps)
            throw WorkoutEditException(if (status == SetStatus.COMPLETED) "Una serie completada necesita entre 1 y ${p.maxReps} repeticiones." else "Repeticiones fuera de rango.")
        if (values.rir != null && values.rir !in 0..4) throw WorkoutEditException("RIR fuera de rango.")
        if (cleanNotes != null && cleanNotes.length > MAX_NOTE_LENGTH) throw WorkoutEditException("La nota supera $MAX_NOTE_LENGTH caracteres.")
        val updated = record.copy(status = status, values = values, notes = cleanNotes)
        if (updated == record) return s
        if (record.revisions.size >= MAX_SET_REVISIONS) throw WorkoutEditException("La serie alcanzó el máximo de $MAX_SET_REVISIONS ediciones.")
        // The replaced version is kept, never overwritten, so the edit trail stays complete.
        val replaced = SetRevision(record.status, record.values, record.notes, now.wallMillis, record.recordedUnit)
        return s.copy(records = s.records.toMutableList().also {
            it[index] = updated.copy(editedAtMillis = now.wallMillis, revisions = record.revisions + replaced)
        }, revision = s.revision + 1)
    }

    private fun skip(s: WorkoutState, plan: List<PlannedSet>, now: ClockSnapshot): WorkoutState {
        val p = plan[s.index]
        val record = SetRecord(s.index, p.exercise, p.number, s.values, now.wallMillis, p.prescriptionId, SetStatus.SKIPPED)
        return if (s.index == plan.lastIndex)
            s.copy(phase = Phase.Completed, records = s.records + record, rest = null, endedAtMillis = now.wallMillis)
        else advance(s.copy(records = s.records + record), plan)
    }

    /** Inserts an unprescribed set of the last exercise as the next position, prefilled from its last real values. */
    private fun addSet(s: WorkoutState, plan: List<PlannedSet>): WorkoutState {
        val last = s.records.lastOrNull()
        val template = plan[last?.index ?: s.index]
        val count = plan.count { it.exercise == template.exercise && it.exerciseId == template.exerciseId }
        val prefill = last?.values ?: SetValues(template.weightTenths, template.reps, template.rir)
        val extra = template.copy(number = count + 1, prescriptionId = null, extra = true, warmup = false, loadPrescribed = false, setType = SetType.WORK,
            weightTenths = prefill.weightTenths, reps = prefill.reps, rir = prefill.rir)
        val position = s.records.size
        val newPlan = plan.take(position) + extra + plan.drop(position)
        return when (s.phase) {
            Phase.ActiveSet -> s.copy(plan = newPlan, values = values(newPlan, position))
            Phase.Resting -> s.copy(plan = newPlan)
            Phase.Completed -> s.copy(plan = newPlan, phase = Phase.ActiveSet, index = position, values = values(newPlan, position),
                endedEarly = false, endedAtMillis = null)
            Phase.Idle -> s
        }
    }

    /** Inserts a warm-up set before the next set to perform, prefilled from that set (the user adjusts it). */
    private fun addWarmup(s: WorkoutState, plan: List<PlannedSet>): WorkoutState {
        val position = s.records.size
        val target = plan[position]
        val count = plan.count { it.setType != SetType.WORK && it.exercise == target.exercise && it.exerciseId == target.exerciseId }
        val warmup = target.copy(number = count + 1, prescriptionId = null, extra = true, warmup = true,
            loadPrescribed = false, setType = SetType.APPROACH)
        val newPlan = plan.take(position) + warmup + plan.drop(position)
        return if (s.phase == Phase.ActiveSet) s.copy(plan = newPlan, values = values(newPlan, position)) else s.copy(plan = newPlan)
    }

    fun addCardio(s: WorkoutState, sessionId: String, revision: Long, entry: CardioEntry): WorkoutState {
        if (sessionId != s.sessionId || revision != s.revision || s.source == null || s.phase == Phase.Idle) return s
        validateCardio(entry)
        if (s.cardio.any { it.id == entry.id } || s.cardio.size >= 100) throw WorkoutEditException("No se pueden añadir más registros de cardio.")
        return s.copy(cardio = s.cardio + entry.copy(modality = entry.modality.trim(), notes = entry.notes?.trim()?.takeIf { it.isNotEmpty() }),
            revision = s.revision + 1)
    }

    fun removeCardio(s: WorkoutState, sessionId: String, revision: Long, id: String): WorkoutState {
        if (sessionId != s.sessionId || revision != s.revision || s.cardio.none { it.id == id }) return s
        return s.copy(cardio = s.cardio.filterNot { it.id == id }, revision = s.revision + 1)
    }

    /** Swaps the current exercise's unrecorded block with the next exercise's; the next exercise is prefilled from its prescription. */
    private fun postpone(s: WorkoutState, plan: List<PlannedSet>, t: PostponeTarget): WorkoutState {
        val newPlan = plan.subList(0, s.index) + plan.subList(t.currentEnd, t.nextEnd) +
            plan.subList(s.index, t.currentEnd) + plan.subList(t.nextEnd, plan.size)
        return s.copy(plan = newPlan, values = values(newPlan, s.index))
    }

    private fun removeExtra(s: WorkoutState, plan: List<PlannedSet>, now: ClockSnapshot): WorkoutState {
        val newPlan = plan.filterIndexed { i, _ -> i != s.index }
        if (newPlan.isEmpty()) return s
        return if (s.index == newPlan.size)
            s.copy(plan = newPlan, phase = Phase.Completed, index = newPlan.lastIndex, rest = null, endedAtMillis = now.wallMillis)
        else s.copy(plan = newPlan, values = values(newPlan, s.index))
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
                    else -> beginRest(s.copy(records = s.records + record), p, now)
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
            val same = current.setType == SetType.WORK && n.setType == SetType.WORK &&
                n.exercise == current.exercise && n.exerciseId == current.exerciseId && n.unit == current.unit &&
                n.weightTenths == current.weightTenths && n.loadPrescribed == current.loadPrescribed
            if (same) it.copy(weightTenths = s.values.weightTenths.coerceAtMost(plan[next].maxWeightTenths)) else it
        }
        return s.copy(phase = Phase.ActiveSet, index = next, values = proposed, rest = null)
    }

    private fun undo(s: WorkoutState): WorkoutState {
        val last: SetRecord = s.records.lastOrNull() ?: return s
        return s.copy(phase = Phase.ActiveSet, index = last.index, values = last.values,
            records = s.records.dropLast(1), rest = null, endedEarly = false, endedAtMillis = null,
            restSegments = s.restSegments.dropLastWhile { it.afterStepId == stepId(last.prescriptionId, last.index) })
    }

    private fun beginRest(s: WorkoutState, step: PlannedSet, now: ClockSnapshot): WorkoutState {
        val seconds = step.restSeconds
        val duration = seconds * 1000L
        val segments = if (s.source == null) s.restSegments else s.restSegments + RestSegment(
            id = "rest-${s.revision}", afterStepId = stepId(step.prescriptionId, s.index),
            startedAtMillis = now.wallMillis, bootCount = now.bootCount, configuredSeconds = seconds,
            startedAtElapsedMillis = now.elapsedMillis
        )
        return s.copy(phase = Phase.Resting, restSegments = segments,
            rest = RestDeadline(now.elapsedMillis + duration, now.wallMillis + duration, now.bootCount))
    }

    private fun adjustRest(s: WorkoutState, deltaSeconds: Int): WorkoutState {
        val deadline = s.rest ?: return s
        val segment = s.openRestSegment
        val configured = segment?.let { (it.configuredSeconds + deltaSeconds).coerceIn(45, 120) }
        val deltaMillis = when {
            segment != null && configured != null -> (configured - segment.configuredSeconds) * 1000L
            s.source == null -> deltaSeconds * 1000L
            else -> return s
        }
        return updateRestSegment(s) { it.copy(configuredSeconds = requireNotNull(configured)) }.copy(
            rest = deadline.copy(elapsedMillis = deadline.elapsedMillis + deltaMillis, wallMillis = deadline.wallMillis + deltaMillis))
    }

    private fun closeRest(s: WorkoutState, now: ClockSnapshot, skipped: Boolean = false, interrupted: Boolean = false): WorkoutState =
        updateRestSegment(s) { segment ->
            val paused = segment.pausedMillis + (s.paused?.elapsedSince(now) ?: 0)
            segment.copy(pausedMillis = paused, confirmedAtMillis = now.wallMillis.takeUnless { interrupted },
                measuredMillis = segment.copy(pausedMillis = paused).elapsedMillis(now), skipped = skipped, interrupted = interrupted)
        }

    private fun updateRestSegment(s: WorkoutState, update: (RestSegment) -> RestSegment): WorkoutState {
        val segment = s.openRestSegment ?: return s
        return s.copy(restSegments = s.restSegments.dropLast(1) + update(segment))
    }

    private fun stepId(prescriptionId: String?, index: Int): String = prescriptionId ?: "set-$index"

    private companion object {
        val restActions = setOf(WorkoutEvent.AddRestTime, WorkoutEvent.ReduceRestTime, WorkoutEvent.SkipRest, WorkoutEvent.ConfirmRest)
    }
}
