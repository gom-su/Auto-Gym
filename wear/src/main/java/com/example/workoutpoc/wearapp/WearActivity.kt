package com.example.workoutpoc.wearapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.example.workoutpoc.wearprotocol.PanelAction
import com.example.workoutpoc.wearprotocol.PanelControl
import com.example.workoutpoc.wearprotocol.PanelMode
import com.example.workoutpoc.wearprotocol.PanelSnapshot
import kotlinx.coroutines.launch

class WearActivity : ComponentActivity() {
    private val connection get() = (application as WearApplication).connection
    private var monitorMessage by mutableStateOf<String?>(null)
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) monitorMessage = "Las notificaciones del reloj están desactivadas."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var startedSession: String? = null
                connection.state.collect { state ->
                    val snapshot = state.snapshot ?: return@collect
                    if (!state.connected || snapshot.mode == PanelMode.IDLE || snapshot.mode == PanelMode.COMPLETED) {
                        startedSession = null
                        return@collect
                    }
                    if (snapshot.sessionId == startedSession) return@collect
                    startedSession = snapshot.sessionId
                    try {
                        WearSessionService.start(this@WearActivity, snapshot.sessionId)
                        requestPermissionOnce()
                    } catch (failure: RuntimeException) {
                        Log.w("AutoGymWatch", "Could not start the session monitor", failure)
                        monitorMessage = "El reloj no pudo mantener el aviso en segundo plano."
                    }
                }
            }
        }
        setContent {
            val state by connection.state.collectAsStateWithLifecycle()
            AutoGymWatchPanel(
                state = state,
                monitorMessage = monitorMessage,
                onAction = connection::perform,
                onRefresh = connection::refresh,
                onLoadSummary = connection::loadSummary,
                onClose = {
                    WearSessionService.close(this)
                    finish()
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        connection.open()
    }

    override fun onStop() {
        connection.hide()
        super.onStop()
    }

    private fun requestPermissionOnce() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            monitorMessage = null
            return
        }
        val preferences = getPreferences(MODE_PRIVATE)
        if (!preferences.getBoolean("requested_notifications", false)) {
            preferences.edit().putBoolean("requested_notifications", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            monitorMessage = "Las notificaciones del reloj están desactivadas."
        }
    }
}

private val watchColors = ColorScheme(
    primary = Color(0xFFB4EE80),
    onPrimary = Color(0xFF1A3700),
    background = Color(0xFF101612),
    onBackground = Color(0xFFF0F4EA),
    surfaceContainer = Color(0xFF273129),
    onSurface = Color(0xFFF0F4EA),
    onSurfaceVariant = Color(0xFFC8D4C5)
)

@Composable
internal fun AutoGymWatchPanel(
    state: WearPanelState,
    monitorMessage: String? = null,
    onAction: (PanelControl, PanelSnapshot) -> Unit,
    onRefresh: () -> Unit,
    onLoadSummary: (Int) -> Unit,
    onClose: () -> Unit
) {
    val snapshot = state.snapshot
    val primary = snapshot?.primaryAction()
    val available = state.connected && !state.busy
    val fire: (PanelControl) -> Unit = { control ->
        if (snapshot != null) onAction(control, snapshot)
    }
    MaterialTheme(colorScheme = watchColors) {
        AppScaffold(modifier = Modifier.background(watchColors.background)) {
            key(snapshot?.sessionId, snapshot?.mode, snapshot?.index, snapshot?.paused, snapshot?.alert) {
                val listState = rememberTransformingLazyColumnState()
                val spec = rememberTransformationSpec()
                ScreenScaffold(
                    scrollState = listState,
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 34.dp, bottom = 28.dp)
                ) { padding ->
                    TransformingLazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("watch_panel"),
                        contentPadding = padding,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item("header") { PanelHeading(state) }
                        if (primary != null) {
                            item("primary") {
                                PanelButton(
                                    text = primary.label,
                                    enabled = available && primary.enabled,
                                    primary = true,
                                    modifier = Modifier.transformedHeight(this, spec),
                                    transformation = SurfaceTransformation(spec),
                                    onClick = { fire(primary) }
                                )
                            }
                        }
                        val message = state.feedback ?: when {
                            !state.connected && snapshot != null -> "Sin conexión con el teléfono"
                            state.busy -> "Confirmando…"
                            else -> snapshot?.completionHint
                        }
                        if (message != null) {
                            item("feedback") {
                                SupportingText(
                                    message,
                                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                                )
                            }
                        }
                        if (!state.connected && !state.busy && (snapshot != null || state.feedback != null)) {
                            item("refresh") {
                                PanelButton("Reconectar", onClick = onRefresh, transformation = SurfaceTransformation(spec))
                            }
                        }
                        snapshot?.notice?.takeIf { it.isNotBlank() }?.let { notice ->
                            item("notice") { SupportingText(notice) }
                        }
                        if (snapshot?.waitPending == true) {
                            item("pending-wait") { SupportingText("Espera al terminar el descanso") }
                        }
                        val controls = snapshot?.controls.orEmpty()
                        val weightActions = controls.filter { it.action in weightControls }
                        val repActions = controls.filter { it.action in repControls }
                        val rirActions = controls.filter { it.action == PanelAction.SELECT_RIR }
                        if (weightActions.isNotEmpty()) {
                            item("weight") { Stepper(snapshot?.weight.orEmpty(), weightActions, available, fire) }
                        }
                        if (repActions.isNotEmpty()) {
                            item("reps") { Stepper("${snapshot?.reps ?: 0} reps", repActions, available, fire) }
                        }
                        if (rirActions.isNotEmpty()) {
                            item("rir-label") { SupportingText("RIR · repeticiones en reserva") }
                            items(rirActions.chunked(3)) { options ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                                ) {
                                    options.forEach { option ->
                                        val label = option.label
                                        val selectedRir = option.argument == snapshot?.rir
                                        PanelButton(
                                            text = label,
                                            enabled = available && option.enabled,
                                            primary = selectedRir,
                                            compact = true,
                                            modifier = Modifier.weight(1f).semantics {
                                                selected = selectedRir
                                                contentDescription = "RIR $label"
                                            },
                                            onClick = { fire(option) }
                                        )
                                    }
                                }
                            }
                        }
                        val remaining = controls.filterNot {
                            it == primary || it.action in weightControls || it.action in repControls ||
                                it.action == PanelAction.SELECT_RIR
                        }
                        items(remaining, key = { it.action.name }) { control ->
                            PanelButton(
                                text = control.label,
                                enabled = available && control.enabled,
                                modifier = Modifier.transformedHeight(this, spec),
                                transformation = SurfaceTransformation(spec),
                                onClick = { fire(control) }
                            )
                        }
                        if (snapshot != null && snapshot.summaryPages > 0) {
                            val lines = state.summary.toSortedMap().values.joinToString("").lines()
                                .filter { it.isNotBlank() }
                            items(lines) { line -> SupportingText(line) }
                            val nextPage = state.summary.size
                            if (nextPage < snapshot.summaryPages) {
                                item("summary-loading") {
                                    SummaryContinuation(state, nextPage, onLoadSummary)
                                }
                            }
                        }
                        if (monitorMessage != null) {
                            item("monitor-message") { SupportingText(monitorMessage) }
                        }
                        item("close") {
                            PanelButton("Cerrar panel", onClick = onClose, transformation = SurfaceTransformation(spec))
                        }
                    }
                }
            }
        }
    }
}

