package com.example.workoutpoc.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.domain.*

@Composable
fun WorkoutControls(state: WorkoutState, dispatch: (WorkoutCommand) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ValueStepper("Peso", "${weightLabel(state.values.weightTenths)} kg",
            { dispatch(state.command(WorkoutEvent.DecrementWeight)) },
            { dispatch(state.command(WorkoutEvent.IncrementWeight)) })
        ValueStepper("Repeticiones", "${state.values.reps}",
            { dispatch(state.command(WorkoutEvent.DecrementReps)) },
            { dispatch(state.command(WorkoutEvent.IncrementReps)) })
        Text("RIR · repeticiones en reserva", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (0..4).forEach { rir ->
                FilterChip(selected = state.values.rir == rir,
                    onClick = { dispatch(state.command(WorkoutEvent.SelectRir, rir)) },
                    label = { Text(if (rir == 4) "4+" else "$rir") },
                    modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Button(onClick = { dispatch(state.command(WorkoutEvent.CompleteSet)) },
            enabled = state.values.reps > 0, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (state.values.reps > 0) "Completar serie" else "Registra al menos 1 repetición")
        }
    }
}

@Composable
private fun ValueStepper(label: String, value: String, minus: () -> Unit, plus: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = minus, modifier = Modifier.size(56.dp), contentPadding = PaddingValues(0.dp)) { Text("−") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(value, style = MaterialTheme.typography.headlineSmall)
            }
            FilledTonalButton(onClick = plus, modifier = Modifier.size(56.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
        }
    }
}
