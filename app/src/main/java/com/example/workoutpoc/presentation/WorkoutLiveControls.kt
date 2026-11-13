package com.example.workoutpoc.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular
import kotlinx.coroutines.delay

@Composable
internal fun liveSurfaceTimer(state: WorkoutState, now: () -> ClockSnapshot): SurfaceTimer? {
    var timer by remember(state) { mutableStateOf(state.surfaceTimer(now())) }
    LaunchedEffect(state) {
        while (true) { timer = state.surfaceTimer(now()); delay(250) }
    }
    return timer
}

@Composable
internal fun WaitActions(state: WorkoutState, timer: SurfaceTimer?, dispatch: (WorkoutCommand) -> Unit) {
    if (state.source == null || !state.phase.inProgress || state.paused != null) return
    val event = when {
        timer?.waiting == true -> WorkoutEvent.EndMachineWait
        state.openMachineWait != null -> WorkoutEvent.CancelPendingWait
        else -> WorkoutEvent.StartMachineWait
    }
    if (state.openMachineWait?.pending != null && timer?.waiting != true)
        Text("Espera al terminar el descanso", style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = { dispatch(state.command(event)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(when (event) {
            WorkoutEvent.EndMachineWait -> "Terminar espera"
            WorkoutEvent.CancelPendingWait -> "Cancelar espera pendiente"
            else -> "Iniciar espera"
        })
    }
}

@Composable
internal fun ExerciseWaitControls(state: WorkoutState, now: () -> ClockSnapshot, dispatch: (WorkoutCommand) -> Unit) {
    val timer = liveSurfaceTimer(state, now)
    if (timer?.waiting == true) {
        Text(timer.label, style = MaterialTheme.typography.titleMedium)
        Text(timer.text, style = MaterialTheme.typography.headlineLarge.tabular())
    }
    WaitActions(state, timer, dispatch)
}

@Composable
internal fun CardioSurface(state: WorkoutState, now: () -> ClockSnapshot, dispatch: (WorkoutCommand) -> Unit) {
    val timer = liveSurfaceTimer(state, now) ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(state.currentCardio?.modality.orEmpty(), style = MaterialTheme.typography.titleLarge)
        Text(timer.label + if (state.paused != null) " · en pausa" else "", style = MaterialTheme.typography.titleMedium)
        Text(timer.text, style = MaterialTheme.typography.displayLarge.tabular())
        WaitActions(state, timer, dispatch)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(onClick = { dispatch(state.command(if (state.paused == null) WorkoutEvent.PauseSession else WorkoutEvent.ResumeSession)) }) {
                Text(if (state.paused == null) "Pausar" else "Reanudar")
            }
            Button(onClick = { dispatch(state.command(WorkoutEvent.FinishCardio)) }, enabled = !timer.waiting) { Text("Terminar cardio") }
        }
    }
}

@Composable
internal fun SeriesNoticeCard(notice: SeriesNotice?) {
    if (notice == null) return
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(notice.heading, style = MaterialTheme.typography.titleMedium)
            Text(notice.detail, style = MaterialTheme.typography.bodyLarge)
            notice.changes.forEach { Text(it, style = MaterialTheme.typography.titleSmall) }
        }
    }
}
