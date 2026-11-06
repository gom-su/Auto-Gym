package com.example.workoutpoc.proposal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun Severity.label() = when (this) { Severity.OFF -> "desactivada"; Severity.WARNING -> "advertencia"; Severity.BLOCKING -> "bloqueo" }
private fun DecisionStatus?.label() = when (this) { null -> "Pendiente de tu decisión"; DecisionStatus.APPROVED -> "Aprobada"; DecisionStatus.REJECTED -> "Rechazada" }

@Composable
fun ProposalScreen(state: ProposalState, ui: ProposalUiState, onBack: () -> Unit, onRetry: () -> Unit,
                   onSelect: (String?) -> Unit, onApprove: () -> Unit, onReject: () -> Unit, onSaveRules: (RuleSettings) -> Unit,
                   onAddExpectation: (Expectation) -> Unit = {}, onManualOutcome: (String, Outcome?, String?) -> Unit = { _, _, _ -> }) {
    val evaluation = ui.evaluation
    val decision = ui.selected?.let { id -> state.decisions.find { it.importId == id } }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                item { TextButton(onClick = { if (ui.selected != null) onSelect(null) else onBack() }, enabled = !ui.busy) {
                    Text(if (ui.selected != null) "Ver todas las propuestas" else "Volver a Programas") } }
                item { Text("Propuestas de semana", style = MaterialTheme.typography.headlineMedium) }
                if (ui.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                ui.message?.let { item { Text(it) } }
                if (!ui.ready) item { Button(onClick = onRetry, enabled = !ui.busy) { Text("Reintentar propuestas") } }
                if (ui.selected == null) {
                    item { Text("La IA propone, las reglas validan y tú decides. Importa propuestas en Intercambio JSON; ninguna se aplica sin tu aprobación.") }
                    if (ui.proposals.isEmpty()) item { Text("No hay propuestas importadas.") }
                    items(ui.proposals, key = { it.importId }) { p ->
                        val status = state.decisions.find { it.importId == p.importId }?.status
                        OutlinedButton(onClick = { onSelect(p.importId) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                            Text("${p.proposal!!.id} v${p.proposal.version} · ${p.proposal.week.name} · ${status.label()}")
                        }
                    }
                    item { RuleEditor(state.rules, !ui.busy && ui.ready, onSaveRules) }
                    if (state.expectations.isNotEmpty()) item {
                        val counts = Outcome.entries.associateWith { o -> state.expectations.count { (ui.results[it.id]?.outcome ?: Outcome.PENDING) == o } }
                        Text("Evaluación de recomendaciones: " + Outcome.entries.joinToString(" · ") { "${it.label}: ${counts.getValue(it)}" },
                            style = MaterialTheme.typography.titleMedium)
                    }
                    item { Text("Historial de decisiones", style = MaterialTheme.typography.titleLarge) }
                    if (state.decisions.isEmpty()) item { Text("Aún no hay decisiones.") }
                    items(state.decisions.sortedByDescending { it.decidedAtMillis }, key = { "decision-${it.importId}" }) { d -> DecisionCard(d) }
                } else if (evaluation != null) {
                    val p = evaluation.proposal
                    item { Text("${p.week.name} · ${p.id} v${p.version}", style = MaterialTheme.typography.titleLarge) }
                    item { Text("Programa ${p.programId} · bloque ${p.blockId} · fuente: ${p.source}") }
                    item { Text("Estado: ${decision?.status.label()}" + (decision?.let {
                        " · " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it.decidedAtMillis)) } ?: ""),
                        style = MaterialTheme.typography.titleMedium) }
                    // Decision controls stay next to the status, above the (possibly long) issue and change lists.
                    if (decision == null) {
                        item {
                            Button(onClick = onApprove, enabled = !ui.busy && evaluation.canApprove, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                Text(if (evaluation.canApprove) "Aprobar y añadir semana" else "Aprobación bloqueada")
                            }
                        }
                        item { OutlinedButton(onClick = onReject, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Rechazar propuesta") } }
                        item { Text("Aprobar añade la semana al programa guardado sin activar ninguna sesión ni modificar semanas existentes.",
                            style = MaterialTheme.typography.bodySmall) }
                    }
                    if (decision?.status == DecisionStatus.APPROVED) item {
                        ExpectationSection(decision.importId, p, state.expectations.filter { it.importId == decision.importId }, ui.results,
                            !ui.busy, onAddExpectation, onManualOutcome)
                    }
                    decision?.let { d ->
                        // The record as it was at decision time: what changed, why, the rule results and the version.
                        item { Text("Registro de la decisión (versión ${d.version}, semana ${d.weekId})", style = MaterialTheme.typography.titleMedium) }
                        items(d.differences.map { "Cambio: $it" } + d.statedDifferences.map { "Declarado: $it" } +
                            d.reasons.map { "Razón: $it" } + d.warnings.map { "Advertencia aceptada: $it" }) { Text("• $it") }
                        item { Text("Validación actual con las reglas vigentes:", style = MaterialTheme.typography.titleMedium) }
                    }
                    if (evaluation.blocking.isNotEmpty()) {
                        item { Text("Bloqueos (${evaluation.blocking.size}) — deben resolverse antes de aprobar", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium) }
                        items(evaluation.blocking) { Text("• ${it.message}", color = MaterialTheme.colorScheme.error) }
                    }
                    if (evaluation.warnings.isNotEmpty()) {
                        item { Text("Advertencias (${evaluation.warnings.size})", style = MaterialTheme.typography.titleMedium) }
                        items(evaluation.warnings) { Text("• ${it.message}") }
                    }
                    if (evaluation.issues.isEmpty()) item { Text("Sin bloqueos ni advertencias con las reglas actuales.") }
                    item { Text("Cambios explicados" + (evaluation.base?.let { " frente a ${it.name}" } ?: ""), style = MaterialTheme.typography.titleMedium) }
                    if (evaluation.explanations.isEmpty()) items(evaluation.differences) { Text("• $it") }
                    items(evaluation.explanations) { c -> ExplainedChangeCard(c) }
                    item { Text("Cambios declarados por el agente", style = MaterialTheme.typography.titleMedium) }
                    items(p.differences.ifEmpty { listOf("(ninguno)") }) { Text("• $it") }
                    item { Text("Razones del agente", style = MaterialTheme.typography.titleMedium) }
                    items(p.reasons.ifEmpty { listOf("(ninguna)") }) { Text("• $it") }
                    item { Text("Semana propuesta: ${p.week.days.size} días · ${p.week.days.sumOf { d -> d.exercises.sumOf { it.sets.size } }} series") }
                }
            }
        }
    }
}

