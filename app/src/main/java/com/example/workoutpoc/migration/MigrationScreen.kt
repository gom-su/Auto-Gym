package com.example.workoutpoc.migration

import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.AppTheme
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular
import androidx.compose.ui.Alignment
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun MigrationScreen(
    archive: MigrationArchive, ui: MigrationUiState, onBack: () -> Unit, onFile: () -> Unit,
    onText: (String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit,
    onSelect: (String?) -> Unit, onRetry: () -> Unit, onAcknowledge: (String, Boolean) -> Unit,
    onConvert: () -> Unit = {}
) {
    var text by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val document = ui.preview ?: archive.documents.find { it.id == ui.selectedId }
    var page by rememberSaveable(document?.id) { mutableStateOf("summary") }
    var dayId by rememberSaveable(document?.id) { mutableStateOf<String?>(null) }
    BackHandler(document != null && page == "summary" && dayId != null) { dayId = null }
    AppScreen("Migrar desde Obsidian", "Volver a Programas", onBack, backEnabled = !ui.busy, busy = ui.busy, message = ui.message,
        listState = rememberViewListState(document?.let { "${it.id}/${ui.preview != null}" })) {
        if (!ui.ready && !ui.busy) item { RetryAction("Reintentar migraciones", onRetry, enabled = !ui.busy) }
        if (document == null) {
            item {
                ContentCard {
                    SupportingText("Importa una semana Workout v3 para revisar objetivos, resultados y notas. Los datos inciertos se conservan con advertencias.")
                    PrimaryAction("Seleccionar archivo Markdown", onFile, enabled = !ui.busy && ui.ready, icon = AppIcons.Upload)
                    OutlinedTextField(text, { if (it.length <= MigrationCodec.MAX_BYTES) text = it },
                        label = { Text("Contenido Markdown") }, modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp))
                    SecondaryAction("Revisar Markdown", { keyboard?.hide(); focus.clearFocus(); onText(text) }, enabled = ui.ready && !ui.busy && text.isNotBlank())
                }
            }
            item { SectionHeader("Migraciones guardadas") }
            if (archive.documents.isEmpty()) item { EmptyState("Aún no hay migraciones guardadas.", icon = AppIcons.Upload) }
            itemsIndexed(archive.documents, key = { _, d -> d.id }) { index, d ->
                val pending = d.issues.count { !it.acknowledged }
                NavRow("Importación ${index + 1} · ${d.title ?: d.source} · $pending pendientes", { onSelect(d.id) }, enabled = !ui.busy,
                    icon = AppIcons.Upload, badge = if (pending > 0) ({ Icon(AppIcons.Warning, contentDescription = null, tint = AppTheme.extended.warning) }) else null)
            }
        } else {
            val pending = document.issues.count { !it.acknowledged }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(document.title ?: document.source, style = MaterialTheme.typography.headlineSmall)
                    SupportingText("${document.source} · ${document.days.size} días · ${document.issues.size} advertencias · $pending sin revisar")
                }
            }
            item { SupportingText("La migración queda archivada para revisión. Marcar una advertencia como vista no resuelve el dato ambiguo.", small = true) }
            if (ui.preview != null) {
                item { PrimaryAction(if (pending > 0) "Guardar con pendientes ($pending)" else "Guardar migración", onSave,
                    enabled = ui.ready && !ui.busy, icon = AppIcons.Check) }
                item { Banner("Revisión sin guardar. Corrige el Markdown y vuelve a importarlo si hace falta.", tone = Tone.Info) }
            }
            item { SecondaryAction(if (ui.preview != null) "Cancelar importación" else "Ver migraciones", onCancel, enabled = !ui.busy) }
            item {
                // Pages as single-choice chips that wrap, so every page stays visible on narrow screens.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    PageChip("Estructura y notas", page == "summary") { page = "summary"; dayId = null }
                    PageChip("Advertencias (${document.issues.size})", page == "issues") { page = "issues" }
                    PageChip("Texto original con líneas", page == "original") { page = "original" }
                    if (ui.preview == null) PageChip("Convertir en programa", page == "convert") { page = "convert" }
                }
            }
            when (page) {
                "convert" -> {
                    val report = MigrationConversion.convert(document)
                    item { SectionHeader("Conversión en programa",
                        supporting = "Solo los valores exactos se convierten en prescripción; lo demás queda sin fijar y se conserva en la migración. Semanas del mismo programa se añaden sin duplicados.") }
                    if (report.blockers.isNotEmpty()) item {
                        ContentCard(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                            Text("No se puede convertir todavía", style = MaterialTheme.typography.titleSmall)
                            report.blockers.forEach { Text("• $it") }
                        }
                    }
                    report.program?.let { p ->
                        val week = p.blocks.single().weeks.single()
                        item { Banner("${p.name} · ${p.blocks.single().name} · ${week.name} · ${week.days.size} días · " +
                            "${week.days.sumOf { it.exercises.size }} ejercicios", tone = Tone.Success) }
                    }
                    if (report.dropped.isNotEmpty()) item {
                        ContentCard {
                            Text("No se convierte (${report.dropped.size})", style = MaterialTheme.typography.titleSmall)
                            report.dropped.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    item { PrimaryAction("Crear o ampliar programa", onConvert, enabled = report.program != null && ui.ready && !ui.busy, icon = AppIcons.Library) }
                }
                "original" -> items(document.lines, key = { "line-${it.line}" }) { line ->
                    SelectionContainer {
                        Text("${line.line}  ${line.text}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
                "issues" -> {
                    if (document.issues.isEmpty()) item { EmptyState("Sin advertencias del parser. Revisa el contenido antes de guardar.", icon = AppIcons.CheckCircle) }
                    items(document.issues, key = { it.id }) { issue ->
                        ContentCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Icon(if (issue.acknowledged) AppIcons.Check else AppIcons.Warning, contentDescription = null, modifier = Modifier.size(18.dp),
                                    tint = if (issue.acknowledged) MaterialTheme.colorScheme.onSurfaceVariant else AppTheme.extended.warning)
                                Text("Línea ${issue.line} · ${issue.code}", style = MaterialTheme.typography.titleSmall)
                            }
                            Text(issue.message)
                            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                                Text(document.lines[issue.line - 1].text, Modifier.padding(Spacing.sm),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(issue.acknowledged, { onAcknowledge(issue.id, it) }, enabled = !ui.busy,
                                    modifier = Modifier.semantics { contentDescription = "Advertencia de la línea ${issue.line}: ${issue.message}" })
                                Text(if (issue.acknowledged) "Vista; significado sin resolver" else "Pendiente de revisión",
                                    color = if (issue.acknowledged) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
                else -> {
                    val day = document.days.find { it.id == dayId }
                    if (day == null) {
                        if (document.metadata.isNotEmpty()) item {
                            ContentCard {
                                document.metadata.entries.forEach { Text("${it.key}: ${it.value}", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        items(document.days, key = { it.id }) { d ->
                            NavRow("${d.title} · ${d.exercises.size} ejercicios", { dayId = d.id }, icon = AppIcons.Calendar)
                        }
                        items(document.notes, key = { "note-${it.line}" }) { note -> Note(note) }
                    } else {
                        item { BackLink("Ver todos los días", { dayId = null }) }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                Text("${day.title} · línea ${day.line}", style = MaterialTheme.typography.titleLarge)
                                Text("Estado: ${when (day.status) { "COMPLETED" -> "completado"; "PARTIAL" -> "parcial"; "NOT_PERFORMED" -> "no realizado"; else -> "sin determinar" }}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(day.exercises, key = { it.id }) { exercise ->
                            ContentCard {
                                Text("${exercise.name} · línea ${exercise.line}", style = MaterialTheme.typography.titleMedium)
                                Text("${exercise.section} · Objetivo: ${exercise.prescription.raw.ifBlank { "sin indicar" }}")
                                Text("Prescripción: ${exercise.prescription.sets ?: "?"} series · reps ${exercise.prescription.reps.label()} · RIR ${exercise.prescription.rir.label()}",
                                    style = MaterialTheme.typography.bodyMedium.tabular())
                                Text("Carga: ${exercise.load.raw.ifBlank { "sin indicar" }} · unidad ${exercise.load.unit.unitLabel()} · base ${exercise.load.basis.basisLabel()}",
                                    style = MaterialTheme.typography.bodyMedium.tabular())
                                Text("Reps reales: ${exercise.reps.raw.ifBlank { "sin indicar" }} · interpretadas: ${exercise.reps.values.label()}",
                                    style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("RIR real: ${exercise.rir.raw.ifBlank { "sin indicar" }} · interpretado: ${exercise.rir.values.label()}",
                                    style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                exercise.comments.forEach { Note(it) }
                                exercise.columns.filterKeys { it !in setOf("ejercicio", "objetivo", "carga", "reps reales", "rir real", "notas", "notes", "comentarios gpt", "comentarios usuario", "resultado") }
                                    .forEach { (key, value) -> Text("Sin interpretar · $key: $value", style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.warning) }
                            }
                        }
                        items(day.cardio, key = { "cardio-${it.line}" }) { cardio ->
                            ContentCard { Text("Cardio · línea ${cardio.line}: ${cardio.raw}\nDuración explícita: ${cardio.durationMinutes.label()} min") }
                        }
                        items(day.notes, key = { "note-${it.line}" }) { Note(it) }
                    }
                }
            }
        }
    }

}

@Composable private fun PageChip(label: String, selected: Boolean, onClick: () -> Unit) {
    ChoiceChip(selected, onClick, { Text(label) })
}

@Composable private fun Note(note: MigrationComment) {
    Text("Línea ${note.line} · ${when (note.author) { "AGENT" -> "Agente"; "USER" -> "Usuario"; else -> "Sin autor atribuido" }}: ${note.text}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
private fun Double.numberLabel(): String = if (this == toLong().toDouble()) toLong().toString() else toString()
private fun NumberRange?.label(): String = if (this == null) "sin indicar" else if (min == max) min.numberLabel() else "${min.numberLabel()}–${max.numberLabel()}"
private fun List<Double?>.label(): String = if (isEmpty()) "sin indicar" else joinToString(", ") { it?.numberLabel() ?: "?" }
private fun String?.unitLabel(): String = when (this) { "KG" -> "kg"; "LB" -> "lb"; "BODYWEIGHT" -> "peso corporal"; else -> "sin indicar" }
private fun String?.basisLabel(): String = when (this) { "TOTAL" -> "total"; "PER_SIDE" -> "por lado"; "PER_HAND" -> "por mano"; "BODYWEIGHT" -> "peso corporal"; else -> "desconocida" }
