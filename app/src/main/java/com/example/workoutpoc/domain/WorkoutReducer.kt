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

/** One corrected set of an archived session (requirement 9). [index] is the record index in the session. */
data class PastSetEdit(val index: Int, val status: SetStatus, val values: SetValues, val notes: String?,
                       val recordedUnit: WeightUnit? = null)

class WorkoutReducer(private val defaultPlan: List<PlannedSet>) {
    init { require(defaultPlan.isNotEmpty()) }

    fun planOf(s: WorkoutState): List<PlannedSet> = if (s.source == null) defaultPlan else s.plan

    fun reduce(s0: WorkoutState, c: WorkoutCommand, now: ClockSnapshot, newSessionId: String): WorkoutState {
        if (c.sessionId != s0.sessionId || c.index != s0.index || c.revision != s0.revision) return s0
        // A pending wait whose deadline has passed starts at that exact deadline before any command is applied.
        val s = settleWait(s0, now)
        if (c.event in restActions && c.restSegmentId != s.openRestSegment?.id) return s
        // A paused session only resumes, finishes or closes; stale taps from the overlay/notification are inert.
        if (s.paused != null && c.event !in setOf(WorkoutEvent.ResumeSession, WorkoutEvent.FinishWorkout, WorkoutEvent.ResetDemo, WorkoutEvent.FinishCardio)) return s
        // While a machine wait runs, nothing that completes, advances or closes the step is accepted (requirement 8).
        if (s.runningWait != null && c.event in blockedDuringWait) return s
        val plan = planOf(s)
        val changed: WorkoutState = when (c.event) {
            WorkoutEvent.StartWorkout -> if (s.phase == Phase.Idle) start(s, plan, newSessionId, now) else s
            WorkoutEvent.ResetDemo -> WorkoutState(sessionId = newSessionId)
            WorkoutEvent.UndoLastSet -> if (s.phase != Phase.ActiveCardio) undo(s) else s
            WorkoutEvent.FinishWorkout -> if (s.phase.inProgress)
                recordCardio(closeRest(s, now, interrupted = true), now).copy(phase = Phase.Completed, rest = null, endedEarly = true, endedAtMillis = now.wallMillis,
                    index = s.index.coerceAtMost(plan.lastIndex.coerceAtLeast(0)),
                    values = if (s.phase == Phase.ActiveCardio) plan.getOrNull(s.index.coerceAtMost(plan.lastIndex))
                        ?.let { s.prefillSet(it) } ?: s.values else s.values,
                    paused = null, pausedMillis = s.pausedMillis + (s.paused?.elapsedSince(now) ?: 0)) else s
            WorkoutEvent.StartMachineWait -> startWait(s, now)
            WorkoutEvent.EndMachineWait -> endWait(s, now)
            WorkoutEvent.CancelPendingWait -> s.openWait?.takeIf { it.pending != null }?.let { w ->
                s.replaceOpenWait(w.copy(cancelled = true, pending = null)) } ?: s
            WorkoutEvent.PauseSession -> if (s.source != null && s.paused == null && s.phase.inProgress)
                s.copy(paused = PauseMark(now.elapsedMillis, now.wallMillis, now.bootCount)) else s
            WorkoutEvent.ResumeSession -> s.paused?.let { mark ->
                val pause = mark.elapsedSince(now)
                // A running wait keeps the pause out of its own duration; a pending one moves with the rest deadline.
                val waits = s.machineWaits.map { w ->
                    if (w.endedAtMillis != null || w.cancelled) w
                    else if (w.startedAtMillis != null) w.copy(pausedMillis = w.pausedMillis + pause)
                    else w.copy(pending = w.pending?.shifted(pause))
                }
                updateRestSegment(s) { it.copy(pausedMillis = it.pausedMillis + pause) }.copy(
                    paused = null, pausedMillis = s.pausedMillis + pause,
                    cardioRun = s.cardioRun?.let { it.copy(pausedMillis = it.pausedMillis + pause) },
                    rest = s.rest?.let { it.copy(elapsedMillis = it.elapsedMillis + pause, wallMillis = it.wallMillis + pause) },
                    machineWaits = waits)
            } ?: s
            WorkoutEvent.AddWarmupSet -> if (s.source != null && plan.size < MAX_PLAN_SIZE &&
                (s.phase == Phase.ActiveSet || (s.phase == Phase.Resting && s.index < plan.lastIndex))) addWarmup(s, plan) else s
            WorkoutEvent.AddRestTime -> adjustRest(s, if (s.source == null) 10 else 15, now)
            WorkoutEvent.ReduceRestTime -> if (s.source != null) adjustRest(s, -15, now) else s
            WorkoutEvent.SkipRest -> if (s.phase == Phase.Resting) advance(closeRest(s, now, skipped = true), plan, now) else s
            WorkoutEvent.ConfirmRest -> if (s.phase == Phase.Resting && s.rest?.remainingMillis(now) == 0L)
                advance(closeRest(s, now), plan, now) else s
            WorkoutEvent.RestFinished -> if (s.source == null && s.phase == Phase.Resting && s.rest?.remainingMillis(now) == 0L)
                advance(s, plan, now) else s
            WorkoutEvent.FinishCardio -> if (s.phase == Phase.ActiveCardio)
                enterStep(recordCardio(s, now).copy(paused = null,
                    pausedMillis = s.pausedMillis + (s.paused?.elapsedSince(now) ?: 0)), plan, s.index, now) else s
            // Deviations apply to planned sessions; the demo keeps its fixed proof-of-concept plan.
            WorkoutEvent.SkipSet -> if (s.source != null && s.phase == Phase.ActiveSet) skip(s, plan, now) else s
            WorkoutEvent.AddSet -> if (s.source != null && s.phase != Phase.Idle && s.phase != Phase.ActiveCardio &&
                plan.isNotEmpty() && plan.size < MAX_PLAN_SIZE) addSet(s, plan) else s
            WorkoutEvent.RemoveExtraSet -> if (s.source != null && s.phase == Phase.ActiveSet && plan[s.index].extra) removeExtra(s, plan, now) else s
            WorkoutEvent.PostponeExercise -> s.postponeTarget(plan)?.let { postpone(s, plan, it, now) } ?: s
            else -> if (s.phase == Phase.ActiveSet) active(s, plan, c, now) else s
        }
        if (changed == s) return s
        return changed.cancelOrphanWait().trackTiming(s, c.event, now).copy(revision = s.revision + 1)
    }

