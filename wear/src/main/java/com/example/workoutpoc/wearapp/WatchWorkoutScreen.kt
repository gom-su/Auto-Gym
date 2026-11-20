package com.example.workoutpoc.wearapp

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.example.workoutpoc.wearprotocol.PanelAction
import com.example.workoutpoc.wearprotocol.PanelControl
import com.example.workoutpoc.wearprotocol.PanelMode
import com.example.workoutpoc.wearprotocol.PanelSnapshot
import com.example.workoutpoc.wearprotocol.WatchLoadUnit
import com.example.workoutpoc.wearprotocol.WatchSet
import com.example.workoutpoc.wearprotocol.WatchSetValues

private enum class EditorField { WEIGHT, REPS, RIR, STEP }

@Composable
internal fun WatchWorkoutScreen(
    state: WearPanelState,
    snapshot: PanelSnapshot,
    monitorMessage: String?,
    ambient: Boolean,
    onAction: (PanelControl, PanelSnapshot) -> Unit,
    onSubmitSet: (PanelAction, PanelSnapshot, WatchSetValues, Int?) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    val workout = checkNotNull(snapshot.watch)
    val active = workout.activeSet
    val last = workout.lastSet
    val ready = state.connected && !state.busy
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("watch_workout", Context.MODE_PRIVATE) }
    var weightStep by remember(preferences) { mutableIntStateOf(preferences.getInt("weight_step", 0)) }
    var draft by remember(snapshot.sessionId, snapshot.index, snapshot.revision, active) { mutableStateOf(active?.values) }
    var rirConfirmed by remember(snapshot.sessionId, snapshot.index, snapshot.revision, active) { mutableStateOf(false) }
    var reviewing by remember(snapshot.sessionId, last) { mutableStateOf(false) }
    var correction by remember(snapshot.sessionId, last) { mutableStateOf(last?.values) }
    val editor = if (reviewing) last else active
    val values = if (reviewing) correction else draft
    var field by remember(snapshot.sessionId, editor, reviewing, snapshot.paused) { mutableStateOf<EditorField?>(null) }
    val picker = field
    val rest = snapshot.mode == PanelMode.REST && !snapshot.paused
    val primary = snapshot.primaryAction()
    val fire: (PanelControl) -> Unit = { onAction(it, snapshot) }

    BackHandler(!ambient && (picker != null || reviewing)) {
        if (picker != null) field = null else reviewing = false
    }
    if (ambient) {
        AmbientWorkout(state, snapshot)
        return
    }

    MaterialTheme(colorScheme = watchColors) {
        if (picker != null && editor != null && values != null) {
            val apply: (WatchSetValues) -> Unit = {
                if (reviewing) correction = it else draft = it
                field = null
            }
            val step = weightStep.takeIf { it > 0 && editor.loadUnit != WatchLoadUnit.MACHINE_SETTING }
                ?: editor.weightStepTenths
            val confirmLabel = if (reviewing) "Aplicar" else "Confirmar"
            val canEdit = ready && (reviewing && editor.editable || !reviewing && !snapshot.paused)
            when (picker) {
                EditorField.WEIGHT -> {
                    val loads = remember(values.weightTenths, step, editor.maxWeightTenths) {
                        LoadOptions(values.weightTenths, step, editor.maxWeightTenths)
                    }
                    WatchNumberPicker("Carga", "Paso ${editor.loadStepLabel(step)}", loads.size, loads.selectedIndex,
                        canEdit, confirmLabel, { editor.loadLabel(loads[it]) }, { apply(values.copy(weightTenths = loads[it])) })
                }
                EditorField.REPS -> {
                    val minimum = if (reviewing) 1 else 0
                    WatchNumberPicker("Repeticiones", null, editor.maxReps - minimum + 1, values.reps - minimum,
                        canEdit, confirmLabel, { (it + minimum).toString() }, { apply(values.copy(reps = it + minimum)) })
                }
                EditorField.RIR -> WatchRirPicker(values.rir, canEdit) {
                    if (!reviewing) rirConfirmed = true
                    apply(values.copy(rir = it))
                }
                EditorField.STEP -> WatchNumberPicker(
                    "Paso de carga", null, loadSteps.size, loadSteps.indexOf(weightStep).coerceAtLeast(0),
                    true, "Guardar", { loadSteps[it].takeIf { step -> step != 0 }?.let(editor::loadStepLabel) ?: "Del plan" },
                    {
                        weightStep = loadSteps[it]
                        preferences.edit().putInt("weight_step", weightStep).apply()
                        field = null
                    },
                )
            }
            return@MaterialTheme
        }

        val review: () -> Unit = {
            correction = last?.values
            reviewing = true
        }
        val canReview = last != null && !reviewing
        val currentReview by rememberUpdatedState(review)
        val reviewFromGesture = remember { { currentReview() } }
        val scroll = rememberScrollState()
        LaunchedEffect(snapshot.sessionId, snapshot.index, snapshot.mode, reviewing) { scroll.scrollTo(0) }
        BoxWithConstraints(Modifier.fillMaxSize().background(watchColors.background)) {
            val screenHeight = maxHeight
            Column(
                Modifier.fillMaxSize().testTag("watch_panel")
                    .then(if (canReview) Modifier.reviewGesture(reviewFromGesture) else Modifier)
                    .verticalScroll(scroll),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    reviewing && last != null && values != null -> WorkoutFace(screenHeight) {
                        WatchHeading("Última serie", "${last.exercise} · ${last.number}/${last.total}")
                        SetValuesRow(last, values, ready && last.editable) { field = it }
                        if (last.requiresRir) RirButton(values.rir, true, ready && last.editable) { field = EditorField.RIR }
                        if (last.editable) {
                            FaceButton("Guardar cambios", ready && values != last.values && values.reps > 0 &&
                                (!last.requiresRir || values.rir != null)) {
                                onSubmitSet(PanelAction.CORRECT_WATCH_SET, snapshot, values, last.index)
                            }
                        } else {
                            FaceButton("Volver", true) { reviewing = false }
                        }
                        WatchStatus(state)
                    }

                    active != null && draft != null && !snapshot.paused -> {
                        val current = checkNotNull(draft)
                        WorkoutFace(screenHeight, (active.number - 1).toFloat() / active.total.coerceAtLeast(1)) {
                            WatchHeading(active.exercise, active.progressLabel())
                            SetValuesRow(active, current, ready) { field = it }
                            if (active.requiresRir) RirButton(current.rir, rirConfirmed, ready) { field = EditorField.RIR }
                            FaceButton("Completar serie", ready && current.reps > 0 && (!active.requiresRir || rirConfirmed)) {
                                onSubmitSet(PanelAction.RECORD_WATCH_SET, snapshot, current, null)
                            }
                            WatchStatus(state)
                        }
                    }

                    rest -> WorkoutFace(screenHeight, last?.let { it.number.toFloat() / it.total.coerceAtLeast(1) }) {
                        WatchHeading(last?.let { "Serie ${it.number} completada" } ?: "Descanso")
                        WatchTimer(state.timerText ?: "—", color = if (state.connected) watchColors.primary else watchColors.onSurfaceVariant)
                        WatchCaption(if (snapshot.alert) "Descanso terminado" else "Descanso restante")
                        if (workout.nextTitle == last?.exercise && workout.nextDetail != null) {
                            WatchCaption("Siguiente · ${workout.nextDetail}", Modifier.fillMaxWidth(0.82f))
                        } else {
                            workout.nextTitle?.let { WatchCaption("Siguiente · $it", Modifier.fillMaxWidth(0.82f)) }
                            workout.nextDetail?.let { WatchCaption(it, Modifier.fillMaxWidth(0.80f)) }
                        }
                        Row(Modifier.fillMaxWidth(0.69f).padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (workout.restAddSeconds > 0) {
                                FaceButton("+${workout.restAddSeconds} s", ready, Modifier.weight(1f), primary = false) {
                                    fire(PanelControl(PanelAction.EXTEND_WATCH_REST, ""))
                                }
                            }
                            primary?.let { control ->
                                FaceButton(if (snapshot.alert) "OK" else "Saltar", ready && control.enabled, Modifier.weight(1f)) {
                                    fire(control)
                                }
                            }
                        }
                        WatchStatus(state)
                    }

                    else -> WorkoutFace(screenHeight) {
                        WatchHeading(snapshot.title, snapshot.subtitle.takeIf { it.isNotBlank() })
                        state.timerText?.let { WatchTimer(it, watchColors.primary) }
                        primary?.let { FaceButton(it.label, ready && it.enabled) { fire(it) } }
                        WatchStatus(state)
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth(0.77f).padding(top = 6.dp, bottom = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (reviewing) {
                        PanelButton("Volver", onClick = { reviewing = false })
                        if (!state.connected) PanelButton("Reconectar", onClick = onRefresh)
                    } else {
                        if (!state.connected) PanelButton("Reconectar", onClick = onRefresh)
                        if (last != null) PanelButton("Última serie", onClick = review)
                        if (active != null && !snapshot.paused && active.loadUnit != WatchLoadUnit.MACHINE_SETTING) {
                            PanelButton("Paso de carga", onClick = { field = EditorField.STEP })
                        }
                        if (snapshot.waitPending) SupportingText("Espera al terminar el descanso")
                        snapshot.notice?.takeIf { it.isNotBlank() }?.let { SupportingText(it) }
                        snapshot.controls.filter {
                            it != primary && it.action !in editorActions && (!rest || it.action != PanelAction.REST_ADD)
                        }.forEach { control ->
                            PanelButton(control.label, enabled = ready && control.enabled, onClick = { fire(control) })
                        }
                        monitorMessage?.let { SupportingText(it) }
                        PanelButton("Cerrar panel", onClick = onClose)
                    }
                }
            }
        }
    }
}

