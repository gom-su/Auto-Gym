package com.example.workoutpoc.domain

import kotlinx.serialization.Serializable

@Serializable
enum class Phase { Idle, ActiveSet, Resting, Completed, ActiveCardio }

val Phase.inProgress: Boolean get() = this == Phase.ActiveSet || this == Phase.Resting || this == Phase.ActiveCardio

/** Recording unit. Values are integer tenths of this unit; BODYWEIGHT tenths are added external load in kg. */
@Serializable
enum class WeightUnit { KG, LB, MACHINE_SETTING, BODYWEIGHT }

/**
 * One executable set. weightTenths/reps/rir are the prefilled values derived from the prescription;
 * reps = 0 and rir = null mean the prescription did not state them (never invented).
 * loadPrescribed = false means no load was prescribed: the prefilled 0 is a starting point, not a target.
 */
@Serializable
data class PlannedSet(
    val exercise: String, val number: Int, val total: Int,
    val weightTenths: Int, val reps: Int, val rir: Int?,
    val prescriptionId: String? = null, val exerciseId: String? = null,
    val unit: WeightUnit = WeightUnit.KG, val basis: String? = null, val loadPrescribed: Boolean = true,
    val restSeconds: Int = 10, val weightStepTenths: Int = 25, val maxWeightTenths: Int = 3000, val maxReps: Int = 30,
    /** Added during the session; it has no prescription of its own and is never reported as prescribed. */
    val extra: Boolean = false,
    /** A warm-up/approach set (always extra). Its load never carries into work sets. Legacy; [setType] wins when both are set. */
    val warmup: Boolean = false,
    /** Set kind. Legacy snapshots read warmup = true as APPROACH. */
    val setType: SetType = if (warmup) SetType.APPROACH else SetType.WORK
)

/** Kind of a strength set (contract §3). WARMUP and APPROACH never feed RIR or load preselection of work sets. */
@Serializable
enum class SetType { WORK, WARMUP, APPROACH }

/** Integer tenths of the set's unit avoid floating point drift; rir=4 means 4+, null means not recorded. */
@Serializable
data class SetValues(val weightTenths: Int = 300, val reps: Int = 8, val rir: Int? = 2)

@Serializable
enum class SetStatus { COMPLETED, SKIPPED }

/**
 * What was actually done for one plan position. The prescription stays in the session plan; edits change only
 * these recorded values and are marked with editedAtMillis. Every edit keeps the replaced version in [revisions]
 * (oldest first), so the full audit trail survives. A SKIPPED set's values are not performance data.
 */
@Serializable
data class SetRecord(val index: Int, val exercise: String, val number: Int,
                     val values: SetValues, val completedAtMillis: Long, val prescriptionId: String? = null,
                     val status: SetStatus = SetStatus.COMPLETED, val notes: String? = null, val editedAtMillis: Long? = null,
                     val revisions: List<SetRevision> = emptyList(),
                     /** Unit the values were recorded in; null means the prescribed unit (contract §2). */
                     val recordedUnit: WeightUnit? = null)

/** A recorded version that an edit replaced, with the time it was replaced. */
@Serializable
data class SetRevision(val status: SetStatus, val values: SetValues, val notes: String?, val replacedAtMillis: Long,
                       val recordedUnit: WeightUnit? = null)

const val MAX_SET_REVISIONS = 50

const val MAX_NOTE_LENGTH = 1000

/** Durable monotonic deadline, with wall time fallback only after a reboot. */
@Serializable
data class RestDeadline(val elapsedMillis: Long, val wallMillis: Long, val bootCount: Int)

@Serializable
enum class DistanceUnit { M, KM, MI }

/** Cardio performed in the session. Only what was entered is stored; nothing is derived or completed by default. */
@Serializable
data class CardioEntry(
    val id: String, val modality: String, val durationSeconds: Int?, val distanceTenths: Int?,
    val distanceUnit: DistanceUnit?, val notes: String?, val recordedAtMillis: Long,
    /** Planned cardio step this entry executed (contract §6); null for manual cardio. */
    val plannedCardioId: String? = null,
    val partial: Boolean = false
)

