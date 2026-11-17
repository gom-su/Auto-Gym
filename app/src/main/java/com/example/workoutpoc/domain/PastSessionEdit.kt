package com.example.workoutpoc.domain

/** A corrected rest of an archived session: its objective in force and its real time (null = not measured). */
data class PastRestEdit(val id: String, val objectiveSeconds: Int, val realMillis: Long?)

/**
 * Every correction to an archived planned session (requirement 9). Only what changed is listed; anything left out keeps
 * its recorded value.
 * - [startMillis]: the new start (date and time of day); the whole session moves with it.
 * - [setMillis]: active time of a set, by record index.
 * - [rests], [waitMillis] (by wait ID) and [pauseMillis] (by position among the pauses of [sessionTimes]): the other times.
 * - [cardio]: recorded cardio entries with corrected values, matched by ID.
 * - [endMillis]: only for a session without a measured timeline; otherwise the end follows the corrected times.
 */
data class PastSessionEdit(
    val startMillis: Long? = null,
    val sets: List<PastSetEdit> = emptyList(),
    val setMillis: Map<Int, Long> = emptyMap(),
    val rests: List<PastRestEdit> = emptyList(),
    val waitMillis: Map<String, Long> = emptyMap(),
    val pauseMillis: Map<Int, Long> = emptyMap(),
    val cardio: List<CardioEntry> = emptyList(),
    val endMillis: Long? = null
)

/** Longest time one item may be corrected to. */
const val MAX_EDITED_MILLIS = 86_400_000L
/** Longest rest objective a correction accepts, in seconds. */
const val MAX_REST_OBJECTIVE_SECONDS = 3600

/** One rest as it can be corrected. [setIndex] is the set it follows; [originalSeconds] is the plan's objective. */
data class RestTime(val id: String, val setIndex: Int?, val objectiveSeconds: Int, val originalSeconds: Int?,
                    val realMillis: Long?, val skipped: Boolean, val interrupted: Boolean)

/** One machine wait that ran. It belongs to a set ([setIndex]) or to a planned cardio step ([cardioStepId]). */
data class WaitTime(val id: String, val setIndex: Int?, val cardioStepId: String?, val fromRest: Boolean, val millis: Long)

/** One pause span of the timeline, during a set or its rest ([setIndex]) or during cardio ([cardioStepId]). */
data class PauseTime(val setIndex: Int?, val cardioStepId: String?, val duringRest: Boolean, val millis: Long)

/**
 * The times of a session item by item, as the past-session editor shows and corrects them. [measured] is false for
 * sessions recorded before the timeline existed: their sets have no duration and the end is corrected directly.
 */
data class SessionTimes(
    val measured: Boolean,
    val setMillis: Map<Int, Long>,
    val rests: List<RestTime>,
    val waits: List<WaitTime>,
    val pauses: List<PauseTime>,
    val editedAtMillis: Long?
)

/** Key of the step a rest, wait or timing span belongs to: a set's prescription ID (or "set-<index>"), or a cardio step ID. */
internal fun stepKey(prescriptionId: String?, index: Int): String = prescriptionId ?: "set-$index"

private fun TimingFocus.key(): String? = setIndex?.let { stepKey(stepId, it) } ?: stepId

private fun WorkoutState.setIndexOf(key: String): Int? = plan.indices.firstOrNull { stepKey(plan[it].prescriptionId, it) == key }

fun WorkoutState.sessionTimes(): SessionTimes {
    val segments = timing?.segments.orEmpty()
    val setMillis = segments.filter { it.focus.activity == TimedActivity.SET && it.focus.setIndex in records.indices }
        .groupBy { it.focus.setIndex!! }.mapValues { (_, spans) -> spans.sumOf { it.durationMillis } }
    val rests = restSegments.map {
        RestTime(it.id, setIndexOf(it.afterStepId), it.configuredSeconds, it.originalSeconds, it.measuredMillis, it.skipped, it.interrupted)
    }
    val waits = machineWaits.filter { !it.cancelled && it.measuredMillis != null }.map { w ->
        val set = setIndexOf(w.afterStepId)
        WaitTime(w.id, set, w.afterStepId.takeIf { set == null }, w.origin == WaitOrigin.REST, w.measuredMillis!!)
    }
    // A pause's span carries the focus it interrupted; the rest it fell in is the REST span just before it, if any.
    val pauses = segments.withIndex().filter { it.value.focus.activity == TimedActivity.PAUSE }.map { (i, span) ->
        val previous = segments.subList(0, i).lastOrNull { it.focus.activity != TimedActivity.PAUSE && it.focus.activity != TimedActivity.MACHINE_WAIT }
        val duringRest = previous?.focus?.activity == TimedActivity.REST && previous.focus.setIndex == span.focus.setIndex
        PauseTime(span.focus.setIndex, span.focus.stepId.takeIf { span.focus.setIndex == null }, duringRest, span.durationMillis)
    }
    return SessionTimes(timing != null, setMillis, rests, waits, pauses, timesEditedAtMillis)
}

