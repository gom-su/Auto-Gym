package com.example.workoutpoc.planning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ProgramScreen(
    library: ProgramLibrary, ui: ProgramUiState, onBack: () -> Unit, onFile: () -> Unit,
    onText: (String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onExchange: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = ui.preview ?: library.programs.find { it.id == selectedId }
    var weekId by rememberSaveable(selectedId, ui.preview?.id) { mutableStateOf<String?>(null) }
    var dayId by rememberSaveable(selectedId, ui.preview?.id, weekId) { mutableStateOf<String?>(null) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                item { TextButton(onClick = onBack) { Text("Volver al entrenamiento") } }
                item { Text("Programas", style = MaterialTheme.typography.headlineLarge) }
                item { OutlinedButton(onClick = onExchange) { Text("Intercambio JSON") } }
                if (ui.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                ui.message?.let { item { Text(it) } }
                if (!ui.ready) item {
                    Button(onClick = onRetry, enabled = !ui.busy) { Text("Reintentar biblioteca") }
                }
                if (ui.preview != null) {
                    item { Text("Revisar importación", style = MaterialTheme.typography.titleLarge) }
                    item { Text("Revisa las semanas antes de guardar. Importar conserva la planificación; no inicia ni activa un entrenamiento.") }
                    item { Button(onClick = onSave, enabled = ui.ready && !ui.busy) { Text("Guardar programa") } }
                    item { OutlinedButton(onClick = onCancel, enabled = !ui.busy) { Text("Cancelar importación") } }
                } else if (selected == null) {
                    item { Text("Importa un programa JSON v1 desde un archivo o pega su contenido. Puedes variar días, ejercicios y series en cada semana.") }
                    item { Button(onClick = onFile, enabled = ui.ready && !ui.busy) { Text("Seleccionar archivo JSON") } }
                    item { OutlinedTextField(value = text, onValueChange = { if (it.length <= ProgramCodec.MAX_BYTES) text = it },
                        label = { Text("Contenido JSON del programa") }, modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)) }
                    item { OutlinedButton(onClick = { onText(text) }, enabled = ui.ready && !ui.busy && text.isNotBlank()) { Text("Revisar JSON") } }
                    if (library.programs.isEmpty()) item { Text("Aún no hay programas guardados.") }
                    items(library.programs, key = { it.id }) { program ->
                        OutlinedButton(onClick = { selectedId = program.id }, modifier = Modifier.fillMaxWidth()) { Text(program.name) }
                    }
                } else item { TextButton(onClick = { selectedId = null }) { Text("Ver todos los programas") } }
                if (selected != null) programBrowser(selected, weekId, dayId,
                    { weekId = it }, { dayId = it })
            }
        }
    }
}

private fun LazyListScope.programBrowser(
    program: TrainingProgram, weekId: String?, dayId: String?,
    onWeek: (String?) -> Unit, onDay: (String?) -> Unit
) {
    val week = program.blocks.flatMap { it.weeks }.find { it.id == weekId }
    val day = week?.days?.find { it.id == dayId }
    item { Text(program.name, style = MaterialTheme.typography.titleLarge) }
    if (week == null) program.blocks.forEach { block ->
        item { Text(block.name, style = MaterialTheme.typography.titleMedium) }
        items(block.weeks, key = { "week-${it.id}" }) { w ->
            OutlinedButton(onClick = { onWeek(w.id) }) { Text("${w.name} · ${w.days.size} ${if (w.days.size == 1) "día" else "días"}") }
        }
    } else {
        item { TextButton(onClick = { onWeek(null) }) { Text("Ver semanas") } }
        item { Text("${week.name} · versión ${week.version}", style = MaterialTheme.typography.titleMedium) }
        if (day == null) items(week.days, key = { "day-${it.id}" }) { d ->
            OutlinedButton(onClick = { onDay(d.id) }) { Text("${d.name} · ${d.exercises.size} ${if (d.exercises.size == 1) "ejercicio" else "ejercicios"} · ${d.exercises.sumOf { it.sets.size }} series") }
        } else {
            item { TextButton(onClick = { onDay(null) }) { Text("Ver días") } }
            item { Text(day.name, style = MaterialTheme.typography.titleMedium) }
            day.exercises.forEach { exercise ->
                item { Text(exercise.name, style = MaterialTheme.typography.titleMedium) }
                items(exercise.sets.size, key = { "set-${exercise.sets[it].id}" }) { index ->
                    val set = exercise.sets[index]
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Serie ${index + 1} · ${set.reps?.let { "$it reps" } ?: "reps sin indicar"} · ${set.rir?.let { "RIR $it" } ?: "RIR sin indicar"}")
                            Text(set.load?.let { "${it.value} ${it.unit.label()} · ${it.basis.label()}" } ?: "Carga sin indicar")
                            Text(set.restSeconds?.let { "Descanso: $it s" } ?: "Descanso sin indicar")
                        }
                    }
                }
            }
        }
    }
}

private fun LoadUnit.label() = when (this) {
    LoadUnit.KG -> "kg"; LoadUnit.LB -> "lb"; LoadUnit.MACHINE_SETTING -> "posición"; LoadUnit.BODYWEIGHT -> "peso corporal"
}
private fun LoadBasis.label() = when (this) {
    LoadBasis.TOTAL -> "total"; LoadBasis.PER_SIDE -> "por lado"; LoadBasis.PER_HAND -> "por mano"
    LoadBasis.BODYWEIGHT -> "peso corporal"; LoadBasis.MACHINE_SETTING -> "configuración de máquina"
}
