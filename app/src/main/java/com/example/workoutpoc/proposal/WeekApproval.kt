package com.example.workoutpoc.proposal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.workoutpoc.domain.SessionComment
import com.example.workoutpoc.domain.rirLabel
import com.example.workoutpoc.planning.DEFAULT_REST_SECONDS
import com.example.workoutpoc.planning.ExercisePrescription
import com.example.workoutpoc.planning.LoadBasis
import com.example.workoutpoc.planning.LoadUnit
import com.example.workoutpoc.planning.PrescribedLoad
import com.example.workoutpoc.planning.SessionPlan
import com.example.workoutpoc.planning.SetKind
import com.example.workoutpoc.planning.SetPrescription
import com.example.workoutpoc.planning.WeekPlan
import com.example.workoutpoc.ui.ContentCard
import com.example.workoutpoc.ui.SectionHeader
import com.example.workoutpoc.ui.SupportingText
import com.example.workoutpoc.ui.theme.Spacing

/** One set of the week as shown for approval, with the comments that point at it. */
data class ReviewedSet(val number: Int, val kind: SetKind, val summary: String, val comments: List<String>)

data class ReviewedExercise(
    val name: String, val sets: List<ReviewedSet>, val comments: List<String>, val explanations: List<String>
)

data class ReviewedDay(
    val id: String, val name: String, val cardio: String?, val comments: List<String>, val exercises: List<ReviewedExercise>
)

/** The whole week in plan order (requirement 2). Week-level comments are those that name no day. */
data class ReviewedWeek(val name: String, val generalComments: List<String>, val days: List<ReviewedDay>)

/**
 * Builds the approval view of a proposed week. Comments are anchored by plan day, exercise and set position, so an
 * exercise that appears twice shows a comment only with the occurrence it refers to. Agent explanations are attached
 * to the first day where their exercise appears, once.
 */
fun buildReviewedWeek(week: WeekPlan, programId: String, comments: List<SessionComment>,
                      explanations: List<ExplainedChange>): ReviewedWeek {
    val inForce = comments.filter { it.programId == programId && it.resolvedAtMillis == null }
    val placed = mutableSetOf<String>()
    val attached = mutableSetOf<ExplainedChange>()
    val general = inForce.filter { it.dayId == null && it.exerciseId == null && it.setIndex == null }
    placed += general.map { it.id }
    val days = week.days.map { day -> reviewDay(day, inForce, explanations, placed, attached) }
    // Anything not shown under a day, exercise or set is kept at week level: a day that the proposal no longer has,
    // an exercise without a day, or a comment written before days existed. None is silently dropped.
    val dayNames = week.days.associate { it.id to it.name }
    // Show the exercise by the name the proposal uses, falling back to its identifier when the week no longer has it.
    val exerciseNames = week.days.flatMap { it.exercises }.associate { it.exerciseId to it.name }
    val rest = inForce.filter { it.id !in placed }.map { c ->
        val where = c.dayId?.let { id ->
            dayNames[id]?.let { "día $it" } ?: "día $id (no está en esta propuesta)" }
        val target = c.exerciseId?.let { "${exerciseNames[it] ?: it}: " } ?: ""
        (where?.let { "[$it] " } ?: "") + target + c.text
    }
    return ReviewedWeek(week.name, general.map { it.text } + rest, days)
}

private fun reviewDay(day: SessionPlan, comments: List<SessionComment>, explanations: List<ExplainedChange>,
                      placed: MutableSet<String>, attached: MutableSet<ExplainedChange>): ReviewedDay {
    val dayMatches = comments.filter { it.dayId == day.id && it.exerciseId == null && it.setIndex == null }
    placed += dayMatches.map { it.id }
    val exercises = day.exercises.map { ex -> reviewExercise(day, ex, comments, explanations, placed, attached) }
    return ReviewedDay(day.id, day.name, day.cardio?.let { cardioLabel(it.modality, it.durationSeconds) },
        dayMatches.map { it.text }, exercises)
}

