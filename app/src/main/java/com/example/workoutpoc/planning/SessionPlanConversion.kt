package com.example.workoutpoc.planning

import com.example.workoutpoc.domain.PlannedSet
import com.example.workoutpoc.domain.PlannedCardio
import com.example.workoutpoc.data.WorkoutRepository
import com.example.workoutpoc.domain.SessionSource
import com.example.workoutpoc.domain.WeightUnit
import java.util.UUID
import kotlin.math.roundToInt

/** Rest used only for the timer when a prescription omits it; it is shown as a default, never exported as prescribed. */
const val DEFAULT_REST_SECONDS = 90

/** A planned day located in the library by identity, with display names snapshotted for the session. */
data class DaySelection(val program: TrainingProgram, val block: TrainingBlock, val week: WeekPlan, val day: SessionPlan) {
    val source get() = SessionSource(program.id, program.name, block.id, week.id, week.name, week.version, day.id, day.name)
    val previousWeekId: String?
        get() = program.blocks.flatMap { it.weeks }.let { weeks ->
            weeks.getOrNull(weeks.indexOfFirst { it.id == week.id } - 1)?.id
        }
}

fun TrainingProgram.findDay(weekId: String, dayId: String): DaySelection? {
    for (block in blocks) for (week in block.weeks) if (week.id == weekId)
        week.days.find { it.id == dayId }?.let { return DaySelection(this, block, week, it) }
    return null
}

suspend fun WorkoutRepository.loadDay(selection: DaySelection) = load(
    plan = selection.day.toPlannedSets(),
    source = selection.source,
    previousWeekId = selection.previousWeekId,
    cardioPlan = listOfNotNull(selection.day.toPlannedCardio())
)

fun SessionPlan.toPlannedCardio(): PlannedCardio? {
    val prescription = cardio ?: return null
    val strengthIds = exercises.flatMap { exercise -> exercise.sets.map { it.id } }.toSet()
    val prefix = "cardio:${UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8))}"
    val stepId = generateSequence(0) { it + 1 }
        .map { suffix -> if (suffix == 0) prefix else "$prefix:$suffix" }
        .first { it !in strengthIds }
    return PlannedCardio(stepId, prescription.modality.trim(), prescription.durationSeconds)
}

/**
 * Flattens a planned day into ordered executable sets. Prefills come only from the prescription:
 * missing reps prefill 0 (the user must enter them), missing RIR stays unrecorded, and a missing load
 * starts at 0 kg flagged as not prescribed. Prescription IDs keep each record traceable to its plan.
 */
fun SessionPlan.toPlannedSets(): List<PlannedSet> = exercises.flatMap { exercise ->
    exercise.sets.mapIndexed { index, set ->
        val load = set.load
        val unit = when (load?.unit) {
            LoadUnit.LB -> WeightUnit.LB
            LoadUnit.MACHINE_SETTING -> WeightUnit.MACHINE_SETTING
            LoadUnit.BODYWEIGHT -> WeightUnit.BODYWEIGHT
            LoadUnit.KG, null -> WeightUnit.KG
        }
        // Bodyweight prescriptions carry no external load to prefill.
        val tenths = if (load == null || unit == WeightUnit.BODYWEIGHT) 0 else (load.value * 10).roundToInt()
        val step = when (unit) { WeightUnit.KG, WeightUnit.BODYWEIGHT -> 25; WeightUnit.LB -> 50; WeightUnit.MACHINE_SETTING -> 10 }
        val unitMax = when (unit) { WeightUnit.KG -> 5000; WeightUnit.LB -> 10000; WeightUnit.MACHINE_SETTING, WeightUnit.BODYWEIGHT -> 2000 }
        val reps = set.reps ?: 0
        PlannedSet(
            exercise = exercise.name, number = index + 1, total = exercise.sets.size,
            weightTenths = tenths, reps = reps, rir = set.rir?.coerceAtMost(4),
            prescriptionId = set.id, exerciseId = exercise.exerciseId,
            unit = unit, basis = load?.basis?.name, loadPrescribed = load != null,
            restSeconds = set.restSeconds ?: DEFAULT_REST_SECONDS, weightStepTenths = step,
            maxWeightTenths = maxOf(unitMax, tenths), maxReps = maxOf(100, reps),
            // Warm-up and approach kinds never feed RIR or load preselection of work sets (requirements 6 and 13).
            setType = when (set.kind) {
                SetKind.WORK -> com.example.workoutpoc.domain.SetType.WORK
                SetKind.WARMUP -> com.example.workoutpoc.domain.SetType.WARMUP
                SetKind.APPROACH -> com.example.workoutpoc.domain.SetType.APPROACH
            }
        )
    }
}