/** Executable cardio step of a planned session, run by duration (contract §6). */
@Serializable
data class PlannedCardio(
    val stepId: String, val modality: String, val plannedSeconds: Int,
    /** The stable prescription ID of the following strength set; null places this step after strength. */
    val beforeSetId: String? = null
)

@Serializable
data class CardioRun(val stepId: String, val started: PauseMark, val pausedMillis: Long = 0) {
    fun activeMillis(now: ClockSnapshot, pause: PauseMark?): Long {
        val end = pause?.let { ClockSnapshot(it.elapsedMillis, it.wallMillis, it.bootCount) } ?: now
        return (started.elapsedSince(end) - pausedMillis).coerceAtLeast(0)
    }
}

/**
 * Rest between two steps, kept outside [SetRecord] (contract §5). Actual rest is
 * confirmedAt - startedAt - pausedMillis; it is open (confirmedAtMillis == null) until OK or Skip.
 */
@Serializable
data class RestSegment(
    val id: String, val afterStepId: String,
    val startedAtMillis: Long, val bootCount: Int,
    /** Adjustable with -15 s / +15 s inside 45–120 s; the elapsed time is never reset by adjusting. */
    val configuredSeconds: Int,
    val pausedMillis: Long = 0,
    val confirmedAtMillis: Long? = null,
    val skipped: Boolean = false,
    val startedAtElapsedMillis: Long? = null,
    val measuredMillis: Long? = null,
    val interrupted: Boolean = false
) {
    val actualRestSeconds: Long? get() = measuredMillis?.div(1000)

    fun elapsedMillis(now: ClockSnapshot): Long {
        val elapsed = if (bootCount == now.bootCount && startedAtElapsedMillis != null)
            now.elapsedMillis - startedAtElapsedMillis else now.wallMillis - startedAtMillis
        return (elapsed - pausedMillis).coerceAtLeast(0)
    }
}

/** Session or exercise comment that outlives the week it was written in (contract §7). */
@Serializable
data class SessionComment(
    val id: String,
    val text: String,
    val createdAtMillis: Long,
    val programId: String,
    /** Origin week only; not the key for relevance. */
    val weekId: String? = null,
    val sessionId: String? = null,
    /** null = general session comment. */
    val exerciseId: String? = null,
    val setIndex: Int? = null,
    /** null = still in force. */
    val resolvedAtMillis: Long? = null
)

/** Start of a pause, on both clocks so a reboot during the pause still resolves its duration. */
@Serializable
data class PauseMark(val elapsedMillis: Long, val wallMillis: Long, val bootCount: Int)

/** Where a planned session came from. Names are display snapshots; IDs are the identity. */
@Serializable
data class SessionSource(
    val programId: String, val programName: String, val blockId: String,
    val weekId: String, val weekName: String, val weekVersion: Int,
    val dayId: String, val dayName: String
)

@Serializable
data class PreviousWorkSet(
    val weightTenths: Int, val unit: WeightUnit, val basis: String?, val reps: Int, val rir: Int?
)

@Serializable
data class WorkoutState(
    val sessionId: String = "",
    val phase: Phase = Phase.Idle,
    val index: Int = 0,
    val revision: Long = 0,
    val values: SetValues = SetValues(),
    val records: List<SetRecord> = emptyList(),
    val rest: RestDeadline? = null,
    val endedEarly: Boolean = false,
    /** Empty means the built-in demo plan, which keeps pre-ENT-04 snapshots readable. */
    val plan: List<PlannedSet> = emptyList(),
    val source: SessionSource? = null,
    val startedAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
    val cardio: List<CardioEntry> = emptyList(),
    /** Non-null while paused; rest deadlines are shifted by the pause length on resume. */
    val paused: PauseMark? = null,
    val pausedMillis: Long = 0,
    /** Planned cardio steps, in the same single sequence as the strength plan (contract §6). */
    val cardioPlan: List<PlannedCardio> = emptyList(),
    /** Rest segments (contract §5). The legacy [rest] deadline remains until the reducer migrates. */
    val restSegments: List<RestSegment> = emptyList(),
    val previousWorkSets: Map<String, PreviousWorkSet> = emptyMap(),
    val cardioRun: CardioRun? = null,
    val timing: SessionTiming? = null
)

