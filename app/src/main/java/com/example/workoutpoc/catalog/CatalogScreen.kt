package com.example.workoutpoc.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.domain.SetStatus
import com.example.workoutpoc.domain.loadLabel
import com.example.workoutpoc.domain.rirLabel
import com.example.workoutpoc.planning.LoadBasis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Equipment.label() = when (this) {
    Equipment.BARBELL -> "barra"; Equipment.DUMBBELL -> "mancuernas"; Equipment.MACHINE -> "máquina"; Equipment.CABLE -> "polea"
    Equipment.BODYWEIGHT -> "peso corporal"; Equipment.KETTLEBELL -> "pesa rusa"; Equipment.BAND -> "banda"; Equipment.OTHER -> "otro"
}
private fun LoadBasis.label() = when (this) {
    LoadBasis.TOTAL -> "total"; LoadBasis.PER_SIDE -> "por lado"; LoadBasis.PER_HAND -> "por mano"
    LoadBasis.BODYWEIGHT -> "peso corporal"; LoadBasis.MACHINE_SETTING -> "posición de máquina"
}

@Composable
fun CatalogScreen(catalog: ExerciseCatalog, ui: CatalogUiState, onBack: () -> Unit, onRetry: () -> Unit, onSave: (CatalogExercise) -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = catalog.exercises.find { it.id == selectedId }
    val ambiguous = remember(catalog) { CatalogCodec.ambiguous(catalog) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                item { TextButton(onClick = { if (selected != null) selectedId = null else onBack() }, enabled = !ui.busy) {
                    Text(if (selected != null) "Ver todos los ejercicios" else "Volver a Programas") } }
                item { Text("Catálogo de ejercicios", style = MaterialTheme.typography.headlineMedium) }
                if (ui.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                ui.message?.let { item { Text(it) } }
                if (!ui.ready) item { Button(onClick = onRetry, enabled = !ui.busy) { Text("Reintentar catálogo") } }
                if (selected == null) {
                    item { Text("Cada ejercicio tiene un ID estable. Cambiar su nombre no altera su historial; variantes con distinto equipo o configuración son ejercicios distintos.") }
                    if (catalog.exercises.isEmpty()) item { Text("Aún no hay ejercicios. Se registran al guardar programas o entrenar sesiones planificadas.") }
                    items(catalog.exercises, key = { it.id }) { e ->
                        val count = remember(ui.sessions, e.id) { exerciseHistory(e.id, ui.sessions).size }
                        OutlinedButton(onClick = { selectedId = e.id }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(listOfNotNull(e.name, e.variant, e.equipment?.label()).joinToString(" · "))
                                Text("ID ${e.id} · $count series registradas" + if (e.id in ambiguous) " · nombre ambiguo" else "",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else {
                    item { ExerciseEditor(selected, selected.id in ambiguous, !ui.busy && ui.ready, onSave) }
                    val history = exerciseHistory(selected.id, ui.sessions)
                    item { Text("Historial · ${history.size} series", style = MaterialTheme.typography.titleLarge) }
                    if (history.isEmpty()) item { Text("Sin series registradas para este ID.") }
                    items(history, key = { "${it.sessionId}-${it.completedAtMillis}-${it.recordedName}-${it.values}" }) { h ->
                        val format = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${format.format(Date(h.completedAtMillis))} · ${h.dayName ?: "sesión"}", style = MaterialTheme.typography.titleSmall)
                                Text(if (h.status == SetStatus.SKIPPED) "Omitida" else
                                    "${loadLabel(h.values.weightTenths, h.unit)} · ${h.values.reps} reps · RIR ${rirLabel(h.values.rir)}" +
                                        (if (h.warmup) " · aproximación" else if (h.extra) " · extra" else ""))
                                if (h.recordedName != selected.name) Text("Registrada como \"${h.recordedName}\"", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseEditor(exercise: CatalogExercise, ambiguous: Boolean, enabled: Boolean, onSave: (CatalogExercise) -> Unit) {
    var name by remember(exercise) { mutableStateOf(exercise.name) }
    var movement by remember(exercise) { mutableStateOf(exercise.movement.orEmpty()) }
    var variant by remember(exercise) { mutableStateOf(exercise.variant.orEmpty()) }
    var equipment by remember(exercise) { mutableStateOf(exercise.equipment) }
    var basis by remember(exercise) { mutableStateOf(exercise.loadBasis) }
    var setup by remember(exercise) { mutableStateOf(exercise.setup.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ID estable: ${exercise.id}", style = MaterialTheme.typography.titleMedium)
        if (ambiguous) Text("Otro ejercicio tiene el mismo nombre, variante y equipo. Añade una variante o equipo para distinguirlos.",
            color = MaterialTheme.colorScheme.error)
        OutlinedTextField(name, { if (it.length <= 200) name = it }, label = { Text("Nombre visible") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (exercise.aliases.isNotEmpty()) Text("Nombres anteriores: ${exercise.aliases.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(movement, { if (it.length <= 200) movement = it }, label = { Text("Movimiento (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(variant, { if (it.length <= 200) variant = it }, label = { Text("Variante (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Equipo", style = MaterialTheme.typography.labelLarge)
        FlowChips(listOf(null) + Equipment.entries, equipment, { it?.label() ?: "sin indicar" }) { equipment = it }
        Text("Base de carga habitual (informativa)", style = MaterialTheme.typography.labelLarge)
        FlowChips(listOf(null) + LoadBasis.entries, basis, { it?.label() ?: "sin indicar" }) { basis = it }
        OutlinedTextField(setup, { if (it.length <= 1000) setup = it }, label = { Text("Configuración de máquina o montaje") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSave(exercise.copy(name = name, movement = movement, variant = variant, equipment = equipment,
            loadBasis = basis, setup = setup)) }, enabled = enabled && name.isNotBlank()) { Text("Guardar ejercicio") }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> FlowChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option -> FilterChip(selected == option, { onSelect(option) }, { Text(label(option)) }) }
    }
}