    /**
     * Loads a planned day without starting it. Only an idle or finished session may be replaced, so an
     * in-progress session can never be discarded by loading another day; callers archive finished records first.
     */
    fun load(s: WorkoutState, plan: List<PlannedSet>, source: SessionSource, newSessionId: String,
             previousWorkSets: Map<String, PreviousWorkSet> = emptyMap(), cardioPlan: List<PlannedCardio> = emptyList()): WorkoutState {
        require(plan.isNotEmpty() || cardioPlan.isNotEmpty()) { "El día no contiene pasos de entrenamiento." }
        if (s.phase.inProgress) return s
        val loaded = WorkoutState(sessionId = newSessionId, revision = s.revision + 1, plan = plan, source = source,
            previousWorkSets = previousWorkSets, cardioPlan = cardioPlan)
        return plan.firstOrNull()?.let { loaded.copy(values = loaded.prefillSet(it)) } ?: loaded
    }

    /**
     * Replaces the recorded values, status and notes of one confirmed set of a planned session.
     * The prescription is untouched. Stale snapshots are ignored; out-of-range values are rejected.
     */
    fun edit(s: WorkoutState, sessionId: String, revision: Long, index: Int, status: SetStatus,
             values: SetValues, notes: String?, now: ClockSnapshot, recordedUnit: WeightUnit? = null): WorkoutState {
        if (sessionId != s.sessionId || revision != s.revision || s.source == null) return s
        val record = s.records.getOrNull(index) ?: throw WorkoutEditException("La serie no está registrada.")
        val p = s.plan[index]
        val cleanNotes = notes?.trim()?.takeIf { it.isNotEmpty() }
        if (values.weightTenths !in 0..p.maxWeightTenths) throw WorkoutEditException("Carga fuera de rango.")
        if (values.reps !in (if (status == SetStatus.COMPLETED) 1 else 0)..p.maxReps)
            throw WorkoutEditException(if (status == SetStatus.COMPLETED) "Una serie completada necesita entre 1 y ${p.maxReps} repeticiones." else "Repeticiones fuera de rango.")
        if (values.rir != null && values.rir !in 0..4) throw WorkoutEditException("RIR fuera de rango.")
        if (status == SetStatus.COMPLETED && p.setType == SetType.WORK && values.rir == null)
            throw WorkoutEditException("Selecciona el RIR")
        if (cleanNotes != null && cleanNotes.length > MAX_NOTE_LENGTH) throw WorkoutEditException("La nota supera $MAX_NOTE_LENGTH caracteres.")
        // A unit other than the prescribed one is kept as the record's unit; null means the prescribed unit (contract §2).
        val unitRecorded = recordedUnit?.takeIf { it != p.unit }
        val updated = record.copy(status = status, values = values, notes = cleanNotes, recordedUnit = unitRecorded)
        if (updated == record) return s
        if (record.revisions.size >= MAX_SET_REVISIONS) throw WorkoutEditException("La serie alcanzó el máximo de $MAX_SET_REVISIONS ediciones.")
        // The replaced version is kept, never overwritten, so the edit trail stays complete.
        val replaced = SetRevision(record.status, record.values, record.notes, now.wallMillis, record.recordedUnit)
        return s.copy(records = s.records.toMutableList().also {
            it[index] = updated.copy(editedAtMillis = now.wallMillis, revisions = record.revisions + replaced)
        }, revision = s.revision + 1)
    }

