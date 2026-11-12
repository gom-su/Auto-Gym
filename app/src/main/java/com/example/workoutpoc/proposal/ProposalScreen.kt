package com.example.workoutpoc.proposal

import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.AppTheme
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular
import androidx.compose.ui.Alignment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
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
private fun DecisionStatus?.tone() = when (this) { null -> Tone.Warning; DecisionStatus.APPROVED -> Tone.Success; DecisionStatus.REJECTED -> Tone.Error }
private fun DecisionStatus?.short() = when (this) { null -> "Pendiente"; DecisionStatus.APPROVED -> "Aprobada"; DecisionStatus.REJECTED -> "Rechazada" }

@Composable
fun ProposalScreen(state: ProposalState, ui: ProposalUiState, onBack: () -> Unit, onRetry: () -> Unit,
                   onSelect: (String?) -> Unit, onApprove: () -> Unit, onReject: () -> Unit, onSaveRules: (RuleSettings) -> Unit,
                   onAddExpectation: (Expectation) -> Unit = {}, onManualOutcome: (String, Outcome?, String?) -> Unit = { _, _, _ -> },
                   onReplacePreview: (Boolean) -> Unit = {}, onRegenerate: (String) -> Unit = {}) {
    val evaluation = ui.evaluation
    val decision = ui.selected?.let { id -> state.decisions.find { it.importId == id } }
    BackHandler(ui.selected != null && !ui.busy) { onSelect(null) }
    AppScreen("Propuestas de semana", if (ui.selected != null) "Ver todas las propuestas" else "Volver a Programas",
        { if (ui.selected != null) onSelect(null) else onBack() }, backEnabled = !ui.busy, busy = ui.busy, message = ui.message,
        listState = rememberViewListState(ui.selected), drillUp = ui.selected != null) {
        if (!ui.ready && !ui.busy) item { RetryAction("Reintentar propuestas", onRetry, enabled = !ui.busy) }
        if (ui.selected == null) {
            item { SupportingText("La IA propone, las reglas validan y tú decides. Importa propuestas en Intercambio JSON; ninguna se aplica sin tu aprobación.") }
            if (ui.proposals.isEmpty()) item { EmptyState("No hay propuestas importadas.", icon = AppIcons.Bolt) }
            items(ui.proposals, key = { it.importId }) { p ->
                val status = state.decisions.find { it.importId == p.importId }?.status
                NavRow("${p.proposal!!.id} v${p.proposal.version} · ${p.proposal.week.name} · ${status.label()}", { onSelect(p.importId) },
                    enabled = !ui.busy, icon = AppIcons.Bolt, badge = { StatusPill(status.short(), status.tone()) })
            }
            item { ContentCard { RuleEditor(state.rules, !ui.busy && ui.ready, onSaveRules) } }
            if (state.expectations.isNotEmpty()) item {
                val counts = Outcome.entries.associateWith { o -> state.expectations.count { (ui.results[it.id]?.outcome ?: Outcome.PENDING) == o } }
                ContentCard {
                    Text("Evaluación de recomendaciones: " + Outcome.entries.joinToString(" · ") { "${it.label}: ${counts.getValue(it)}" },
                        style = MaterialTheme.typography.titleSmall)
                }
            }
            item { SectionHeader("Historial de decisiones") }
            if (state.decisions.isEmpty()) item { EmptyState("Aún no hay decisiones.", icon = AppIcons.History) }
            items(state.decisions.sortedByDescending { it.decidedAtMillis }, key = { "decision-${it.importId}" }) { d -> DecisionCard(d) }
        } else if (evaluation != null) {
            val p = evaluation.proposal
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text("${p.week.name} · ${p.id} v${p.version}", style = MaterialTheme.typography.headlineSmall)
                    SupportingText("Programa ${p.programId} · bloque ${p.blockId} · fuente: ${p.source}")
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("Estado: ${decision?.status.label()}" + (decision?.let {
                        " · " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it.decidedAtMillis)) } ?: ""),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    StatusPill(decision?.status.short(), decision?.status.tone())
                }
            }
            item {
                MetricRow(listOf("${evaluation.blocking.size}" to "bloqueos", "${evaluation.warnings.size}" to "advertencias",
                    "${p.week.days.size}" to "días", "${p.week.days.sumOf { d -> d.exercises.sumOf { it.sets.size } }}" to "series"))
            }
            // Decision controls stay next to the status, above the (possibly long) issue and change lists.
            if (decision == null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (ui.replacePreview) {
                            // Explicit replacement (§2): the warning in the review says which week and how many sessions go.
                            PrimaryAction("Confirmar reemplazo y borrar registros", onApprove,
                                enabled = !ui.busy && evaluation.canApprove, icon = AppIcons.Check, tall = true)
                            SecondaryAction("Cancelar reemplazo", { onReplacePreview(false) }, enabled = !ui.busy)
                            SupportingText("Reemplazar borra las sesiones registradas de esa semana y no se puede deshacer.", small = true)
                        } else {
                            PrimaryAction(if (evaluation.canApprove) "Aprobar y añadir semana" else "Aprobación bloqueada", onApprove,
                                enabled = !ui.busy && evaluation.canApprove, icon = AppIcons.Check, tall = true)
                            if (evaluation.blocking.any { it.code == "WEEK_EXISTS" })
                                SecondaryAction("Reemplazar la semana guardada…", { onReplacePreview(true) }, enabled = !ui.busy)
                            SecondaryAction("Regenerar con feedback", { ui.selected?.let(onRegenerate) }, enabled = !ui.busy && ui.selected != null)
                            SecondaryAction("Rechazar propuesta", onReject, enabled = !ui.busy)
                            SupportingText("Aprobar añade la semana al programa guardado sin activar ninguna sesión ni modificar semanas existentes.", small = true)
                        }
                    }
                }
            }
            if (decision?.status == DecisionStatus.APPROVED) item {
                ExpectationSection(decision.importId, p, state.expectations.filter { it.importId == decision.importId }, ui.results,
                    !ui.busy, onAddExpectation, onManualOutcome)
            }
            decision?.let { d ->
                // The record as it was at decision time: what changed, why, the rule results and the version.
                item {
                    ContentCard {
                        Text("Registro de la decisión (versión ${d.version}, semana ${d.weekId})", style = MaterialTheme.typography.titleMedium)
                        (d.differences.map { "Cambio: $it" } + d.statedDifferences.map { "Declarado: $it" } +
                            d.reasons.map { "Razón: $it" } + d.warnings.map { "Advertencia aceptada: $it" }).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                item { SectionHeader("Validación actual con las reglas vigentes:") }
            }
            if (evaluation.blocking.isNotEmpty()) item {
                ContentCard(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Icon(AppIcons.Error, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("Bloqueos (${evaluation.blocking.size}) — deben resolverse antes de aprobar", style = MaterialTheme.typography.titleSmall)
                    }
                    evaluation.blocking.forEach { Text("• ${it.message}") }
                }
            }
            if (evaluation.warnings.isNotEmpty()) item {
                val x = AppTheme.extended
                ContentCard(color = x.warningContainer, contentColor = x.onWarningContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Icon(AppIcons.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("Advertencias (${evaluation.warnings.size})", style = MaterialTheme.typography.titleSmall)
                    }
                    evaluation.warnings.forEach { Text("• ${it.message}") }
                }
            }
            if (evaluation.issues.isEmpty()) item { Banner("Sin bloqueos ni advertencias con las reglas actuales.", tone = Tone.Success) }
            item { SectionHeader("Cambios explicados" + (evaluation.base?.let { " frente a ${it.name}" } ?: "")) }
            if (evaluation.explanations.isEmpty()) items(evaluation.differences) { Text("• $it") }
            items(evaluation.explanations) { c -> ExplainedChangeCard(c) }
            item { BulletCard("Cambios declarados por el agente", p.differences.ifEmpty { listOf("(ninguno)") }) }
            item { BulletCard("Razones del agente", p.reasons.ifEmpty { listOf("(ninguna)") }) }
            item { SupportingText("Semana propuesta: ${p.week.days.size} días · ${p.week.days.sumOf { d -> d.exercises.sumOf { it.sets.size } }} series", small = true) }
        }
    }

}

