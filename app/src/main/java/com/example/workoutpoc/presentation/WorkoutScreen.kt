package com.example.workoutpoc.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.data.DemoWorkout
import com.example.workoutpoc.domain.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(
    state: WorkoutState, ready: Boolean, error: String?, notificationEnabled: Boolean,
    overlayGranted: Boolean, message: String?, editorVisible: Boolean,
    onDismissEditor: () -> Unit, onNotificationMode: () -> Unit, onOverlayMode: () -> Unit,
    onNotificationSettings: () -> Unit, retry: () -> Unit,
    now: () -> ClockSnapshot, dispatch: (WorkoutCommand) -> Unit, onPrograms: () -> Unit
) {
    val colors = darkColorScheme(primary = Color(0xFFB4EE80), onPrimary = Color(0xFF17220F),
        background = Color(0xFF101612), surface = Color(0xFF18221C), surfaceContainer = Color(0xFF243028))
    MaterialTheme(colorScheme = colors) {
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("WORKOUT / POC", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                Text("Tu siguiente serie.", style = MaterialTheme.typography.headlineLarge)
                Text("3 ejercicios · 7 series · descansos de 10 s", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onPrograms) { Text("Programas") }
                if (error != null) {
                    Text(error, color = colors.error)
                    TextButton(onClick = retry) { Text("Reintentar") }
                }
                if (!ready) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.phase == Phase.ActiveSet || state.phase == Phase.Resting)
                    message?.let { Text(it, color = colors.primary) }
                if (!notificationEnabled) {
                    Text("Notificaciones desactivadas. Puedes registrar aquí; para probar el modo A, habilítalas en Ajustes.")
                    OutlinedButton(onClick = onNotificationSettings) { Text("Ajustes de notificaciones") }
                }
                Button(onClick = onNotificationMode, enabled = ready && state.phase != Phase.Completed,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Iniciar demo — Notificación") }
                OutlinedButton(onClick = onOverlayMode, enabled = ready && state.phase != Phase.Completed,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Iniciar demo — Burbuja flotante") }
                Text(if (overlayGranted) "Panel flotante autorizado. Cambia de modo sin perder tu progreso."
                    else "El modo burbuja necesita permiso para mostrarse sobre otras aplicaciones.",
                    style = MaterialTheme.typography.bodySmall)
                when (state.phase) {
                    Phase.Idle -> DemoWorkout.sets.filter { it.number == 1 }.forEach { set ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(set.exercise, style = MaterialTheme.typography.titleMedium)
                                Text("${set.total} series · ${weightLabel(set.weightTenths)} kg · ${set.reps} reps · RIR ${set.rir}")
                            }
                        }
                    }
                    Phase.ActiveSet -> {
                        val plan: PlannedSet = DemoWorkout.sets[state.index]
                        Text("SERIE ${state.index + 1} / 7", color = colors.primary)
                        Text(plan.exercise, style = MaterialTheme.typography.headlineSmall)
                        Text("Serie ${plan.number} de ${plan.total} · objetivo ${plan.reps} reps · RIR ${plan.rir}")
                        WorkoutControls(state, dispatch)
                    }
                    Phase.Resting -> RestControls(state, now, dispatch)
                    Phase.Completed -> {
                        Text("Entrenamiento terminado", style = MaterialTheme.typography.headlineSmall)
                        Text(if (state.endedEarly) "Terminaste antes de completar el plan. ${state.records.size}/7 series registradas."
                            else "Completaste las 7 series.")
                    }
                }
                if (state.records.isNotEmpty()) {
                    Text("Series registradas · ${state.records.size}/7", style = MaterialTheme.typography.titleLarge)
                    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                    state.records.forEach { record ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${record.exercise} · serie ${record.number}", style = MaterialTheme.typography.titleMedium)
                                Text("${weightLabel(record.values.weightTenths)} kg · ${record.values.reps} reps · RIR ${if (record.values.rir == 4) "4+" else record.values.rir}")
                                Text(dateFormat.format(Date(record.completedAtMillis)), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (state.phase != Phase.Idle) {
                    OutlinedButton(onClick = { dispatch(state.command(WorkoutEvent.UndoLastSet)) },
                        enabled = state.records.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Deshacer última serie") }
                    if (state.phase != Phase.Completed) TextButton(
                        onClick = { dispatch(state.command(WorkoutEvent.FinishWorkout)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Terminar entrenamiento")
                    }
                    TextButton(onClick = { dispatch(state.command(WorkoutEvent.ResetDemo)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Reiniciar demo")
                    }
                }
            }
            if (editorVisible) ModalBottomSheet(onDismissRequest = onDismissEditor) {
                Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Registro rápido", style = MaterialTheme.typography.headlineSmall)
                    when (state.phase) {
                        Phase.ActiveSet -> {
                            Text(DemoWorkout.sets[state.index].exercise)
                            WorkoutControls(state, dispatch)
                        }
                        Phase.Resting -> RestControls(state, now, dispatch)
                        Phase.Completed -> Text("Entrenamiento terminado. Consulta el resumen.")
                        Phase.Idle -> Text("Inicia el demo desde la pantalla principal.")
                    }
                }
            }
        }
    }
}

@Composable
private fun RestControls(state: WorkoutState, now: () -> ClockSnapshot, dispatch: (WorkoutCommand) -> Unit) {
    var seconds: Long by remember(state.rest) { mutableLongStateOf(((state.rest?.remainingMillis(now()) ?: 0) + 999) / 1000) }
    LaunchedEffect(state.rest) {
        while (true) { seconds = ((state.rest?.remainingMillis(now()) ?: 0) + 999) / 1000; delay(250) }
    }
    Text("Descanso", style = MaterialTheme.typography.titleLarge)
    Text("$seconds s", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
    Text("Siguiente: ${DemoWorkout.sets[(state.index + 1).coerceAtMost(6)].exercise}")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { dispatch(state.command(WorkoutEvent.AddRestTime)) }, modifier = Modifier.weight(1f).height(56.dp)) { Text("+10 s") }
        Button(onClick = { dispatch(state.command(WorkoutEvent.SkipRest)) }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Saltar descanso") }
    }
}