    /**
     * Corrects an archived planned session (requirement 9). A new date shifts the session's wall-clock stamps by the same
     * amount, so its durations and its order inside the session stay the same; the link to the plan day is kept.
     * Each corrected set goes through [edit], so the replaced version is kept and the same validation applies.
     */
    fun editPastSession(s: WorkoutState, newStartMillis: Long?, sets: List<PastSetEdit>, now: ClockSnapshot): WorkoutState {
        if (s.source == null || s.phase.inProgress) throw WorkoutEditException("Solo se editan sesiones archivadas de un día planificado.")
        var cur = s
        val start = s.startedAtMillis
        if (newStartMillis != null && start == null) {
            // No recorded start yet: the corrected date becomes the start; nothing else is shifted.
            cur = cur.copy(startedAtMillis = newStartMillis, revision = cur.revision + 1)
        } else if (newStartMillis != null && start != null && newStartMillis != start) {
            val delta = newStartMillis - start
            cur = cur.copy(
                startedAtMillis = newStartMillis,
                endedAtMillis = cur.endedAtMillis?.plus(delta),
                records = cur.records.map { it.copy(completedAtMillis = it.completedAtMillis + delta) },
                cardio = cur.cardio.map { it.copy(recordedAtMillis = it.recordedAtMillis + delta) },
                restSegments = cur.restSegments.map { it.copy(startedAtMillis = it.startedAtMillis + delta,
                    confirmedAtMillis = it.confirmedAtMillis?.plus(delta)) },
                machineWaits = cur.machineWaits.map { it.copy(startedAtMillis = it.startedAtMillis?.plus(delta),
                    endedAtMillis = it.endedAtMillis?.plus(delta), pending = it.pending?.copy(wallMillis = it.pending.wallMillis + delta)) },
                revision = cur.revision + 1)
        }
        for (edit in sets) {
            val record = cur.records.getOrNull(edit.index) ?: throw WorkoutEditException("La serie no está registrada.")
            val setType = cur.plan[record.index].setType
            // Warm-ups and approaches carry no RIR (requirement 13), whatever the editor sent.
            val values = if (setType == SetType.WORK) edit.values else edit.values.copy(rir = null)
            cur = edit(cur, cur.sessionId, cur.revision, record.index, edit.status, values, edit.notes, now, edit.recordedUnit)
        }
        return cur
    }

    private fun skip(s: WorkoutState, plan: List<PlannedSet>, now: ClockSnapshot): WorkoutState {
        val p = plan[s.index]
        val record = SetRecord(s.index, p.exercise, p.number, s.values, now.wallMillis, p.prescriptionId, SetStatus.SKIPPED)
        return advance(s.copy(records = s.records + record), plan, now)
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
            Phase.ActiveSet -> s.copy(plan = newPlan, values = s.prefillSet(extra))
            Phase.Resting -> s.copy(plan = newPlan)
            Phase.Completed -> s.copy(plan = newPlan, phase = Phase.ActiveSet, index = position, values = s.prefillSet(extra),
                endedEarly = false, endedAtMillis = null)
            Phase.Idle, Phase.ActiveCardio -> s
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
        return if (s.phase == Phase.ActiveSet) s.copy(plan = newPlan, values = s.prefillSet(warmup)) else s.copy(plan = newPlan)
    }