private val weightControls = setOf(PanelAction.WEIGHT_DOWN, PanelAction.WEIGHT_UP)
private val repControls = setOf(PanelAction.REPS_DOWN, PanelAction.REPS_UP)

private fun PanelSnapshot.primaryAction(): PanelControl? {
    val preferred = if (paused) PanelAction.RESUME else when (mode) {
        PanelMode.SET -> PanelAction.COMPLETE_SET
        PanelMode.REST -> if (alert) PanelAction.REST_OK else PanelAction.REST_SKIP
        PanelMode.WAIT -> PanelAction.WAIT_END
        PanelMode.CARDIO -> PanelAction.CARDIO_FINISH
        else -> return null
    }
    return controls.firstOrNull { it.action == preferred }
}

@Composable
private fun PanelHeading(state: WearPanelState) {
    val snapshot = state.snapshot
    val title = snapshot?.title?.takeIf { snapshot.mode != PanelMode.IDLE && it.isNotBlank() } ?: "Auto-Gym"
    val subtitle = snapshot?.subtitle?.takeIf { it.isNotBlank() } ?: when {
        snapshot == null && state.feedback == null -> "Conectando con el teléfono…"
        snapshot?.mode == PanelMode.IDLE -> "Inicia una sesión en el teléfono"
        else -> null
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            textAlign = TextAlign.Center,
            color = watchColors.onBackground,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        if (subtitle != null) SupportingText(subtitle)
        state.timerText?.let { timer ->
            Text(
                text = timer,
                color = if (state.connected) watchColors.primary else watchColors.onSurfaceVariant,
                style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun Stepper(
    value: String,
    buttons: List<PanelControl>,
    enabled: Boolean,
    onAction: (PanelControl) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        buttons.firstOrNull { it.action == PanelAction.WEIGHT_DOWN || it.action == PanelAction.REPS_DOWN }
            ?.let { StepperButton(it, enabled, onAction) }
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            color = watchColors.onBackground,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Bold
        )
        buttons.firstOrNull { it.action == PanelAction.WEIGHT_UP || it.action == PanelAction.REPS_UP }
            ?.let { StepperButton(it, enabled, onAction) }
    }
}

@Composable
private fun StepperButton(control: PanelControl, enabled: Boolean, onAction: (PanelControl) -> Unit) {
    val (symbol, description) = when (control.action) {
        PanelAction.WEIGHT_DOWN -> "−" to "Reducir peso"
        PanelAction.WEIGHT_UP -> "+" to "Aumentar peso"
        PanelAction.REPS_DOWN -> "−" to "Reducir repeticiones"
        else -> "+" to "Aumentar repeticiones"
    }
    Button(
        onClick = { onAction(control) },
        modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
            .semantics { contentDescription = description },
        enabled = enabled && control.enabled,
        colors = ButtonDefaults.filledTonalButtonColors(),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        Text(symbol, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SummaryContinuation(state: WearPanelState, page: Int, onLoad: (Int) -> Unit) {
    LaunchedEffect(page, state.connected, state.summaryFailed) {
        if (state.connected && !state.summaryFailed) onLoad(page)
    }
    if (state.summaryFailed) {
        PanelButton("Reintentar resumen", enabled = state.connected, onClick = { onLoad(page) })
    } else if (state.connected) {
        SupportingText("Cargando resumen…")
    }
}

@Composable
private fun PanelButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    compact: Boolean = false,
    transformation: SurfaceTransformation? = null,
    onClick: () -> Unit
) {
    Button(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        enabled = enabled,
        onClick = onClick,
        contentPadding = if (compact) PaddingValues(horizontal = 4.dp) else ButtonDefaults.ContentPadding,
        colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        transformation = transformation,
        label = {
            Text(
                text = text,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium
            )
        }
    )
}

@Composable
private fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 5.dp),
        style = MaterialTheme.typography.labelMedium,
        color = watchColors.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}