private fun checkMillis(value: Long?, what: String) {
    if (value != null && value !in 0..MAX_EDITED_MILLIS) throw WorkoutEditException("$what fuera de rango (máximo 24 h).")
}

/**
 * Applies the time and cardio corrections of [e]. Each item takes its new value; the timeline spans behind it change by
 * the same amount, so the totals, the excess rest, the paused time and the end of the session follow. Nothing is
 * reordered. With no change, the same state is returned.
 */
internal fun WorkoutState.withCorrectedTimes(e: PastSessionEdit, now: ClockSnapshot): WorkoutState {
    val spans = timing?.segments?.toMutableList()
    var timesChanged = false
    fun requireTimeline(): MutableList<TimedSegment> =
        spans ?: throw WorkoutEditException("Esta sesión no registró tiempos por serie.")

    // Pauses first, by position: later insertions must not move them.
    if (e.pauseMillis.isNotEmpty()) {
        val list = requireTimeline()
        val positions = list.indices.filter { list[it].focus.activity == TimedActivity.PAUSE }
        e.pauseMillis.forEach { (ordinal, millis) ->
            val at = positions.getOrNull(ordinal) ?: throw WorkoutEditException("La pausa no está registrada.")
            checkMillis(millis, "Pausa")
            if (list[at].durationMillis != millis) { list[at] = list[at].copy(durationMillis = millis); timesChanged = true }
        }
    }

    e.setMillis.forEach { (index, millis) ->
        if (index !in records.indices) throw WorkoutEditException("La serie no está registrada.")
        checkMillis(millis, "Duración de la serie")
        val list = requireTimeline()
        val current = list.filter { it.focus.activity == TimedActivity.SET && it.focus.setIndex == index }.sumOf { it.durationMillis }
        if (millis != current || list.none { it.focus.activity == TimedActivity.SET && it.focus.setIndex == index }) {
            list.shiftGroup(millis - current, focusFor(TimedActivity.SET, index), anchorAtOrBefore(index)) {
                it.activity == TimedActivity.SET && it.setIndex == index
            }
            timesChanged = true
        }
    }

    var rests = restSegments
    val restDeltas = mutableMapOf<Int, Long>()
    e.rests.forEach { edit ->
        val old = rests.firstOrNull { it.id == edit.id } ?: throw WorkoutEditException("El descanso no está registrado.")
        if (edit.objectiveSeconds !in 0..MAX_REST_OBJECTIVE_SECONDS)
            throw WorkoutEditException("Objetivo de descanso fuera de rango (máximo $MAX_REST_OBJECTIVE_SECONDS s).")
        checkMillis(edit.realMillis, "Descanso real")
        val updated = old.copy(configuredSeconds = edit.objectiveSeconds, measuredMillis = edit.realMillis)
        if (updated == old) return@forEach
        rests = rests.map { if (it.id == old.id) updated else it }
        timesChanged = true
        val delta = (edit.realMillis ?: 0) - (old.measuredMillis ?: 0)
        setIndexOf(old.afterStepId)?.takeIf { delta != 0L }?.let { restDeltas[it] = (restDeltas[it] ?: 0) + delta }
    }
    if (spans != null) restDeltas.forEach { (index, delta) ->
        spans.shiftGroup(delta, focusFor(TimedActivity.REST, index), { it.setIndex == index }) {
            it.activity == TimedActivity.REST && it.setIndex == index
        }
    }

    var waits = machineWaits
    val waitDeltas = mutableMapOf<String, Long>()
    e.waitMillis.forEach { (id, millis) ->
        val old = waits.firstOrNull { it.id == id && !it.cancelled && it.measuredMillis != null }
            ?: throw WorkoutEditException("La espera por máquina no está registrada.")
        checkMillis(millis, "Espera por máquina")
        if (old.measuredMillis == millis) return@forEach
        waits = waits.map { if (it.id == id) it.copy(measuredMillis = millis) else it }
        timesChanged = true
        waitDeltas[old.afterStepId] = (waitDeltas[old.afterStepId] ?: 0) + millis - old.measuredMillis!!
    }
    if (spans != null) waitDeltas.forEach { (key, delta) ->
        val index = setIndexOf(key)
        val focus = index?.let { focusFor(TimedActivity.MACHINE_WAIT, it) } ?: cardioFocus(TimedActivity.MACHINE_WAIT, key)
        spans.shiftGroup(delta, focus, { it.key() == key }) { it.activity == TimedActivity.MACHINE_WAIT && it.key() == key }
    }

    var cardio = cardio
    e.cardio.forEach { entry ->
        val old = cardio.firstOrNull { it.id == entry.id } ?: throw WorkoutEditException("El cardio no está registrado.")
        val step = old.plannedCardioId?.let { id -> cardioPlan.firstOrNull { it.stepId == id } }
        if (old.plannedCardioId != null && entry.durationSeconds == null)
            throw WorkoutEditException("El cardio planificado necesita su duración.")
        val updated = old.copy(modality = entry.modality.trim(), durationSeconds = entry.durationSeconds,
            distanceTenths = entry.distanceTenths, distanceUnit = entry.distanceUnit,
            notes = entry.notes?.trim()?.takeIf { it.isNotEmpty() },
            partial = step?.let { (entry.durationSeconds ?: 0) < it.plannedSeconds } ?: old.partial)
        validateCardio(updated)
        if (updated == old) return@forEach
        cardio = cardio.map { if (it.id == old.id) updated.copy(editedAtMillis = now.wallMillis) else it }
        val delta = ((updated.durationSeconds ?: 0) - (old.durationSeconds ?: 0)) * 1000L
        if (spans != null && old.plannedCardioId != null && delta != 0L) {
            val stepId = old.plannedCardioId
            spans.shiftGroup(delta, cardioFocus(TimedActivity.CARDIO, stepId), { it.stepId == stepId && it.setIndex == null }) {
                it.activity == TimedActivity.CARDIO && it.stepId == stepId
            }
            timesChanged = true
        }
    }

    var end = endedAtMillis
    var paused = pausedMillis
    if (spans != null) {
        val before = timing!!.segments
        end = end?.plus(spans.sumOf { it.durationMillis } - before.sumOf { it.durationMillis })
        paused = (paused + spans.pauseTotal() - before.pauseTotal()).coerceAtLeast(0)
    }
    if (e.endMillis != null && e.endMillis != endedAtMillis) {
        if (spans != null) throw WorkoutEditException("El fin se calcula a partir de los tiempos de la sesión.")
        val start = startedAtMillis ?: throw WorkoutEditException("Indica primero la fecha de inicio.")
        if (e.endMillis < start || e.endMillis - start > MAX_EDITED_MILLIS)
            throw WorkoutEditException("El fin debe ser posterior al inicio y dentro de las 24 h siguientes.")
        end = e.endMillis
        timesChanged = true
    }

    if (!timesChanged && cardio == this.cardio) return this
    return copy(timing = spans?.let { timing!!.copy(segments = it) }, restSegments = rests, machineWaits = waits,
        cardio = cardio, endedAtMillis = end, pausedMillis = paused,
        timesEditedAtMillis = if (timesChanged) now.wallMillis else timesEditedAtMillis, revision = revision + 1)
}