enum class WorkoutEvent {
    StartWorkout, IncrementWeight, DecrementWeight, IncrementReps, DecrementReps,
    SelectRir, CompleteSet, AddRestTime, SkipRest, RestFinished, UndoLastSet, ResetDemo,
    FinishWorkout,
    /** Planned sessions only: omit the current set, add an extra set of the last exercise, remove an unrecorded extra. */
    SkipSet, AddSet, RemoveExtraSet,
    /** Planned sessions only (ENT-06). */
    PauseSession, ResumeSession, AddWarmupSet,
    /** Planned sessions only: on an exercise's first set, move all its unrecorded sets after the next exercise. */
    PostponeExercise, ReduceRestTime, ConfirmRest, FinishCardio
}

/** Plan positions [WorkoutState.index, currentEnd) are the current exercise and [currentEnd, nextEnd) the next one. */
data class PostponeTarget(val currentEnd: Int, val nextEnd: Int, val nextExercise: String)

private fun PlannedSet.sameExercise(o: PlannedSet): Boolean = exercise == o.exercise && exerciseId == o.exerciseId

/**
 * Where postponing the current exercise would move it, or null when it is not allowed: only for a running planned
 * session, on the first set of an exercise (nothing of it recorded yet, warm-ups included) and when another exercise follows.
 * Only unrecorded positions move, so recorded sets and their prescriptions are never affected.
 */
fun WorkoutState.postponeTarget(plan: List<PlannedSet>): PostponeTarget? {
    if (source == null || phase != Phase.ActiveSet || paused != null || index !in plan.indices || index != records.size) return null
    val current = plan[index]
    if (index > 0 && plan[index - 1].sameExercise(current)) return null
    var currentEnd = index + 1
    while (currentEnd < plan.size && plan[currentEnd].sameExercise(current)) currentEnd++
    if (currentEnd == plan.size) return null
    val next = plan[currentEnd]
    var nextEnd = currentEnd + 1
    while (nextEnd < plan.size && plan[nextEnd].sameExercise(next)) nextEnd++
    return PostponeTarget(currentEnd, nextEnd, next.exercise)
}

/** Every command targets an exact snapshot. Old notification/overlay taps become no-ops. */
data class WorkoutCommand(val event: WorkoutEvent, val sessionId: String,
                          val index: Int, val revision: Long, val rir: Int? = null, val restSegmentId: String? = null)

data class ClockSnapshot(val elapsedMillis: Long, val wallMillis: Long, val bootCount: Int)

fun WorkoutState.command(event: WorkoutEvent, rir: Int? = null): WorkoutCommand =
    WorkoutCommand(event, sessionId, index, revision, rir, openRestSegment?.id)

val WorkoutState.openRestSegment: RestSegment?
    get() = restSegments.lastOrNull()?.takeIf { it.confirmedAtMillis == null && !it.interrupted }

fun WorkoutState.followingSetValues(current: PlannedSet, next: PlannedSet): SetValues {
    val carryLoad = current.setType == SetType.WORK && next.setType == SetType.WORK &&
        current.sameExercise(next) && current.unit == next.unit && current.basis == next.basis &&
        current.weightTenths == next.weightTenths && current.loadPrescribed == next.loadPrescribed
    val weight = if (carryLoad) values.weightTenths.coerceAtMost(next.maxWeightTenths) else next.weightTenths
    return prefillSet(next, weight)
}

internal fun WorkoutState.prefillSet(step: PlannedSet, weight: Int = step.weightTenths): SetValues {
    if (source == null) return SetValues(weight, step.reps, step.rir)
    if (step.setType != SetType.WORK) return SetValues(weight, step.reps, null)
    val previousToday = records.lastOrNull { record ->
        plan.getOrNull(record.index)?.let { previous ->
            val sameExercise = step.exerciseId?.let { it == previous.exerciseId } ?: previous.sameExercise(step)
            previous.setType == SetType.WORK && sameExercise
        } == true
    }
    val previousWeek = step.exerciseId?.let(previousWorkSets::get)
    val rir = if (previousToday != null) previousToday.takeIf { it.status == SetStatus.COMPLETED }?.values?.rir else previousWeek?.takeIf {
        step.loadPrescribed && step.reps > 0 && it.weightTenths == weight && it.unit == step.unit &&
            it.basis == step.basis && it.reps == step.reps
    }?.rir
    return SetValues(weight, step.reps, rir)
}