    fun addCardio(s: WorkoutState, sessionId: String, revision: Long, entry: CardioEntry): WorkoutState {
        if (sessionId != s.sessionId || revision != s.revision || s.source == null || s.phase == Phase.Idle) return s
        validateCardio(entry)
        if (entry.plannedCardioId != null) throw WorkoutEditException("Usa el temporizador para registrar el cardio planificado.")
        if (s.cardio.any { it.id == entry.id } || s.cardio.count { it.plannedCardioId == null } + s.cardioPlan.size >= 100)
            throw WorkoutEditException("No se pueden añadir más registros de cardio.")
        return s.copy(cardio = s.cardio + entry.copy(modality = entry.modality.trim(), notes = entry.notes?.trim()?.takeIf { it.isNotEmpty() }),
            revision = s.revision + 1)
    }

    fun removeCardio(s: WorkoutState, sessionId: String, revision: Long, id: String): WorkoutState {
        if (sessionId != s.sessionId || revision != s.revision || s.cardio.none { it.id == id && it.plannedCardioId == null }) return s
        return s.copy(cardio = s.cardio.filterNot { it.id == id }, revision = s.revision + 1)
    }

    /** Swaps the current exercise's unrecorded block with the next exercise's; the next exercise is prefilled from its prescription. */
    private fun postpone(s: WorkoutState, plan: List<PlannedSet>, t: PostponeTarget, now: ClockSnapshot): WorkoutState {
        val newPlan = plan.subList(0, s.index) + plan.subList(t.currentEnd, t.nextEnd) +
            plan.subList(s.index, t.currentEnd) + plan.subList(t.nextEnd, plan.size)
        return enterStep(s.copy(plan = newPlan), newPlan, s.index, now)
    }

    private fun removeExtra(s: WorkoutState, plan: List<PlannedSet>, now: ClockSnapshot): WorkoutState {
        val newPlan = plan.filterIndexed { i, _ -> i != s.index }
        if (newPlan.isEmpty()) return s
        return enterStep(s.copy(plan = newPlan), newPlan, s.index, now)
    }

    private fun start(s: WorkoutState, plan: List<PlannedSet>, id: String, now: ClockSnapshot): WorkoutState =
        enterStep(s.copy(sessionId = id, records = emptyList(), rest = null, endedEarly = false,
            startedAtMillis = now.wallMillis, endedAtMillis = null), plan, 0, now)

    private fun active(s: WorkoutState, plan: List<PlannedSet>, c: WorkoutCommand, now: ClockSnapshot): WorkoutState {
        val p: PlannedSet = plan[s.index]
        return when (c.event) {
            WorkoutEvent.IncrementWeight -> s.copy(values = s.values.copy(weightTenths = (s.values.weightTenths + p.weightStepTenths).coerceAtMost(p.maxWeightTenths)))
            WorkoutEvent.DecrementWeight -> s.copy(values = s.values.copy(weightTenths = (s.values.weightTenths - p.weightStepTenths).coerceAtLeast(0)))
            WorkoutEvent.IncrementReps -> s.copy(values = s.values.copy(reps = (s.values.reps + 1).coerceAtMost(p.maxReps)))
            WorkoutEvent.DecrementReps -> s.copy(values = s.values.copy(reps = (s.values.reps - 1).coerceAtLeast(0)))
            // Warm-ups and approaches have no RIR (requirement 13): the selector is ignored for them.
            WorkoutEvent.SelectRir -> if (c.rir in 0..4 && p.setType == SetType.WORK) s.copy(values = s.values.copy(rir = c.rir!!)) else s
            // A work set needs a valid RIR, including a preloaded one; RIR 0 is valid (requirement 6).
            WorkoutEvent.CompleteSet -> if (s.values.reps == 0 || (p.setType == SetType.WORK && s.values.rir == null)) s else {
                val record = SetRecord(s.index, p.exercise, p.number, s.values, now.wallMillis, p.prescriptionId)
                when {
                    p.restSeconds == 0 || (s.index == plan.lastIndex && s.nextCardio(plan, s.index + 1) == null) ->
                        advance(s.copy(records = s.records + record), plan, now)
                    else -> beginRest(s.copy(records = s.records + record), p, now)
                }
            }
            else -> s
        }
    }

    private fun advance(s: WorkoutState, plan: List<PlannedSet>, now: ClockSnapshot): WorkoutState {
        val next = s.index + 1
        val proposed = plan.getOrNull(next)?.let { s.followingSetValues(plan[s.index], it) }
        return enterStep(s, plan, next, now, proposed)
    }