private val editorActions = setOf(
    PanelAction.WEIGHT_DOWN, PanelAction.WEIGHT_UP, PanelAction.REPS_DOWN, PanelAction.REPS_UP,
    PanelAction.SELECT_RIR, PanelAction.EDIT_LAST,
)

private fun Modifier.reviewGesture(review: () -> Unit) =
    semantics {
        customActions = listOf(CustomAccessibilityAction("Ver última serie") { review(); true })
    }.pointerInput(Unit) {
        var startInPanel = false
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { startInPanel = it.x > 20.dp.toPx(); distance = 0f },
            onDragEnd = { if (startInPanel && distance > 42.dp.toPx()) review() },
        ) { _, delta -> distance += delta }
    }

@Composable
private fun WorkoutFace(height: Dp, progress: Float? = null, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = height), contentAlignment = Alignment.Center) {
        if (progress != null) {
            Canvas(Modifier.matchParentSize()) {
                val diameter = size.minDimension - 10.dp.toPx()
                val origin = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                val ring = Size(diameter, diameter)
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                drawArc(Color(0xFF2F4235), 142f, 256f, false, origin, ring, style = stroke)
                if (progress > 0f) {
                    drawArc(watchColors.primary, 142f, 256f * progress.coerceAtMost(1f), false, origin, ring, style = stroke)
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 17.dp, bottom = 13.dp),
        ) { content() }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp)
            .width(16.dp).height(2.dp).background(watchColors.surfaceContainer, RoundedCornerShape(2.dp)))
    }
}

