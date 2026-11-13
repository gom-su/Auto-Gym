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
import com.example.workoutpoc.history.review
import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.AppTheme
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular
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
    now: () -> ClockSnapshot, dispatch: (WorkoutCommand) -> Unit, onBack: () -> Unit,
    archivedCount: Int = 0, onEditRecord: (RecordEdit) -> Unit = {},
    onAddCardio: (CardioAdd) -> Unit = {}, onRemoveCardio: (CardioEntry) -> Unit = {},
    /** The default logging mode in Ajustes is the floating panel: offer it first. */
    overlayFirst: Boolean = false,
    onFinalize: () -> Unit = {},
    /** Saves a comment for the planned session (contract §7); exerciseId is null for a general comment. */
    onSaveComment: (text: String, exerciseId: String?) -> Unit = { _, _ -> },
    catalog: List<com.example.workoutpoc.catalog.CatalogExercise> = emptyList(),
    recordEditorRequest: RecordEditorRequest? = null, onConsumeRecordEditorRequest: () -> Unit = {}
) {
    var editing by remember(state.sessionId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(recordEditorRequest, ready) {
        val request = recordEditorRequest ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        if (request.sessionId == state.sessionId && state.records.any { it.index == request.index }) editing = request.index
        onDismissEditor()
        onConsumeRecordEditorRequest()
    }
    var confirmReset by remember(state.sessionId) { mutableStateOf(false) }
    val plan: List<PlannedSet> = state.activePlan()
    val source: SessionSource? = state.source
    val demo: Boolean = source == null
    val active = state.phase.inProgress
    val colors = MaterialTheme.colorScheme
    Scaffold(topBar = {
        Column {
            TopAppBar(
                title = { Text(if (demo) "Demo" else "Sesión") },
                // Leaving keeps the session running; Hoy shows the way back to it.
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "Volver a Hoy") } })
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
                    ModeSection(demo, ready, compact = active, overlayGranted, onNotificationMode, onOverlayMode, overlayFirst)
                when (state.phase) {
                    Phase.Idle -> {
                        if (plan.isNotEmpty()) PlanPreview(plan, demo)
                        state.cardioPlan.forEach { step ->
                            SupportingText("${step.modality} · ${durationLabel(step.plannedSeconds * 1000L)}")
                        }
                    }
                    Phase.ActiveSet -> ActiveSetCard(state, plan, demo, now, catalog, dispatch)
                    Phase.ActiveCardio -> FocusCard { CardioSurface(state, now, dispatch) }
                    Phase.Resting -> FocusCard { RestControls(state, now, catalog, dispatch) { editing = it } }
                    Phase.Completed -> {
                        CompletedCard(state, plan)
                        if (!demo) SessionReviewContent(remember(state) { state.review() })
                    }
                }
                SessionStatus(state, now, dispatch)
                if (state.paused == null && state.runningWait == null && plan.isNotEmpty() && state.phase != Phase.ActiveCardio) DeviationActions(state, dispatch)
                CardioSection(state, onAddCardio, onRemoveCardio)
                // Comments during a planned session are linked to an exercise or the whole session (§5, decision 2).
                if (!demo && state.source != null && state.phase != Phase.Idle) SessionCommentCard(state, onSaveComment)
                if (state.records.isNotEmpty()) RecordList(state, plan, demo) { editing = it }
                if (state.phase != Phase.Idle || !demo) SessionEndActions(state, demo, archivedCount, dispatch) {
                    // Resetting the demo discards its records; closing a planned session archives them first.
                    if (!demo && state.phase == Phase.Completed) onFinalize()
                    else if (demo && state.records.isNotEmpty()) confirmReset = true
                    else dispatch(state.command(WorkoutEvent.ResetDemo))
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
                        ExerciseWaitControls(state, now, dispatch)
                    }
                    Phase.ActiveCardio -> CardioSurface(state, now, dispatch)
                    Phase.Resting -> RestControls(state, now, catalog, dispatch) { editing = it; onDismissEditor() }
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
        if (!compact) Text(when {
            state.phase == Phase.Completed -> "Sesión terminada"
            plan.isEmpty() -> "Cardio planificado"
            else -> "Tu siguiente serie"
        },
            style = MaterialTheme.typography.headlineMedium)
        SupportingText(if (source == null) "3 ejercicios · 7 series · descansos de 10 s"
            else "${source.programName} · ${source.weekName} · ${source.dayName}\n" +
                listOfNotNull("${plan.distinctBy { it.exerciseId to it.exercise }.size} ejercicios · ${plan.size} series".takeIf { plan.isNotEmpty() },
                    "${state.cardioPlan.size} de cardio".takeIf { state.cardioPlan.isNotEmpty() }).joinToString(" · "))
    }
}

