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
import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Icon(AppIcons.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text("Tiempo activo: ${durationLabel(active)}" + if (state.pausedMillis > 0 || state.paused != null) " · pausas excluidas" else "",
            style = MaterialTheme.typography.bodyMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (state.paused != null) {
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
            Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(AppIcons.Pause, contentDescription = null)
                Text("Sesión en pausa", style = MaterialTheme.typography.titleLarge)
                Text("Las series, el cardio y el descanso pendiente se conservan. Al reanudar, el descanso continúa con el tiempo que le quedaba.")
                PrimaryAction("Reanudar sesión", { dispatch(state.command(WorkoutEvent.ResumeSession)) }, icon = AppIcons.Play, tall = true)
            }
        }
    } else if (state.phase == Phase.ActiveSet || state.phase == Phase.Resting) {
        TonalAction("Pausar sesión", { dispatch(state.command(WorkoutEvent.PauseSession)) }, icon = AppIcons.Pause)
    }
}

/** Cardio performed in a planned session, with add/remove. */
@Composable
fun CardioSection(state: WorkoutState, onAdd: (CardioAdd) -> Unit, onRemove: (CardioEntry) -> Unit) {
    if (state.source == null || state.phase == Phase.Idle) return
    var adding by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader("Cardio · ${state.cardio.size}")
        if (state.cardio.isEmpty()) SupportingText("Sin cardio registrado en esta sesión.", small = true)
        state.cardio.forEach { c ->
            ContentCard {
                Text(c.modality, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(c.durationSeconds?.let { "Duración ${durationLabel(it * 1000L)}" },
                    c.distanceTenths?.let { "${weightLabel(it)} ${c.distanceUnit!!.name.lowercase()}" }).joinToString(" · ").ifBlank { "Sin duración ni distancia" },
                    style = MaterialTheme.typography.bodyMedium.tabular())
                c.notes?.let { Text("Nota: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    QuietAction("Quitar cardio: ${c.modality}", { onRemove(c) }, icon = AppIcons.Delete, destructive = true)
                }
            }
        }
        SecondaryAction("Añadir cardio", { adding = true }, icon = AppIcons.Add)
    }
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Añadir cardio") }, icon = { Icon(AppIcons.Add, contentDescription = null) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(modality, { if (it.length <= 200) modality = it }, label = { Text("Modalidad") }, singleLine = true)
                OutlinedTextField(minutes, { minutes = it }, label = { Text("Duración (min, opcional)") }, singleLine = true, isError = !minutesOk,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    supportingText = if (!minutesOk) ({ Text("Entre 0 y 10080 minutos") }) else null)
                OutlinedTextField(distance, { distance = it }, label = { Text("Distancia (opcional)") }, singleLine = true, isError = !distanceOk,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    supportingText = if (!distanceOk) ({ Text("Número no válido") }) else null)
                ChipGroup("Unidad de distancia") {
                    DistanceUnit.entries.forEach { u -> ChoiceChip(unit == u, { unit = u }, { Text(u.name.lowercase()) }) }
                }
                OutlinedTextField(notes, { if (it.length <= MAX_NOTE_LENGTH) notes = it }, label = { Text("Notas") })
                SupportingText("Solo se guarda lo que escribas; no se estima duración ni distancia.", small = true)
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
