package com.example.workoutpoc.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.data.activePlan
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.AppTheme
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular
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
    archivedCount: Int = 0, onEditRecord: (RecordEdit) -> Unit = {},
    onAddCardio: (CardioAdd) -> Unit = {}, onRemoveCardio: (CardioEntry) -> Unit = {}
) {
    var editing by remember(state.sessionId) { mutableStateOf<Int?>(null) }
    var confirmReset by remember(state.sessionId) { mutableStateOf(false) }
    val plan: List<PlannedSet> = state.activePlan()
    val source: SessionSource? = state.source
    val demo: Boolean = source == null
    val active = state.phase == Phase.ActiveSet || state.phase == Phase.Resting
    val colors = MaterialTheme.colorScheme
    Scaffold(topBar = {
        Column {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Icon(AppIcons.Fitness, contentDescription = null, tint = colors.primary)
                        Text("Auto-Gym")
                    }
                },
                actions = {
                    // Always visible: the library is the way into everything beyond today's session.
                    FilledTonalButton(onClick = onPrograms, modifier = Modifier.padding(end = Spacing.sm)) {
                        Icon(AppIcons.Library, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.sm)); Text("Programas")
                    }
                })
            BusyTrack(!ready)
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = MaxContentWidth).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter).padding(top = Spacing.sm, bottom = Spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                SessionHeader(state, plan, source, compact = active)
                if (error != null) Banner(error, tone = Tone.Error, action = { TextButton(onClick = retry) { Text("Reintentar") } })
                if (active) message?.let { Banner(it, tone = Tone.Info) }
                if (!notificationEnabled) Banner("Notificaciones desactivadas. Puedes registrar aquí; para probar el modo A, habilítalas en Ajustes.",
                    tone = Tone.Warning, action = { TextButton(onClick = onNotificationSettings) { Text("Ajustes de notificaciones") } })
                if (state.phase != Phase.Completed)
                    ModeSection(demo, ready, compact = active, overlayGranted, onNotificationMode, onOverlayMode)
                when (state.phase) {
                    Phase.Idle -> PlanPreview(plan, demo)
                    Phase.ActiveSet -> ActiveSetCard(state, plan, demo, dispatch)
                    Phase.Resting -> state.paused.let { pause ->
                        if (pause == null) FocusCard { RestControls(state, plan, now, dispatch) }
                        else {
                            // Frozen at the pause instant; resuming shifts the deadline by the pause length.
                            val left = state.rest?.let { r -> (if (r.bootCount == pause.bootCount) r.elapsedMillis - pause.elapsedMillis
                                else r.wallMillis - pause.wallMillis).coerceAtLeast(0) } ?: 0
                            FocusCard {
                                Eyebrow("DESCANSO EN PAUSA", color = colors.onSurfaceVariant)
                                Text("Descanso restante al reanudar: ${(left + 999) / 1000} s", style = MaterialTheme.typography.titleMedium.tabular())
                            }
                        }
                    }
                    Phase.Completed -> CompletedCard(state, plan)
                }
                SessionStatus(state, now, dispatch)
                if (state.paused == null) DeviationActions(state, dispatch)
                CardioSection(state, onAddCardio, onRemoveCardio)
                if (state.records.isNotEmpty()) RecordList(state, plan, demo) { editing = it }
                if (state.phase != Phase.Idle || !demo) SessionEndActions(state, demo, archivedCount, dispatch) {
                    // Resetting the demo discards its records; closing a planned session archives them first.
                    if (demo && state.records.isNotEmpty()) confirmReset = true else dispatch(state.command(WorkoutEvent.ResetDemo))
                } else if (archivedCount > 0) SupportingText("Sesiones archivadas en este dispositivo: $archivedCount", small = true)
            }
        }
        editing?.let { index ->
            RecordEditorDialog(state, index, { editing = null }) { edit -> editing = null; onEditRecord(edit) }
        }
        if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
            icon = { Icon(AppIcons.Warning, contentDescription = null) },
            title = { Text("¿Reiniciar el demo?") },
            text = { Text((if (state.records.size == 1) "Se borrará la serie registrada" else "Se borrarán las ${state.records.size} series registradas") +
                " de esta sesión demo. No se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; dispatch(state.command(WorkoutEvent.ResetDemo)) },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) { Text("Reiniciar demo") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancelar") } })
        if (editorVisible) ModalBottomSheet(onDismissRequest = onDismissEditor) {
            Column(Modifier.padding(horizontal = Spacing.xl).verticalScroll(rememberScrollState()).padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Text("Registro rápido", style = MaterialTheme.typography.headlineSmall)
                when (state.phase) {
                    Phase.ActiveSet -> {
                        Text(plan[state.index].exercise, style = MaterialTheme.typography.titleMedium)
                        WorkoutControls(state, dispatch)
                    }
                    Phase.Resting -> RestControls(state, plan, now, dispatch)
                    Phase.Completed -> SupportingText("Entrenamiento terminado. Consulta el resumen.")
                    Phase.Idle -> SupportingText(if (demo) "Inicia el demo desde la pantalla principal." else "Inicia la sesión desde la pantalla principal.")
                }
            }
        }
    }
}