@Composable
private fun ExplainedChangeCard(c: ExplainedChange) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${c.kind.label} · ${c.summary}", style = MaterialTheme.typography.titleSmall)
            Text("Evidencia: ${c.evidence}", style = MaterialTheme.typography.bodySmall)
            Text(c.reason?.let { "Motivo del agente: $it" } ?: "Sin motivo específico del agente para este cambio.",
                style = MaterialTheme.typography.bodySmall,
                color = if (c.reason == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Retrospective record of one decision: what changed, why, the evidence and the version (AG-03). */
@Composable
private fun DecisionCard(d: ProposalDecision) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(d.decidedAtMillis))} · ${d.status.label()} · ${d.proposalId} v${d.version}",
                style = MaterialTheme.typography.titleSmall)
            Text("Programa ${d.programId} · bloque ${d.blockId} · semana ${d.weekId}", style = MaterialTheme.typography.bodySmall)
            (if (d.explanations.isNotEmpty()) d.explanations.map { "${it.kind.label}: ${it.summary} Motivo: ${it.reason ?: "no indicado"}. Evidencia: ${it.evidence}" }
             else d.differences).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (d.reasons.isNotEmpty()) Text("Razones del agente: ${d.reasons.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
            if (d.warnings.isNotEmpty()) Text("Advertencias aceptadas: ${d.warnings.size}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RuleEditor(rules: RuleSettings, enabled: Boolean, onSave: (RuleSettings) -> Unit) {
    var edited by remember(rules) { mutableStateOf(rules) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reglas de validación", style = MaterialTheme.typography.titleLarge)
        Text("La integridad (programa, bloque, IDs nuevos) siempre bloquea. El resto se configura aquí.", style = MaterialTheme.typography.bodySmall)
        Threshold("Aumento máximo de carga", edited.maxLoadIncreasePercent) { edited = edited.copy(maxLoadIncreasePercent = it) }
        SeverityRow("Aumento de carga", edited.loadIncrease) { edited = edited.copy(loadIncrease = it) }
        Threshold("Aumento máximo de series", edited.maxSetIncreasePercent) { edited = edited.copy(maxSetIncreasePercent = it) }
        SeverityRow("Aumento de series", edited.setIncrease) { edited = edited.copy(setIncrease = it) }
        SeverityRow("Cambio de unidad o base de carga", edited.unitChange) { edited = edited.copy(unitChange = it) }
        SeverityRow("Series a RIR 0", edited.rirZero) { edited = edited.copy(rirZero = it) }
        SeverityRow("Ejercicio desconocido", edited.newExercise) { edited = edited.copy(newExercise = it) }
        SeverityRow("Cambio sin motivo del agente", edited.unexplainedChange) { edited = edited.copy(unexplainedChange = it) }
        Button(onClick = { onSave(edited) }, enabled = enabled && edited != rules) { Text("Guardar reglas") }
    }
}

@Composable
private fun Threshold(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("$label: $value %", Modifier.weight(1f))
        TextButton(onClick = { onChange((value - 5).coerceAtLeast(0)) }) { Text("−5") }
        TextButton(onClick = { onChange((value + 5).coerceAtMost(1000)) }) { Text("+5") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeverityRow(label: String, value: Severity, onChange: (Severity) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Severity.entries.forEach { s -> FilterChip(value == s, { onChange(s) }, { Text(s.label()) }) }
        }
    }
}
