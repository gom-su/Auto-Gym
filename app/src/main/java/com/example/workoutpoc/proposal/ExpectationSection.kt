package com.example.workoutpoc.proposal

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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Evaluación posterior", style = MaterialTheme.typography.titleLarge)
        Text("Define qué debería lograr la recomendación y en cuánto tiempo. Se contrasta con la última exposición previa a la decisión, sin semanas de descarga; mientras la ventana siga abierta queda pendiente y, sin datos suficientes, inconclusa.",
            style = MaterialTheme.typography.bodySmall)
        if (expectations.isEmpty()) Text("Sin resultados esperados registrados.")
        expectations.forEach { e ->
            val r = results[e.id]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${e.exerciseName}: ${e.direction.label} ${e.metric.label.lowercase()} en ${e.windowDays} días", style = MaterialTheme.typography.titleSmall)
                    Text("Resultado: ${r?.outcome?.label ?: Outcome.PENDING.label}" + if (r?.manual == true) " (manual)" else " (automático)",
                        style = MaterialTheme.typography.titleSmall)
                    r?.let { Text(it.detail, style = MaterialTheme.typography.bodySmall) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(e.manualOutcome == null, { onManual(e.id, null, null) }, { Text("Automático") }, enabled = enabled)
                        listOf(Outcome.FAVORABLE, Outcome.UNFAVORABLE, Outcome.INCONCLUSIVE).forEach { o ->
                            FilterChip(e.manualOutcome == o, { onManual(e.id, o, e.note) }, { Text(o.label) }, enabled = enabled)
                        }
                    }
                }
            }
        }
        Text("Nuevo resultado esperado", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            exercises.forEach { ex -> FilterChip(exerciseId == ex.exerciseId, { exerciseId = ex.exerciseId }, { Text(ex.name) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(ChartMetric.TOP_LOAD, ChartMetric.TOTAL_REPS, ChartMetric.VOLUME).forEach { m -> FilterChip(metric == m, { metric = m }, { Text(m.label) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExpectedDirection.entries.forEach { d -> FilterChip(direction == d, { direction = d }, { Text(d.label.replaceFirstChar { it.uppercase() }) }) }
        }
        OutlinedTextField(days, { days = it.take(2) }, label = { Text("Ventana (días, 1–84)") }, singleLine = true, isError = parsedDays !in 1..84)
        Button(enabled = enabled && exerciseId != null && parsedDays in 1..84, onClick = {
            val ex = exercises.first { it.exerciseId == exerciseId }
            onAdd(Expectation(UUID.randomUUID().toString(), importId, ex.exerciseId, ex.name, metric, direction, parsedDays!!, 1L))
        }) { Text("Guardar resultado esperado") }
    }
}