/**
 * Where to log from. Before the session these are the main calls to action; during it they become a compact
 * switch so the set controls stay near the top of the screen.
 */
@Composable
private fun ModeSection(demo: Boolean, ready: Boolean, compact: Boolean, overlayGranted: Boolean,
                        onNotificationMode: () -> Unit, onOverlayMode: () -> Unit, overlayFirst: Boolean) {
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
        // The default mode from Ajustes is the primary action; the other stays one tap away.
        if (overlayFirst) {
            PrimaryAction(overlay, onOverlayMode, enabled = ready, icon = AppIcons.Bubble, tall = true)
            SecondaryAction(notification, onNotificationMode, enabled = ready, icon = AppIcons.Notifications, tall = true)
        } else {
            PrimaryAction(notification, onNotificationMode, enabled = ready, icon = AppIcons.Notifications, tall = true)
            SecondaryAction(overlay, onOverlayMode, enabled = ready, icon = AppIcons.Bubble, tall = true)
        }
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
internal fun FocusCard(content: @Composable ColumnScope.() -> Unit) {
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
private fun ActiveSetCard(state: WorkoutState, plan: List<PlannedSet>, demo: Boolean, now: () -> ClockSnapshot, catalog: List<com.example.workoutpoc.catalog.CatalogExercise>, dispatch: (WorkoutCommand) -> Unit) {
    val current: PlannedSet = plan[state.index]
    FocusCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SERIE ${state.index + 1} / ${plan.size}", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge.tabular(), modifier = Modifier.weight(1f))
            StatusPill(current.setType.surfaceLabel())
            if (current.extra) StatusPill("Extra", Tone.Warning)
        }
        SessionProgress(state, plan)
        Text(current.exercise, style = MaterialTheme.typography.headlineSmall)
        SupportingText(when {
            demo -> "Serie ${current.number} de ${current.total} · objetivo ${current.reps} reps · RIR ${current.rir}"
            current.setType != SetType.WORK -> "${current.setType.surfaceLabel()} ${current.number} · ${prescriptionLabel(current)}"
            current.extra -> "Serie extra ${current.number} · añadida, sin prescripción"
            else -> "Serie ${current.number} de ${current.total} · prescrito: ${prescriptionLabel(current)}"
        })
        SeriesNoticeCard(state.seriesNotice(catalog))
        if (state.paused == null) WorkoutControls(state, dispatch)
        ExerciseWaitControls(state, now, dispatch)
    }
}

