package com.example.workoutpoc.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.data.activePlan
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.ui.AppIcons
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular

@Composable
fun WorkoutControls(state: WorkoutState, dispatch: (WorkoutCommand) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        ValueStepper("Peso", loadLabel(state.values.weightTenths, state.activePlan()[state.index].unit),
            "Reducir peso", "Aumentar peso",
            { dispatch(state.command(WorkoutEvent.DecrementWeight)) },
            { dispatch(state.command(WorkoutEvent.IncrementWeight)) })
        ValueStepper("Repeticiones", "${state.values.reps}", "Reducir repeticiones", "Aumentar repeticiones",
            { dispatch(state.command(WorkoutEvent.DecrementReps)) },
            { dispatch(state.command(WorkoutEvent.IncrementReps)) })
        Text("RIR · repeticiones en reserva", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // A single-choice segmented control: equal widths, one tap, current value always visible.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            (0..4).forEach { rir ->
                SegmentedButton(selected = state.values.rir == rir,
                    onClick = { dispatch(state.command(WorkoutEvent.SelectRir, rir)) },
                    shape = SegmentedButtonDefaults.itemShape(rir, 5), modifier = Modifier.heightIn(min = 48.dp),
                    icon = {}) { Text(if (rir == 4) "4+" else "$rir", style = MaterialTheme.typography.titleMedium.tabular()) }
            }
        }
        Button(onClick = { dispatch(state.command(WorkoutEvent.CompleteSet)) },
            enabled = state.values.reps > 0, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            if (state.values.reps > 0) { Icon(AppIcons.Check, contentDescription = null); Spacer(Modifier.width(Spacing.sm)) }
            Text(if (state.values.reps > 0) "Completar serie" else "Registra al menos 1 repetición", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Large −/value/+ control. The value is a polite live region, so each change is announced. */
@Composable
private fun ValueStepper(label: String, value: String, minusLabel: String, plusLabel: String, minus: () -> Unit, plus: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = minus, modifier = Modifier.size(56.dp)) { Icon(AppIcons.Remove, contentDescription = minusLabel) }
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.headlineMedium.tabular())
            }
            FilledTonalIconButton(onClick = plus, modifier = Modifier.size(56.dp)) { Icon(AppIcons.Add, contentDescription = plusLabel) }
        }
    }
}