fun WorkoutState.nextSetLabel(plan: List<PlannedSet>): String {
    nextCardio(plan, index + 1)?.let { return "${it.modality} · ${it.plannedSeconds / 60} min de cardio" }
    val current = plan.getOrNull(index) ?: return ""
    val next = plan.getOrNull(index + 1) ?: return ""
    val prefill = followingSetValues(current, next)
    val basis = when (next.basis) {
        "PER_HAND" -> " por mano"
        "PER_SIDE" -> " por lado"
        "TOTAL" -> " total"
        else -> ""
    }
    val load = if (next.loadPrescribed || prefill.weightTenths != 0) loadLabel(prefill.weightTenths, next.unit) + basis else "carga sin indicar"
    val reps = if (prefill.reps > 0) "${prefill.reps} reps" else "reps sin indicar"
    return "${next.exercise} · ${next.number}/${next.total}\n$load · $reps"
}

val WorkoutState.currentCardio: PlannedCardio?
    get() = cardioRun?.let { run -> cardioPlan.firstOrNull { it.stepId == run.stepId } }

val WorkoutState.plannedCardioComplete: Boolean
    get() = cardioPlan.all { step -> cardio.any { it.plannedCardioId == step.stepId && !it.partial } }

fun WorkoutState.cardioRemainingMillis(now: ClockSnapshot): Long? {
    val step = currentCardio ?: return null
    val elapsed = cardioRun?.activeMillis(now, paused) ?: return null
    return (step.plannedSeconds * 1000L - elapsed).coerceAtLeast(0)
}

fun WorkoutState.nextCardio(plan: List<PlannedSet>, nextSetIndex: Int): PlannedCardio? {
    val nextSet = plan.getOrNull(nextSetIndex)
    return cardioPlan.firstOrNull { step ->
        val inPosition = if (nextSet == null) step.beforeSetId == null else step.beforeSetId != null &&
            step.beforeSetId == nextSet.prescriptionId
        inPosition && cardio.none { it.plannedCardioId == step.stepId }
    }
}

fun weightLabel(tenths: Int): String = if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10},${tenths % 10}"

/** Human label of a recorded/prefilled load in the set's own unit, without conversion. */
fun loadLabel(tenths: Int, unit: WeightUnit): String = when (unit) {
    WeightUnit.KG -> "${weightLabel(tenths)} kg"
    WeightUnit.LB -> "${weightLabel(tenths)} lb"
    WeightUnit.MACHINE_SETTING -> "posición ${weightLabel(tenths)}"
    WeightUnit.BODYWEIGHT -> if (tenths == 0) "peso corporal" else "PC + ${weightLabel(tenths)} kg"
}

fun rirLabel(rir: Int?): String = when (rir) { null -> "—"; 4 -> "4+"; else -> "$rir" }

/** Milliseconds between a mark and now, on the monotonic clock when no reboot happened in between. */
fun PauseMark.elapsedSince(now: ClockSnapshot): Long =
    (if (bootCount == now.bootCount) now.elapsedMillis - elapsedMillis else now.wallMillis - wallMillis).coerceAtLeast(0)

/** Active session time: wall time since start, minus completed and ongoing pauses. */
fun WorkoutState.activeMillis(now: ClockSnapshot): Long? {
    val start = startedAtMillis ?: return null
    val end = endedAtMillis ?: now.wallMillis
    return (end - start - pausedMillis - (paused?.elapsedSince(now) ?: 0)).coerceAtLeast(0)
}

fun RestDeadline.remainingMillis(now: ClockSnapshot): Long =
    ((if (bootCount == now.bootCount) elapsedMillis - now.elapsedMillis else wallMillis - now.wallMillis)).coerceAtLeast(0)