private fun List<TimedSegment>.pauseTotal(): Long = filter { it.focus.activity == TimedActivity.PAUSE }.sumOf { it.durationMillis }

private fun WorkoutState.focusFor(activity: TimedActivity, index: Int): TimingFocus {
    val set = plan[index]
    return TimingFocus(activity, set.exerciseId ?: set.exercise, set.exercise, index, set.number, set.prescriptionId)
}

private fun WorkoutState.cardioFocus(activity: TimedActivity, stepId: String): TimingFocus =
    TimingFocus(activity, stepId, cardioPlan.firstOrNull { it.stepId == stepId }?.modality ?: "Cardio", stepId = stepId)

private fun anchorAtOrBefore(index: Int): (TimingFocus) -> Boolean = { f -> f.setIndex != null && f.setIndex <= index }

/**
 * Changes the total of the spans matching [member] by [delta]: a gain goes to the last of them, a loss is taken from the
 * last ones first, never below zero. With no such span, a new one is placed after the last span matching [anchor].
 */
private fun MutableList<TimedSegment>.shiftGroup(delta: Long, focus: TimingFocus, anchor: (TimingFocus) -> Boolean,
                                                 member: (TimingFocus) -> Boolean) {
    val members = indices.filter { member(this[it].focus) }
    if (members.isEmpty()) {
        if (delta < 0) return
        val after = indices.lastOrNull { anchor(this[it].focus) }
        add(after?.plus(1) ?: size, TimedSegment(focus, delta))
        return
    }
    if (delta >= 0) { val last = members.last(); this[last] = this[last].copy(durationMillis = this[last].durationMillis + delta); return }
    var remaining = -delta
    for (i in members.asReversed()) {
        if (remaining == 0L) break
        val take = minOf(remaining, this[i].durationMillis)
        this[i] = this[i].copy(durationMillis = this[i].durationMillis - take)
        remaining -= take
    }
}