    private fun enterStep(s: WorkoutState, plan: List<PlannedSet>, index: Int, now: ClockSnapshot,
                          prefill: SetValues? = null): WorkoutState {
        val cardio = s.nextCardio(plan, index)
        if (cardio != null) return s.copy(phase = Phase.ActiveCardio, index = index, rest = null,
            cardioRun = CardioRun(cardio.stepId, PauseMark(now.elapsedMillis, now.wallMillis, now.bootCount)))
        val step = plan.getOrNull(index) ?: return s.copy(phase = Phase.Completed, index = plan.lastIndex.coerceAtLeast(0),
            rest = null, endedAtMillis = now.wallMillis)
        return s.copy(phase = Phase.ActiveSet, index = index, values = prefill ?: s.prefillSet(step), rest = null)
    }

    private fun recordCardio(s: WorkoutState, now: ClockSnapshot): WorkoutState {
        val run = s.cardioRun ?: return s
        val step = requireNotNull(s.currentCardio)
        val elapsed = (run.activeMillis(now, s.clockHold) / 1000).coerceAtMost(604_800).toInt()
        val id = generateSequence(s.revision) { it + 1 }.map { "cardio-$it" }.first { id -> s.cardio.none { it.id == id } }
        val entry = CardioEntry(id = id, modality = step.modality, durationSeconds = elapsed,
            distanceTenths = null, distanceUnit = null, notes = null, recordedAtMillis = now.wallMillis,
            plannedCardioId = step.stepId, partial = elapsed < step.plannedSeconds)
        return s.copy(cardio = s.cardio + entry, cardioRun = null)
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
            startedAtElapsedMillis = now.elapsedMillis, originalSeconds = seconds
        )
        return s.copy(phase = Phase.Resting, restSegments = segments,
            rest = RestDeadline(now.elapsedMillis + duration, now.wallMillis + duration, now.bootCount))
    }

    /**
     * ±15 s on the objective. Earlier history is not rewritten: a pending wait's start is never placed before the
     * adjustment itself, so time before the tap is measured against the objective that was in force.
     */
    private fun adjustRest(s: WorkoutState, deltaSeconds: Int, now: ClockSnapshot): WorkoutState {
        val deadline = s.rest ?: return s
        val segment = s.openRestSegment
        val configured = segment?.let { (it.configuredSeconds + deltaSeconds).coerceIn(45, 120) }
        val deltaMillis = when {
            segment != null && configured != null -> (configured - segment.configuredSeconds) * 1000L
            s.source == null -> deltaSeconds * 1000L
            else -> return s
        }
        val shifted = s.machineWaits.lastOrNull()?.takeIf { it.pending != null && it.endedAtMillis == null && !it.cancelled }
            ?.let { w ->
                val moved = w.pending!!.shifted(deltaMillis)
                val adjustedAt = RestDeadline(now.elapsedMillis, now.wallMillis, now.bootCount)
                val notBefore = moved.bootCount == now.bootCount && moved.elapsedMillis < now.elapsedMillis
                w.copy(pending = if (notBefore) adjustedAt else moved)
            }
        return updateRestSegment(s) { it.copy(configuredSeconds = requireNotNull(configured)) }.copy(
            rest = deadline.copy(elapsedMillis = deadline.elapsedMillis + deltaMillis, wallMillis = deadline.wallMillis + deltaMillis),
            machineWaits = shifted?.let { s.machineWaits.dropLast(1) + it } ?: s.machineWaits)
    }

    /**
     * Arms a wait during a rest (pending until its deadline, or running now if the objective is already exceeded), or
     * starts it now during a set or cardio step. The rest keeps its clock; only the wait span is excluded from it.
     */
    private fun startWait(s: WorkoutState, now: ClockSnapshot): WorkoutState {
        if (s.source == null || s.openWait != null || s.paused != null) return s
        val id = "wait-${s.revision}"
        return when (s.phase) {
            Phase.Resting -> {
                val rest = s.openRestSegment ?: return s
                val deadline = s.rest ?: return s
                val balance = s.restSignedBalance(now) ?: return s
                val base = MachineWait(id, rest.afterStepId, WaitOrigin.REST, restSegmentId = rest.id,
                    returnPhase = Phase.Resting, returnIndex = s.index)
                if (balance > 0) s.copy(machineWaits = s.machineWaits + base.copy(pending = deadline))
                else s.copy(machineWaits = s.machineWaits + base.started(now))
            }
            Phase.ActiveSet -> {
                val p = planOf(s).getOrNull(s.index) ?: return s
                s.copy(machineWaits = s.machineWaits + MachineWait(id, stepId(p.prescriptionId, s.index), WaitOrigin.EXERCISE,
                    returnPhase = Phase.ActiveSet, returnIndex = s.index).started(now))
            }
            Phase.ActiveCardio -> {
                val step = s.currentCardio ?: return s
                s.copy(machineWaits = s.machineWaits + MachineWait(id, step.stepId, WaitOrigin.EXERCISE,
                    returnPhase = Phase.ActiveCardio, returnIndex = s.index).started(now))
            }
            else -> s
        }
    }

    /** Ends a running wait. A REST-origin wait leaves the rest open (its clock resumes); an exercise wait resumes the step. */
    private fun endWait(s: WorkoutState, now: ClockSnapshot): WorkoutState {
        val w = s.runningWait ?: return s
        // Session pauses that happened during the wait are not part of its duration.
        val measured = (PauseMark(w.startedAtElapsedMillis ?: 0, w.startedAtMillis ?: 0, w.bootCount).elapsedSince(now) - w.pausedMillis)
            .coerceAtLeast(0)
        var next = s.replaceOpenWait(w.copy(endedAtMillis = now.wallMillis, measuredMillis = measured))
        next = when (w.origin) {
            WaitOrigin.REST -> {
                val rest = next.rest ?: return next
                next.copy(
                    restSegments = next.restSegments.map { seg ->
                        if (seg.id == w.restSegmentId) seg.copy(pausedMillis = seg.pausedMillis + measured) else seg },
                    rest = rest.copy(elapsedMillis = rest.elapsedMillis + measured, wallMillis = rest.wallMillis + measured))
            }
            WaitOrigin.EXERCISE -> next.cardioRun?.let { run ->
                next.copy(cardioRun = run.copy(pausedMillis = run.pausedMillis + measured)) } ?: next
        }
        return next
    }

    /** A pending wait whose deadline is reached starts at that deadline exactly, splitting the timing at that instant. */
    private fun settleWait(s: WorkoutState, now: ClockSnapshot): WorkoutState {
        val w = s.openWait ?: return s
        val deadline = w.pending ?: return s
        // A paused session starts no wait: the deadline moves with the pause when it resumes.
        if (s.paused != null || s.rest == null || deadline.signedRemainingMillis(now) > 0) return s
        val mark = deadline.asMark()
        val settled = s.replaceOpenWait(w.copy(pending = null, startedAtMillis = mark.wallMillis,
            startedAtElapsedMillis = mark.elapsedMillis, bootCount = mark.bootCount))
        val timing = s.timing ?: return settled
        // The open span (the rest) ends at the deadline itself, not at the moment this command arrived.
        val atDeadline = ClockSnapshot(mark.elapsedMillis, mark.wallMillis, mark.bootCount)
        val closed = timing.open?.let { timing.segments + TimedSegment(it.focus, it.start.elapsedSince(atDeadline)) } ?: timing.segments
        return settled.copy(timing = SessionTiming(closed, settled.timingFocus()?.let { OpenTimingSegment(it, mark) }))
    }

    /** A pending wait is cancelled when its rest is closed or the step leaves the rest. */
    private fun WorkoutState.cancelOrphanWait(): WorkoutState {
        val w = openWait ?: return this
        if (w.pending == null) return this
        if (phase == Phase.Resting && openRestSegment?.id == w.restSegmentId) return this
        return replaceOpenWait(w.copy(cancelled = true, pending = null))
    }

    private fun WorkoutState.replaceOpenWait(w: MachineWait): WorkoutState =
        copy(machineWaits = machineWaits.dropLast(1) + w)

    private fun MachineWait.started(now: ClockSnapshot): MachineWait =
        copy(pending = null, startedAtMillis = now.wallMillis, startedAtElapsedMillis = now.elapsedMillis, bootCount = now.bootCount)

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
        /** Events that would complete, advance, close or re-time the step while a machine wait runs. */
        val blockedDuringWait = setOf(WorkoutEvent.CompleteSet, WorkoutEvent.SkipSet, WorkoutEvent.SkipRest,
            WorkoutEvent.ConfirmRest, WorkoutEvent.FinishCardio, WorkoutEvent.FinishWorkout, WorkoutEvent.UndoLastSet,
            WorkoutEvent.PostponeExercise, WorkoutEvent.RemoveExtraSet, WorkoutEvent.AddSet, WorkoutEvent.AddWarmupSet,
            WorkoutEvent.AddRestTime, WorkoutEvent.ReduceRestTime, WorkoutEvent.RestFinished)
    }
}
