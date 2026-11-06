package com.example.workoutpoc.migration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    onSelect: (String?) -> Unit, onRetry: () -> Unit, onAcknowledge: (String, Boolean) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val document = ui.preview ?: archive.documents.find { it.id == ui.selectedId }
    var page by rememberSaveable(document?.id) { mutableStateOf("summary") }
    var dayId by rememberSaveable(document?.id) { mutableStateOf<String?>(null) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                item { TextButton(onClick = onBack, enabled = !ui.busy) { Text("Volver a Programas") } }
                item { Text("Migrar desde Obsidian", style = MaterialTheme.typography.headlineMedium) }
                if (ui.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                ui.message?.let { item { Text(it) } }
                if (!ui.ready) item { Button(onClick = onRetry, enabled = !ui.busy) { Text("Reintentar migraciones") } }
                if (document == null) {
                    item { Text("Importa una semana Workout v3 para revisar objetivos, resultados y notas. Los datos inciertos se conservan con advertencias.") }
                    item { Button(onClick = onFile, enabled = !ui.busy && ui.ready) { Text("Seleccionar archivo Markdown") } }
                    item { OutlinedTextField(text, { if (it.length <= MigrationCodec.MAX_BYTES) text = it },
                        label = { Text("Contenido Markdown") }, modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)) }
                    item { OutlinedButton(onClick = { keyboard?.hide(); focus.clearFocus(); onText(text) }, enabled = ui.ready && !ui.busy && text.isNotBlank()) { Text("Revisar Markdown") } }
                    item { Text("Migraciones guardadas", style = MaterialTheme.typography.titleLarge) }
                    if (archive.documents.isEmpty()) item { Text("Aún no hay migraciones guardadas.") }
                    itemsIndexed(archive.documents, key = { _, d -> d.id }) { index, d ->
                        OutlinedButton(onClick = { onSelect(d.id) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                            Text("Importación ${index + 1} · ${d.title ?: d.source} · ${d.issues.count { !it.acknowledged }} pendientes")
                        }
                    }
                } else {
                    item { Text(document.title ?: document.source, style = MaterialTheme.typography.titleLarge) }
                    item { Text("${document.source} · ${document.days.size} días · ${document.issues.size} advertencias · ${document.issues.count { !it.acknowledged }} sin revisar") }
                    item { Text("La migración queda archivada para revisión. Marcar una advertencia como vista no resuelve el dato ambiguo.") }
                    if (ui.preview != null) {
                        item { Button(onClick = onSave, enabled = ui.ready && !ui.busy) {
                            val pending = document.issues.count { !it.acknowledged }
                            Text(if (pending > 0) "Guardar con pendientes ($pending)" else "Guardar migración")
                        } }
                        item { Text("Revisión sin guardar. Corrige el Markdown y vuelve a importarlo si hace falta.") }
                    }
                    item { OutlinedButton(onClick = onCancel, enabled = !ui.busy) { Text(if (ui.preview != null) "Cancelar importación" else "Ver migraciones") } }
                    item {
                        Column {
                            TextButton(onClick = { page = "summary"; dayId = null }) { Text("Estructura y notas") }
                            TextButton(onClick = { page = "issues" }) { Text("Advertencias (${document.issues.size})") }
                            TextButton(onClick = { page = "original" }) { Text("Texto original con líneas") }
                        }
                    }
                    when (page) {
                        "original" -> items(document.lines, key = { "line-${it.line}" }) { line ->
                            SelectionContainer { Text("${line.line}  ${line.text}") }
                        }
                        "issues" -> {
                            if (document.issues.isEmpty()) item { Text("Sin advertencias del parser. Revisa el contenido antes de guardar.") }
                            items(document.issues, key = { it.id }) { issue ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("Línea ${issue.line} · ${issue.code}", style = MaterialTheme.typography.titleSmall)
                                        Text(issue.message)
                                        Text(document.lines[issue.line - 1].text)
                                        Row {
                                            Checkbox(issue.acknowledged, { onAcknowledge(issue.id, it) }, enabled = !ui.busy,
                                                modifier = Modifier.semantics { contentDescription = "Advertencia de la línea ${issue.line}: ${issue.message}" })
                                            Text(if (issue.acknowledged) "Vista; significado sin resolver" else "Pendiente de revisión", Modifier.padding(top = 12.dp))
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            val day = document.days.find { it.id == dayId }
                            if (day == null) {
                                items(document.metadata.entries.toList(), key = { "meta-${it.key}" }) { Text("${it.key}: ${it.value}") }
                                items(document.days, key = { it.id }) { d ->
                                    OutlinedButton(onClick = { dayId = d.id }, modifier = Modifier.fillMaxWidth()) { Text("${d.title} · ${d.exercises.size} ejercicios") }
                                }
                                items(document.notes, key = { "note-${it.line}" }) { note -> Note(note) }
                            } else {
                                item { TextButton(onClick = { dayId = null }) { Text("Ver todos los días") } }
                                item { Text("${day.title} · línea ${day.line}", style = MaterialTheme.typography.titleLarge) }
                                item { Text("Estado: ${when (day.status) { "COMPLETED" -> "completado"; "PARTIAL" -> "parcial"; "NOT_PERFORMED" -> "no realizado"; else -> "sin determinar" }}") }
                                items(day.exercises, key = { it.id }) { exercise ->
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("${exercise.name} · línea ${exercise.line}", style = MaterialTheme.typography.titleMedium)
                                            Text("${exercise.section} · Objetivo: ${exercise.prescription.raw.ifBlank { "sin indicar" }}")
                                            Text("Prescripción: ${exercise.prescription.sets ?: "?"} series · reps ${exercise.prescription.reps.label()} · RIR ${exercise.prescription.rir.label()}")
                                            Text("Carga: ${exercise.load.raw.ifBlank { "sin indicar" }} · unidad ${exercise.load.unit.unitLabel()} · base ${exercise.load.basis.basisLabel()}")
                                            Text("Reps reales: ${exercise.reps.raw.ifBlank { "sin indicar" }} · interpretadas: ${exercise.reps.values.label()}")
                                            Text("RIR real: ${exercise.rir.raw.ifBlank { "sin indicar" }} · interpretado: ${exercise.rir.values.label()}")
                                            exercise.comments.forEach { Note(it) }
                                            exercise.columns.filterKeys { it !in setOf("ejercicio", "objetivo", "carga", "reps reales", "rir real", "notas", "notes", "comentarios gpt", "comentarios usuario", "resultado") }
                                                .forEach { (key, value) -> Text("Sin interpretar · $key: $value") }
                                        }
                                    }
                                }
                                items(day.cardio, key = { "cardio-${it.line}" }) { cardio -> Text("Cardio · línea ${cardio.line}: ${cardio.raw}\nDuración explícita: ${cardio.durationMinutes.label()} min") }
                                items(day.notes, key = { "note-${it.line}" }) { Note(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Note(note: MigrationComment) {
    Text("Línea ${note.line} · ${when (note.author) { "AGENT" -> "Agente"; "USER" -> "Usuario"; else -> "Sin autor atribuido" }}: ${note.text}")
}
private fun Double.numberLabel(): String = if (this == toLong().toDouble()) toLong().toString() else toString()
private fun NumberRange?.label(): String = if (this == null) "sin indicar" else if (min == max) min.numberLabel() else "${min.numberLabel()}–${max.numberLabel()}"
private fun List<Double?>.label(): String = if (isEmpty()) "sin indicar" else joinToString(", ") { it?.numberLabel() ?: "?" }
private fun String?.unitLabel(): String = when (this) { "KG" -> "kg"; "LB" -> "lb"; "BODYWEIGHT" -> "peso corporal"; else -> "sin indicar" }
private fun String?.basisLabel(): String = when (this) { "TOTAL" -> "total"; "PER_SIDE" -> "por lado"; "PER_HAND" -> "por mano"; "BODYWEIGHT" -> "peso corporal"; else -> "desconocida" }