@Composable
internal fun WatchHeading(title: String, subtitle: String? = null) {
    Text(
        title,
        modifier = Modifier.fillMaxWidth(0.65f).semantics { heading() },
        style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp, lineHeight = 16.sp),
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = watchColors.onBackground,
    )
    subtitle?.let { WatchCaption(it, Modifier.fillMaxWidth(0.72f), maxLines = 2) }
}

@Composable
internal fun WatchCaption(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(text, modifier, color = watchColors.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
        textAlign = TextAlign.Center, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun SetValuesRow(set: WatchSet, values: WatchSetValues, enabled: Boolean, select: (EditorField) -> Unit) {
    Row(Modifier.fillMaxWidth(0.84f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ValueCard(tenthsLabel(values.weightTenths), set.loadCaption(values.weightTenths),
            "Carga ${set.loadLabel(values.weightTenths)}", enabled, Modifier.weight(1f)) { select(EditorField.WEIGHT) }
        ValueCard(values.reps.toString(), "reps", "${values.reps} repeticiones", enabled, Modifier.weight(1f)) {
            select(EditorField.REPS)
        }
    }
}

@Composable
private fun ValueCard(value: String, label: String, description: String, enabled: Boolean,
                      modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
            .background(watchColors.surfaceContainer).clickable(enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }.padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(value, color = watchColors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = if (value.length > 5) 19.sp else 28.sp, lineHeight = 28.sp,
                fontFeatureSettings = "tnum",
            ), fontWeight = FontWeight.Bold)
        WatchCaption(label)
    }
}

@Composable
private fun RirButton(rir: Int?, confirmed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = "RIR ${rirText(rir)}"
    Row(
        modifier = Modifier.heightIn(min = 34.dp).clip(RoundedCornerShape(20.dp))
            .clickable(enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (confirmed) label else "$label · confirmar" }
            .padding(horizontal = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, color = if (confirmed) watchColors.primary else watchColors.onBackground)
        if (!confirmed) WatchCaption("Confirmar")
    }
}

@Composable
internal fun FaceButton(text: String, enabled: Boolean, modifier: Modifier = Modifier.fillMaxWidth(0.62f),
                        primary: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val background = if (enabled && primary) watchColors.primary else watchColors.surfaceContainer
    val foreground = when {
        enabled && primary -> watchColors.onPrimary
        enabled -> watchColors.onBackground
        else -> watchColors.onSurfaceVariant.copy(alpha = 0.6f)
    }
    Box(
        modifier.heightIn(min = 44.dp).clip(shape).background(background)
            .clickable(enabled, role = Role.Button, onClick = onClick).padding(horizontal = 5.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, textAlign = TextAlign.Center, color = foreground,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
            fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WatchStatus(state: WearPanelState) {
    val message = when {
        !state.connected -> "Sin conexión"
        state.busy -> "Sincronizando…"
        else -> state.feedback
    }
    if (message != null) {
        WatchCaption(message, Modifier.fillMaxWidth(0.68f).semantics { liveRegion = LiveRegionMode.Polite }, maxLines = 2)
    }
}

@Composable
internal fun AmbientWorkout(state: WearPanelState, snapshot: PanelSnapshot) {
    val color = Color(0xFFC4C8C2)
    MaterialTheme(colorScheme = watchColors) {
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("workout_ambient"), contentAlignment = Alignment.Center) {
            TimeText()
            Column(Modifier.fillMaxWidth(0.70f), horizontalAlignment = Alignment.CenterHorizontally) {
                val set = snapshot.watch?.activeSet
                Text(snapshot.title, color = color, textAlign = TextAlign.Center, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                when {
                    !state.connected -> Text("Sin conexión", color = color, style = MaterialTheme.typography.labelSmall)
                    set != null && !snapshot.paused -> Text(set.progressLabel(), color = color,
                        style = MaterialTheme.typography.labelSmall)
                    snapshot.timer != null -> WatchTimer(state.timerText ?: "—", color)
                }
            }
        }
    }
}

@Composable
private fun WatchTimer(text: String, color: Color) {
    Text(text, color = color, fontWeight = FontWeight.Medium,
        style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum", fontSize = 34.sp, lineHeight = 39.sp))
}

private fun WatchSet.progressLabel(): String =
    if (requiresRir) "Serie $number de $total" else "$typeLabel · $number de $total"