@Composable
private fun CompletedCard(state: WorkoutState, plan: List<PlannedSet>) {
    val x = AppTheme.extended
    val partial = state.endedEarly || !state.plannedCardioComplete ||
        state.records.size != plan.size || state.records.any { it.status == SetStatus.SKIPPED }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = x.successContainer, contentColor = x.onSuccessContainer) {
        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(AppIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(32.dp))
            Text(if (partial) "Entrenamiento parcial" else "Entrenamiento terminado", style = MaterialTheme.typography.headlineSmall)
            Text(if (partial) "Resultado guardado."
                else if (plan.isEmpty()) "Completaste el cardio planificado."
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
                        Text("${record.exercise} · ${planned.setType.surfaceLabel()}${if (planned.extra) " extra" else ""} ${record.number}",
                            style = MaterialTheme.typography.titleSmall)
                        if (record.status == SetStatus.SKIPPED) Text("Omitida", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Text("${if (demo) "" else "Real: "}${loadLabel(record.values.weightTenths, record.recordedUnit ?: planned.unit)} · ${record.values.reps} reps${if (planned.setType == SetType.WORK) " · RIR ${rirLabel(record.values.rir)}" else ""}",
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
                enabled = state.records.isNotEmpty() && state.phase != Phase.ActiveCardio && state.runningWait == null, icon = AppIcons.Undo)
            if (state.phase != Phase.Completed && state.runningWait == null)
                TonalAction("Terminar entrenamiento", { dispatch(state.command(WorkoutEvent.FinishWorkout)) }, icon = AppIcons.Stop)
        }
        if (archivedCount > 0) SupportingText("Sesiones archivadas en este dispositivo: $archivedCount", small = true)
        if (!demo && state.phase == Phase.Completed) PrimaryAction("Finalizar entrenamiento", onReset, icon = AppIcons.Check, tall = true)
        else if (demo || state.phase == Phase.Idle) QuietAction(if (demo) "Reiniciar demo" else "Cerrar sesión", onReset, destructive = demo, fill = true)
        if (!demo && state.records.isNotEmpty())
            SupportingText("Las series registradas se archivan en este dispositivo antes de cerrar.", small = true)
    }
}

@Composable
private fun RestControls(state: WorkoutState, now: () -> ClockSnapshot,
                         catalog: List<com.example.workoutpoc.catalog.CatalogExercise>, dispatch: (WorkoutCommand) -> Unit,
                         onEdit: (Int) -> Unit) {
    val timer = liveSurfaceTimer(state, now) ?: return
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(timer.label + if (state.paused != null) " · en pausa" else "", style = MaterialTheme.typography.titleLarge)
        Text(timer.text, style = MaterialTheme.typography.displayLarge.tabular(), color = MaterialTheme.colorScheme.primary)
        SeriesNoticeCard(state.seriesNotice(catalog))
        if (state.paused == null && !timer.waiting) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                if (state.openRestSegment != null) OutlinedButton(onClick = { dispatch(state.command(WorkoutEvent.ReduceRestTime)) }, modifier = Modifier.weight(1f)) { Text("−15 s") }
                OutlinedButton(onClick = { dispatch(state.command(WorkoutEvent.AddRestTime)) }, modifier = Modifier.weight(1f)) { Text(if (state.source == null) "+10 s" else "+15 s") }
            }
            val expired = state.source != null && (state.restSignedBalance(now()) ?: 1) <= 0
            Button(onClick = { dispatch(state.command(if (expired) WorkoutEvent.ConfirmRest else WorkoutEvent.SkipRest)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(if (expired) "OK" else "Saltar descanso") }
        }
        WaitActions(state, timer, dispatch)
        if (state.source != null) state.records.lastOrNull()?.let { record ->
            OutlinedButton(onClick = { onEdit(record.index) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Editar última serie") }
        }
    }
}

/** Prescription as planned, separate from the values being recorded. Unstated fields stay unstated. */
fun prescriptionLabel(p: PlannedSet): String {
    val load = if (!p.loadPrescribed) "carga sin indicar"
        else loadLabel(p.weightTenths, p.unit) + when (p.basis) { "PER_HAND" -> " por mano"; "PER_SIDE" -> " por lado"; "TOTAL" -> " total"; else -> "" }
    return "${if (p.reps > 0) "${p.reps} reps" else "reps sin indicar"}${if (p.setType == SetType.WORK) " · " + (p.rir?.let { "RIR ${rirLabel(it)}" } ?: "RIR sin indicar") else ""} · $load · descanso ${p.restSeconds} s"
}