private fun reviewExercise(day: SessionPlan, ex: ExercisePrescription, comments: List<SessionComment>,
                           explanations: List<ExplainedChange>, placed: MutableSet<String>,
                           attached: MutableSet<ExplainedChange>): ReviewedExercise {
    val exMatches = comments.filter { it.dayId == day.id && it.exerciseId == ex.exerciseId && it.setIndex == null }
    placed += exMatches.map { it.id }
    // Each explanation is shown once, at its first occurrence; identical explanations are the same item. The changes
    // are computed for the whole week, so they are labelled as such rather than attributed to this day.
    val exExplanations = explanations.filter { it.exerciseId == ex.exerciseId && attached.add(it) }
        .map { "Cambio de toda la semana · ${it.kind.label}: ${it.summary} Motivo: ${it.reason ?: "no indicado"}. Evidencia: ${it.evidence}" }
    val sets = ex.sets.mapIndexed { index, set ->
        val matches = comments.filter { it.dayId == day.id && it.exerciseId == ex.exerciseId && it.setIndex == index }
        placed += matches.map { it.id }
        ReviewedSet(index + 1, set.kind, setSummary(set), matches.map { it.text })
    }
    return ReviewedExercise(ex.name, sets, exMatches.map { it.text }, exExplanations)
}

/** Plain-language set line: kind, load with unit and basis, reps, RIR (work sets only) and rest. */
internal fun setSummary(set: SetPrescription): String {
    val kind = when (set.kind) { SetKind.WORK -> "Trabajo"; SetKind.WARMUP -> "Calentamiento"; SetKind.APPROACH -> "Aproximación" }
    val load = set.load?.let { prescribedLoadLabel(it) } ?: "carga sin indicar"
    val reps = set.reps?.let { "$it reps" } ?: "reps sin indicar"
    val rir = if (set.kind == SetKind.WORK) " · RIR ${set.rir?.let { rirLabel(it) } ?: "sin indicar"}" else ""
    return "$kind · $load · $reps$rir · descanso ${set.restSeconds ?: DEFAULT_REST_SECONDS} s"
}

internal fun prescribedLoadLabel(load: PrescribedLoad): String {
    val value = if (load.value == Math.floor(load.value)) load.value.toLong().toString() else "%.1f".format(load.value)
    val unit = when (load.unit) {
        LoadUnit.KG -> "kg"; LoadUnit.LB -> "lb"; LoadUnit.MACHINE_SETTING -> "posición"; LoadUnit.BODYWEIGHT -> "peso corporal"
    }
    val basis = when (load.basis) {
        LoadBasis.TOTAL -> "total"; LoadBasis.PER_SIDE -> "por lado"; LoadBasis.PER_HAND -> "por mano"
        LoadBasis.BODYWEIGHT -> "peso corporal"; LoadBasis.MACHINE_SETTING -> "posición"
    }
    return "$value $unit (base $basis)"
}

internal fun cardioLabel(modality: String, seconds: Int): String =
    "Cardio · $modality · " + if (seconds % 60 == 0) "${seconds / 60} min" else "$seconds s"

@Composable
fun WeekApprovalSection(week: ReviewedWeek) {
    ContentCard {
        SectionHeader("Semana completa")
        if (week.generalComments.isNotEmpty()) {
            Text("Comentarios generales", style = MaterialTheme.typography.titleSmall)
            week.generalComments.forEach { SupportingText("• $it") }
        }
    }
    week.days.forEach { day ->
        ContentCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(day.name, style = MaterialTheme.typography.titleMedium)
                day.comments.forEach { SupportingText("Comentario del día: $it") }
                day.exercises.forEach { ex ->
                    Text(ex.name, style = MaterialTheme.typography.titleSmall)
                    ex.comments.forEach { SupportingText("Comentario: $it") }
                    ex.explanations.forEach { SupportingText("Agente: $it") }
                    ex.sets.forEach { set ->
                        Text("Serie ${set.number} · ${set.summary}", style = MaterialTheme.typography.bodyMedium)
                        set.comments.forEach { SupportingText("Comentario de la serie: $it") }
                    }
                }
                day.cardio?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