@Composable
private fun SessionHeader(state: WorkoutState, plan: List<PlannedSet>, source: SessionSource?, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Eyebrow(if (source == null) "WORKOUT / POC" else "SESIÓN PLANIFICADA")
        if (!compact) Text(if (state.phase == Phase.Completed) "Sesión completada." else "Tu siguiente serie.",
            style = MaterialTheme.typography.headlineMedium)
        SupportingText(if (source == null) "3 ejercicios · 7 series · descansos de 10 s"
            else "${source.programName} · ${source.weekName} · ${source.dayName}\n" +
                "${plan.distinctBy { it.exerciseId to it.exercise }.size} ejercicios · ${plan.size} series")
    }
}

/**
 * Where to log from. Before the session these are the main calls to action; during it they become a compact
 * switch so the set controls stay near the top of the screen.
 */
@Composable
private fun ModeSection(demo: Boolean, ready: Boolean, compact: Boolean, overlayGranted: Boolean,
                        onNotificationMode: () -> Unit, onOverlayMode: () -> Unit) {
    val notification = if (demo) "Iniciar demo — Notificación" else "Iniciar — Notificación"
    val overlay = if (demo) "Iniciar demo — Burbuja flotante" else "Iniciar — Burbuja flotante"
    val hint = if (overlayGranted) "Panel flotante autorizado. Cambia de modo sin perder tu progreso."
        else "El modo burbuja necesita permiso para mostrarse sobre otras aplicaciones."
    if (compact) Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("Registrar desde", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ModeButton(notification, AppIcons.Notifications, ready, onNotificationMode, Modifier.weight(1f).fillMaxHeight())
            ModeButton(overlay, AppIcons.Bubble, ready, onOverlayMode, Modifier.weight(1f).fillMaxHeight())
        }
        // No caption here: during a set every line pushes "Completar serie" further from reach.
    } else Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        PrimaryAction(notification, onNotificationMode, enabled = ready, icon = AppIcons.Notifications, tall = true)
        SecondaryAction(overlay, onOverlayMode, enabled = ready, icon = AppIcons.Bubble, tall = true)
        SupportingText(hint, small = true)
    }
}

@Composable
private fun ModeButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean,
                       onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 56.dp),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(Spacing.sm))
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Start)
    }
}

/** The one card that holds what to do right now; visually the strongest element on the screen. */
@Composable
private fun FocusCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md), content = content)
    }
}