@Composable
private fun BulletCard(title: String, lines: List<String>) {
    ContentCard {
        Text(title, style = MaterialTheme.typography.titleSmall)
        lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun ExplainedChangeCard(c: ExplainedChange) {
    ContentCard {
        Text("${c.kind.label} · ${c.summary}", style = MaterialTheme.typography.titleSmall)
        Text("Evidencia: ${c.evidence}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(c.reason?.let { "Motivo del agente: $it" } ?: "Sin motivo específico del agente para este cambio.",
            style = MaterialTheme.typography.bodySmall,
            color = if (c.reason == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

/** Retrospective record of one decision: what changed, why, the evidence and the version (AG-03). */
@Composable
private fun DecisionCard(d: ProposalDecision) {
    ContentCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(d.decidedAtMillis))} · ${d.status.label()} · ${d.proposalId} v${d.version}",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            StatusPill(d.status.short(), d.status.tone())
        }
        Text("Programa ${d.programId} · bloque ${d.blockId} · semana ${d.weekId}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        (if (d.explanations.isNotEmpty()) d.explanations.map { "${it.kind.label}: ${it.summary} Motivo: ${it.reason ?: "no indicado"}. Evidencia: ${it.evidence}" }
         else d.differences).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        if (d.reasons.isNotEmpty()) Text("Razones del agente: ${d.reasons.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
        if (d.warnings.isNotEmpty()) Text("Advertencias aceptadas: ${d.warnings.size}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RuleEditor(rules: RuleSettings, enabled: Boolean, onSave: (RuleSettings) -> Unit) {
    var edited by remember(rules) { mutableStateOf(rules) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("Reglas de validación", style = MaterialTheme.typography.titleLarge)
        SupportingText("La integridad (programa, bloque, IDs nuevos) siempre bloquea. El resto se configura aquí.", small = true)
        Threshold("Aumento máximo de carga", edited.maxLoadIncreasePercent) { edited = edited.copy(maxLoadIncreasePercent = it) }
        SeverityRow("Aumento de carga", edited.loadIncrease) { edited = edited.copy(loadIncrease = it) }
        Threshold("Aumento máximo de series", edited.maxSetIncreasePercent) { edited = edited.copy(maxSetIncreasePercent = it) }
        SeverityRow("Aumento de series", edited.setIncrease) { edited = edited.copy(setIncrease = it) }
        SeverityRow("Cambio de unidad o base de carga", edited.unitChange) { edited = edited.copy(unitChange = it) }
        SeverityRow("Series a RIR 0", edited.rirZero) { edited = edited.copy(rirZero = it) }
        SeverityRow("Ejercicio desconocido", edited.newExercise) { edited = edited.copy(newExercise = it) }
        SeverityRow("Cambio sin motivo del agente", edited.unexplainedChange) { edited = edited.copy(unexplainedChange = it) }
        SeverityRow("Fuera de los objetivos del plan", edited.outlineTargets) { edited = edited.copy(outlineTargets = it) }
        PrimaryAction("Guardar reglas", { onSave(edited) }, enabled = enabled && edited != rules, icon = AppIcons.Check)
    }
}

@Composable
private fun Threshold(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("$label: $value\u00A0%", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.tabular())
        OutlinedButton(onClick = { onChange((value - 5).coerceAtLeast(0)) }, contentPadding = PaddingValues(horizontal = Spacing.md)) { Text("−5") }
        OutlinedButton(onClick = { onChange((value + 5).coerceAtMost(1000)) }, contentPadding = PaddingValues(horizontal = Spacing.md)) { Text("+5") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeverityRow(label: String, value: Severity, onChange: (Severity) -> Unit) {
    SegmentedChoice(label, Severity.entries, value, { it.label() }, onChange)
}
