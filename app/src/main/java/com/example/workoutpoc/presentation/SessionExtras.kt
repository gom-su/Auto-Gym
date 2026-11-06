package com.example.workoutpoc.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.domain.*
import kotlinx.coroutines.delay
import java.util.UUID

/** Cardio to add, bound to the snapshot it was entered against. */
data class CardioAdd(val sessionId: String, val revision: Long, val entry: CardioEntry)

fun durationLabel(millis: Long): String {
    val total = millis / 1000
    val h = total / 3600; val m = (total % 3600) / 60; val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Pause banner, pause/resume control and active session time for planned sessions. */
@Composable
fun SessionStatus(state: WorkoutState, now: () -> ClockSnapshot, dispatch: (WorkoutCommand) -> Unit) {
    if (state.source == null || state.startedAtMillis == null) return
    var active by remember(state) { mutableLongStateOf(state.activeMillis(now()) ?: 0) }
    LaunchedEffect(state) { while (state.endedAtMillis == null && state.paused == null) { active = state.activeMillis(now()) ?: 0; delay(1000) } }
    Text("Tiempo activo: ${durationLabel(active)}" + if (state.pausedMillis > 0 || state.paused != null) " · pausas excluidas" else "",
        style = MaterialTheme.typography.bodyMedium)
    if (state.paused != null) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sesión en pausa", style = MaterialTheme.typography.titleLarge)
                Text("Las series, el cardio y el descanso pendiente se conservan. Al reanudar, el descanso continúa con el tiempo que le quedaba.")
                Button(onClick = { dispatch(state.command(WorkoutEvent.ResumeSession)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("Reanudar sesión")
                }
            }
        }
    } else if (state.phase == Phase.ActiveSet || state.phase == Phase.Resting) {
        OutlinedButton(onClick = { dispatch(state.command(WorkoutEvent.PauseSession)) }, modifier = Modifier.fillMaxWidth()) { Text("Pausar sesión") }
    }
}

/** Cardio performed in a planned session, with add/remove. */
@Composable
fun CardioSection(state: WorkoutState, onAdd: (CardioAdd) -> Unit, onRemove: (CardioEntry) -> Unit) {
    if (state.source == null || state.phase == Phase.Idle) return
    var adding by remember { mutableStateOf(false) }
    Text("Cardio · ${state.cardio.size}", style = MaterialTheme.typography.titleLarge)
    state.cardio.forEach { c ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(c.modality, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(c.durationSeconds?.let { "Duración ${durationLabel(it * 1000L)}" },
                    c.distanceTenths?.let { "${weightLabel(it)} ${c.distanceUnit!!.name.lowercase()}" }).joinToString(" · ").ifBlank { "Sin duración ni distancia" })
                c.notes?.let { Text("Nota: $it") }
                TextButton(onClick = { onRemove(c) }) { Text("Quitar cardio: ${c.modality}") }
            }
        }
    }
    OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Añadir cardio") }
    if (adding) CardioDialog({ adding = false }) { entry -> adding = false; onAdd(CardioAdd(state.sessionId, state.revision, entry)) }
}

@Composable
private fun CardioDialog(onDismiss: () -> Unit, onSave: (CardioEntry) -> Unit) {
    var modality by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    var distance by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(DistanceUnit.KM) }
    var notes by remember { mutableStateOf("") }
    val parsedMinutes = minutes.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val parsedDistance = distance.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val minutesOk = minutes.isBlank() || (parsedMinutes != null && parsedMinutes in 0.0..10080.0)
    val distanceOk = distance.isBlank() || (parsedDistance != null && parsedDistance in 0.0..1_000_000.0)
    val valid = modality.isNotBlank() && minutesOk && distanceOk && (parsedMinutes != null || parsedDistance != null || notes.isNotBlank())
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Añadir cardio") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(modality, { if (it.length <= 200) modality = it }, label = { Text("Modalidad") }, singleLine = true)
                OutlinedTextField(minutes, { minutes = it }, label = { Text("Duración (min, opcional)") }, singleLine = true, isError = !minutesOk)
                OutlinedTextField(distance, { distance = it }, label = { Text("Distancia (opcional)") }, singleLine = true, isError = !distanceOk)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    DistanceUnit.entries.forEach { u -> FilterChip(unit == u, { unit = u }, { Text(u.name.lowercase()) }) }
                }
                OutlinedTextField(notes, { if (it.length <= MAX_NOTE_LENGTH) notes = it }, label = { Text("Notas") })
                Text("Solo se guarda lo que escribas; no se estima duración ni distancia.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                onSave(CardioEntry(UUID.randomUUID().toString(), modality.trim(),
                    parsedMinutes?.let { (it * 60).toInt() }, parsedDistance?.let { (it * 10).toInt() },
                    parsedDistance?.let { unit }, notes.trim().ifBlank { null }, System.currentTimeMillis()))
            }) { Text("Guardar cardio") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}
