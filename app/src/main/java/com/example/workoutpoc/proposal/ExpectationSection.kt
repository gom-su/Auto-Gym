package com.example.workoutpoc.proposal

import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.Spacing
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.catalog.ChartMetric
import com.example.workoutpoc.exchange.WeekProposal
import java.util.UUID

/** Expected results of an approved recommendation and their evaluation (AG-04). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpectationSection(importId: String, proposal: WeekProposal, expectations: List<Expectation>, results: Map<String, EvaluationResult>,
                       enabled: Boolean, onAdd: (Expectation) -> Unit, onManual: (String, Outcome?, String?) -> Unit) {
    val exercises = proposal.week.days.flatMap { it.exercises }.distinctBy { it.exerciseId }
    var exerciseId by rememberSaveable(importId) { mutableStateOf(exercises.firstOrNull()?.exerciseId) }
    var metric by rememberSaveable(importId) { mutableStateOf(ChartMetric.TOP_LOAD) }
    var direction by rememberSaveable(importId) { mutableStateOf(ExpectedDirection.INCREASE) }
    var days by rememberSaveable(importId) { mutableStateOf("28") }
    val parsedDays = days.trim().toIntOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader("Evaluación posterior",
            supporting = "Define qué debería lograr la recomendación y en cuánto tiempo. Se contrasta con la última exposición previa a la decisión, sin semanas de descarga; mientras la ventana siga abierta queda pendiente y, sin datos suficientes, inconclusa.")
        if (expectations.isEmpty()) EmptyState("Sin resultados esperados registrados.", icon = AppIcons.Target)
        expectations.forEach { e ->
            val r = results[e.id]
            val outcome = r?.outcome ?: Outcome.PENDING
            ContentCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("${e.exerciseName}: ${e.direction.label} ${e.metric.label.lowercase()} en ${e.windowDays} días", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    StatusPill(outcome.label, when (outcome) { Outcome.FAVORABLE -> Tone.Success; Outcome.UNFAVORABLE -> Tone.Error; else -> Tone.Info })
                }
                Text("Resultado: ${r?.outcome?.label ?: Outcome.PENDING.label}" + if (r?.manual == true) " (manual)" else " (automático)",
                    style = MaterialTheme.typography.bodyMedium)
                r?.let { Text(it.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                ChipGroup("Resultado manual") {
                    ChoiceChip(e.manualOutcome == null, { onManual(e.id, null, null) }, { Text("Automático") }, enabled = enabled)
                    listOf(Outcome.FAVORABLE, Outcome.UNFAVORABLE, Outcome.INCONCLUSIVE).forEach { o ->
                        ChoiceChip(e.manualOutcome == o, { onManual(e.id, o, e.note) }, { Text(o.label) }, enabled = enabled)
                    }
                }
            }
        }
        ContentCard {
            Text("Nuevo resultado esperado", style = MaterialTheme.typography.titleMedium)
            ChipGroup("Ejercicio") {
                exercises.forEach { ex -> ChoiceChip(exerciseId == ex.exerciseId, { exerciseId = ex.exerciseId }, { Text(ex.name) }) }
            }
            ChipGroup("Métrica") {
                listOf(ChartMetric.TOP_LOAD, ChartMetric.TOTAL_REPS, ChartMetric.VOLUME).forEach { m -> ChoiceChip(metric == m, { metric = m }, { Text(m.label) }) }
            }
            ChipGroup("Dirección esperada") {
                ExpectedDirection.entries.forEach { d -> ChoiceChip(direction == d, { direction = d }, { Text(d.label.replaceFirstChar { it.uppercase() }) }) }
            }
            OutlinedTextField(days, { days = it.take(2) }, label = { Text("Ventana (días, 1–84)") }, singleLine = true, isError = parsedDays !in 1..84,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            PrimaryAction("Guardar resultado esperado", {
                val ex = exercises.first { it.exerciseId == exerciseId }
                onAdd(Expectation(UUID.randomUUID().toString(), importId, ex.exerciseId, ex.name, metric, direction, parsedDays!!, 1L))
            }, enabled = enabled && exerciseId != null && parsedDays in 1..84, icon = AppIcons.Check)
        }
    }
}