@Composable
private fun PlanPreview(plan: List<PlannedSet>, demo: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader("Plan de la sesión")
        plan.filter { it.number == 1 }.forEachIndexed { i, set ->
            ContentCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    NumberBadge("${i + 1}")
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(set.exercise, style = MaterialTheme.typography.titleMedium)
                        Text(if (demo) "${set.total} series · ${weightLabel(set.weightTenths)} kg · ${set.reps} reps · RIR ${set.rir}"
                            else "${set.total} series · ${prescriptionLabel(set)}",
                            style = MaterialTheme.typography.bodyMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberBadge(text: String) {
    Surface(Modifier.size(36.dp), shape = androidx.compose.foundation.shape.CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Box(contentAlignment = Alignment.Center) { Text(text, style = MaterialTheme.typography.labelLarge.tabular()) }
    }
}

@Composable
private fun SessionProgress(state: WorkoutState, plan: List<PlannedSet>) {
    val done = state.records.size
    LinearProgressIndicator(progress = { if (plan.isEmpty()) 0f else done.toFloat() / plan.size },
        modifier = Modifier.fillMaxWidth().height(6.dp).semantics { contentDescription = "$done de ${plan.size} series registradas" },
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {})
}

@Composable
private fun ActiveSetCard(state: WorkoutState, plan: List<PlannedSet>, demo: Boolean, dispatch: (WorkoutCommand) -> Unit) {
    val current: PlannedSet = plan[state.index]
    FocusCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SERIE ${state.index + 1} / ${plan.size}", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge.tabular(), modifier = Modifier.weight(1f))
            if (current.warmup) StatusPill("Aproximación") else if (current.extra) StatusPill("Extra", Tone.Warning)
        }
        SessionProgress(state, plan)
        Text(current.exercise, style = MaterialTheme.typography.headlineSmall)
        SupportingText(when {
            demo -> "Serie ${current.number} de ${current.total} · objetivo ${current.reps} reps · RIR ${current.rir}"
            current.warmup -> "Aproximación ${current.number} · sin prescripción; ajusta carga y repeticiones"
            current.extra -> "Serie extra ${current.number} · añadida, sin prescripción"
            else -> "Serie ${current.number} de ${current.total} · prescrito: ${prescriptionLabel(current)}"
        })
        if (state.paused == null) WorkoutControls(state, dispatch)
    }
}

@Composable
private fun CompletedCard(state: WorkoutState, plan: List<PlannedSet>) {
    val x = AppTheme.extended
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = x.successContainer, contentColor = x.onSuccessContainer) {
        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(AppIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(32.dp))
            Text("Entrenamiento terminado", style = MaterialTheme.typography.headlineSmall)
            Text(if (state.endedEarly) "Terminaste antes de completar el plan. ${state.records.size}/${plan.size} series registradas."
                else "Completaste las ${plan.size} series.", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun RecordList(state: WorkoutState, plan: List<PlannedSet>, demo: Boolean, onEdit: (Int) -> Unit) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader("Series registradas · ${state.records.size}/${plan.size}")
        state.records.forEach { record ->
            val planned: PlannedSet = plan[record.index]
            ContentCard {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    NumberBadge("${record.index + 1}")
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${record.exercise} · ${when { planned.warmup -> "aproximación"; planned.extra -> "serie extra"; else -> "serie" }} ${record.number}",
                            style = MaterialTheme.typography.titleSmall)
                        if (record.status == SetStatus.SKIPPED) Text("Omitida", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Text("${if (demo) "" else "Real: "}${loadLabel(record.values.weightTenths, planned.unit)} · ${record.values.reps} reps · RIR ${rirLabel(record.values.rir)}",
                            style = MaterialTheme.typography.bodyLarge.tabular())
                        if (!demo) Text(if (planned.extra) "Sin prescripción" else "Prescrito: ${prescriptionLabel(planned)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        record.notes?.let { Text("Nota: $it", style = MaterialTheme.typography.bodyMedium) }
                        Text(dateFormat.format(Date(record.completedAtMillis)) + (if (record.editedAtMillis != null) " · editada" else ""),
                            style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!demo) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    QuietAction("Editar serie ${record.index + 1}", { onEdit(record.index) }, icon = AppIcons.Edit)
                }
            }
        }
    }
}

@Composable
private fun SessionEndActions(state: WorkoutState, demo: Boolean, archivedCount: Int, dispatch: (WorkoutCommand) -> Unit, onReset: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        HorizontalDivider(Modifier.padding(vertical = Spacing.sm), color = MaterialTheme.colorScheme.outlineVariant)
        if (state.phase != Phase.Idle) {
            SecondaryAction("Deshacer última serie", { dispatch(state.command(WorkoutEvent.UndoLastSet)) },
                enabled = state.records.isNotEmpty(), icon = AppIcons.Undo)
            if (state.phase != Phase.Completed)
                TonalAction("Terminar entrenamiento", { dispatch(state.command(WorkoutEvent.FinishWorkout)) }, icon = AppIcons.Stop)
        }
        if (archivedCount > 0) SupportingText("Sesiones archivadas en este dispositivo: $archivedCount", small = true)
        QuietAction(if (demo) "Reiniciar demo" else "Cerrar sesión y volver al demo", onReset, destructive = demo, fill = true)
        if (!demo && state.records.isNotEmpty())
            SupportingText("Las series registradas se archivan en este dispositivo antes de cerrar.", small = true)
    }
}

