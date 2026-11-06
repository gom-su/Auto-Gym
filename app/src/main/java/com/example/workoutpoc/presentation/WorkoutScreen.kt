package com.example.workoutpoc.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.data.activePlan
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
    now: () -> ClockSnapshot, dispatch: (WorkoutCommand) -> Unit, onPrograms: () -> Unit,
    archivedCount: Int = 0, onEditRecord: (RecordEdit) -> Unit = {}
) {
    var editing by remember(state.sessionId) { mutableStateOf<Int?>(null) }
    val plan: List<PlannedSet> = state.activePlan()
    val source: SessionSource? = state.source
    val demo: Boolean = source == null
    val colors = darkColorScheme(primary = Color(0xFFB4EE80), onPrimary = Color(0xFF17220F),
        background = Color(0xFF101612), surface = Color(0xFF18221C), surfaceContainer = Color(0xFF243028))
    MaterialTheme(colorScheme = colors) {
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (demo) "WORKOUT / POC" else "SESIÓN PLANIFICADA", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                Text("Tu siguiente serie.", style = MaterialTheme.typography.headlineLarge)
                Text(if (source == null) "3 ejercicios · 7 series · descansos de 10 s"
                    else "${source.programName} · ${source.weekName} · ${source.dayName}\n" +
                        "${plan.distinctBy { it.exerciseId to it.exercise }.size} ejercicios · ${plan.size} series",
                    style = MaterialTheme.typography.bodyMedium)
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
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(if (demo) "Iniciar demo — Notificación" else "Iniciar — Notificación") }
                OutlinedButton(onClick = onOverlayMode, enabled = ready && state.phase != Phase.Completed,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(if (demo) "Iniciar demo — Burbuja flotante" else "Iniciar — Burbuja flotante") }
                Text(if (overlayGranted) "Panel flotante autorizado. Cambia de modo sin perder tu progreso."
                    else "El modo burbuja necesita permiso para mostrarse sobre otras aplicaciones.",
                    style = MaterialTheme.typography.bodySmall)
                when (state.phase) {
                    Phase.Idle -> plan.filter { it.number == 1 }.forEach { set ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(set.exercise, style = MaterialTheme.typography.titleMedium)
                                Text(if (demo) "${set.total} series · ${weightLabel(set.weightTenths)} kg · ${set.reps} reps · RIR ${set.rir}"
                                    else "${set.total} series · ${prescriptionLabel(set)}")
                            }
                        }
                    }
                    Phase.ActiveSet -> {
                        val current: PlannedSet = plan[state.index]
                        Text("SERIE ${state.index + 1} / ${plan.size}", color = colors.primary)
                        Text(current.exercise, style = MaterialTheme.typography.headlineSmall)
                        Text(when {
                            demo -> "Serie ${current.number} de ${current.total} · objetivo ${current.reps} reps · RIR ${current.rir}"
                            current.extra -> "Serie extra ${current.number} · añadida, sin prescripción"
                            else -> "Serie ${current.number} de ${current.total} · prescrito: ${prescriptionLabel(current)}"
                        })
                        WorkoutControls(state, dispatch)
                    }
                    Phase.Resting -> RestControls(state, plan, now, dispatch)
                    Phase.Completed -> {
                        Text("Entrenamiento terminado", style = MaterialTheme.typography.headlineSmall)
                        Text(if (state.endedEarly) "Terminaste antes de completar el plan. ${state.records.size}/${plan.size} series registradas."
                            else "Completaste las ${plan.size} series.")
                    }
                }
                DeviationActions(state, dispatch)
                if (state.records.isNotEmpty()) {
                    Text("Series registradas · ${state.records.size}/${plan.size}", style = MaterialTheme.typography.titleLarge)
                    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                    state.records.forEach { record ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val planned: PlannedSet = plan[record.index]
                                Text("${record.exercise} · ${if (planned.extra) "serie extra" else "serie"} ${record.number}", style = MaterialTheme.typography.titleMedium)
                                if (record.status == SetStatus.SKIPPED) Text("Omitida")
                                else Text("${if (demo) "" else "Real: "}${loadLabel(record.values.weightTenths, planned.unit)} · ${record.values.reps} reps · RIR ${rirLabel(record.values.rir)}")
                                if (!demo) Text(if (planned.extra) "Sin prescripción" else "Prescrito: ${prescriptionLabel(planned)}", style = MaterialTheme.typography.bodySmall)
                                record.notes?.let { Text("Nota: $it") }
                                Text(dateFormat.format(Date(record.completedAtMillis)) + (if (record.editedAtMillis != null) " · editada" else ""),
                                    style = MaterialTheme.typography.bodySmall)
                                if (!demo) TextButton(onClick = { editing = record.index }) { Text("Editar serie ${record.index + 1}") }
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
                }
                if (archivedCount > 0) Text("Sesiones archivadas en este dispositivo: $archivedCount", style = MaterialTheme.typography.bodySmall)
                if (state.phase != Phase.Idle || !demo) {
                    TextButton(onClick = { dispatch(state.command(WorkoutEvent.ResetDemo)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (demo) "Reiniciar demo" else "Cerrar sesión y volver al demo")
                    }
                    if (!demo && state.records.isNotEmpty())
                        Text("Las series registradas se archivan en este dispositivo antes de cerrar.", style = MaterialTheme.typography.bodySmall)
                }
            }
            editing?.let { index ->
                RecordEditorDialog(state, index, { editing = null }) { edit -> editing = null; onEditRecord(edit) }
            }
            if (editorVisible) ModalBottomSheet(onDismissRequest = onDismissEditor) {
                Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Registro rápido", style = MaterialTheme.typography.headlineSmall)
                    when (state.phase) {
                        Phase.ActiveSet -> {
                            Text(plan[state.index].exercise)
                            WorkoutControls(state, dispatch)
                        }
                        Phase.Resting -> RestControls(state, plan, now, dispatch)
                        Phase.Completed -> Text("Entrenamiento terminado. Consulta el resumen.")
                        Phase.Idle -> Text(if (demo) "Inicia el demo desde la pantalla principal." else "Inicia la sesión desde la pantalla principal.")
                    }
                }
            }
        }
    }
}

