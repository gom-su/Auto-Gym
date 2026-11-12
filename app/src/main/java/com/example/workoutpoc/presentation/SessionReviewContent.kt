package com.example.workoutpoc.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.history.*
import com.example.workoutpoc.ui.ContentCard
import com.example.workoutpoc.ui.SectionHeader
import com.example.workoutpoc.ui.SupportingText
import com.example.workoutpoc.ui.theme.Spacing

internal fun measuredTimeLabel(millis: Long): String {
    val seconds = millis / 1000
    return listOfNotNull(
        "${seconds / 3600} h".takeIf { seconds >= 3600 },
        "${seconds / 60 % 60} min".takeIf { seconds >= 60 },
        "${seconds % 60} s"
    ).joinToString(" ")
}

internal fun TimeBreakdown.description(): String =
    "Actividad ${measuredTimeLabel(activityMillis)} · Descanso ${measuredTimeLabel(restMillis)} · Pausa ${measuredTimeLabel(pauseMillis)}"

internal fun TimedSegment.description(): String {
    val activity = when (focus.activity) {
        TimedActivity.SET -> "Serie ${focus.setNumber}"
        TimedActivity.CARDIO -> "Cardio"
        TimedActivity.REST -> "Descanso"
        TimedActivity.PAUSE -> "Pausa"
    }
    return "$activity · ${focus.exercise} · ${measuredTimeLabel(durationMillis)}"
}

internal fun ReviewSet.label(): String {
    val typeLabel = when (type) {
        SetType.WORK -> if (extra) "Serie añadida" else "Serie"
        SetType.WARMUP -> "Calentamiento"
        SetType.APPROACH -> "Serie de aproximación"
    }
    val status = when (recordedStatus) {
        SetStatus.SKIPPED -> " · Omitida"
        null -> " · Sin registro"
        SetStatus.COMPLETED -> ""
    }
    return "$typeLabel $number$status"
}

internal fun ReviewSet.resultLines(): List<String> = listOf(
    "Peso objetivo: ${targetWeight ?: "—"} · Peso real: ${actualWeight ?: "—"}",
    "RIR objetivo: ${rirLabel(targetRir)} · RIR real: ${rirLabel(actualRir)}",
    durationMillis?.let { "Duración: ${measuredTimeLabel(it)}" } ?: "Sin duración registrada"
)

internal fun CardioEntry.reviewDescription(): String =
    "$modality · ${durationSeconds?.let { measuredTimeLabel(it * 1000L) } ?: "Sin duración registrada"}" +
        if (partial) " · Parcial" else ""

internal fun SessionReview.overlayText(): String = buildString {
    val breakdown = time
    if (breakdown == null) appendLine("Sin duración registrada")
    else {
        appendLine("Total real · ${measuredTimeLabel(breakdown.totalMillis)}")
        appendLine(breakdown.description())
    }
    exercises.forEach { exercise ->
        appendLine()
        appendLine(exercise.name)
        exercise.time?.let { appendLine("Total ${measuredTimeLabel(it.totalMillis)} · ${it.description()}") }
        exercise.sets.forEach { set ->
            appendLine(set.label())
            set.resultLines().forEach { appendLine(it) }
        }
    }
    cardio.forEach { appendLine(it.reviewDescription()) }
    if (timeline.isNotEmpty()) {
        appendLine()
        appendLine("Secuencia real")
        timeline.forEach { appendLine(it.description()) }
    }
}.trimEnd()

@Composable
internal fun SessionReviewContent(review: SessionReview) {
    review.time?.let { time ->
        ContentCard {
            Text("Total real · ${measuredTimeLabel(time.totalMillis)}", style = MaterialTheme.typography.titleMedium)
            SupportingText(time.description())
        }
    } ?: SupportingText("Sin duración registrada")
    review.exercises.forEach { exercise ->
        ContentCard {
            Text(exercise.name, style = MaterialTheme.typography.titleMedium)
            exercise.time?.let { SupportingText("Total ${measuredTimeLabel(it.totalMillis)} · ${it.description()}") }
            exercise.sets.forEach { set ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(set.label(), style = MaterialTheme.typography.titleSmall)
                    set.resultLines().forEach { SupportingText(it) }
                }
            }
        }
    }
    if (review.cardio.isNotEmpty()) ContentCard {
        SectionHeader("Cardio registrado")
        review.cardio.forEach { Text(it.reviewDescription(), style = MaterialTheme.typography.bodyMedium) }
    }
    if (review.timeline.isNotEmpty()) ContentCard {
        SectionHeader("Secuencia real")
        review.timeline.forEach { Text(it.description(), style = MaterialTheme.typography.bodyMedium) }
    }
}