@Composable
private fun RestControls(state: WorkoutState, plan: List<PlannedSet>, now: () -> ClockSnapshot, dispatch: (WorkoutCommand) -> Unit) {
    var seconds: Long by remember(state.rest) { mutableLongStateOf(((state.rest?.remainingMillis(now()) ?: 0) + 999) / 1000) }
    // The ring is relative to the rest as last set (+10 s restarts it from the new remaining time).
    val peak = remember(state.rest) { seconds.coerceAtLeast(1) }
    LaunchedEffect(state.rest) {
        while (true) { seconds = ((state.rest?.remainingMillis(now()) ?: 0) + 999) / 1000; delay(250) }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text("Descanso", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Start))
        Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
            // Glides between whole-second ticks instead of jumping.
            val ring by androidx.compose.animation.core.animateFloatAsState((seconds.toFloat() / peak).coerceIn(0f, 1f),
                androidx.compose.animation.core.tween(950, easing = androidx.compose.animation.core.LinearEasing), label = "rest")
            CircularProgressIndicator(progress = { ring }, modifier = Modifier.fillMaxSize(),
                strokeWidth = 10.dp, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, gapSize = 0.dp)
            Text("$seconds s", style = MaterialTheme.typography.displayLarge.tabular(), color = MaterialTheme.colorScheme.primary)
        }
        Text("Siguiente: ${plan[(state.index + 1).coerceAtMost(plan.lastIndex)].exercise}", style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.align(Alignment.Start))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            OutlinedButton(onClick = { dispatch(state.command(WorkoutEvent.AddRestTime)) }, modifier = Modifier.weight(1f).height(56.dp)) {
                Icon(AppIcons.Timer, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(Spacing.sm)); Text("+10 s")
            }
            Button(onClick = { dispatch(state.command(WorkoutEvent.SkipRest)) }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Saltar descanso") }
        }
    }
}

/** Prescription as planned, separate from the values being recorded. Unstated fields stay unstated. */
fun prescriptionLabel(p: PlannedSet): String {
    val load = if (!p.loadPrescribed) "carga sin indicar"
        else loadLabel(p.weightTenths, p.unit) + when (p.basis) { "PER_HAND" -> " por mano"; "PER_SIDE" -> " por lado"; "TOTAL" -> " total"; else -> "" }
    return "${if (p.reps > 0) "${p.reps} reps" else "reps sin indicar"} · ${p.rir?.let { "RIR ${rirLabel(it)}" } ?: "RIR sin indicar"} · $load · descanso ${p.restSeconds} s"
}