@Composable
private fun RestControls(state: WorkoutState, plan: List<PlannedSet>, now: () -> ClockSnapshot, dispatch: (WorkoutCommand) -> Unit) {
    var seconds: Long by remember(state.rest) { mutableLongStateOf(((state.rest?.remainingMillis(now()) ?: 0) + 999) / 1000) }
    LaunchedEffect(state.rest) {
        while (true) { seconds = ((state.rest?.remainingMillis(now()) ?: 0) + 999) / 1000; delay(250) }
    }
    Text("Descanso", style = MaterialTheme.typography.titleLarge)
    Text("$seconds s", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
    Text("Siguiente: ${plan[(state.index + 1).coerceAtMost(plan.lastIndex)].exercise}")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { dispatch(state.command(WorkoutEvent.AddRestTime)) }, modifier = Modifier.weight(1f).height(56.dp)) { Text("+10 s") }
        Button(onClick = { dispatch(state.command(WorkoutEvent.SkipRest)) }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Saltar descanso") }
    }
}

/** Prescription as planned, separate from the values being recorded. Unstated fields stay unstated. */
fun prescriptionLabel(p: PlannedSet): String {
    val load = if (!p.loadPrescribed) "carga sin indicar"
        else loadLabel(p.weightTenths, p.unit) + when (p.basis) { "PER_HAND" -> " por mano"; "PER_SIDE" -> " por lado"; "TOTAL" -> " total"; else -> "" }
    return "${if (p.reps > 0) "${p.reps} reps" else "reps sin indicar"} · ${p.rir?.let { "RIR ${rirLabel(it)}" } ?: "RIR sin indicar"} · $load · descanso ${p.restSeconds} s"
}
