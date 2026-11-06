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

/** A recorded-set edit, bound to the snapshot it was opened from so a stale save cannot overwrite newer data. */
data class RecordEdit(val sessionId: String, val revision: Long, val index: Int,
                      val status: SetStatus, val values: SetValues, val notes: String?)

@Composable
fun RecordEditorDialog(state: WorkoutState, index: Int, onDismiss: () -> Unit, onSave: (RecordEdit) -> Unit) {
    val record = state.records.getOrNull(index) ?: return
    val planned = state.plan[index]
    var status by remember(record) { mutableStateOf(record.status) }
    var weight by remember(record) { mutableIntStateOf(record.values.weightTenths) }
    var reps by remember(record) { mutableIntStateOf(record.values.reps) }
    var rir by remember(record) { mutableStateOf(record.values.rir) }
    var notes by remember(record) { mutableStateOf(record.notes.orEmpty()) }
    val valid = (status == SetStatus.SKIPPED || reps > 0) && notes.length <= MAX_NOTE_LENGTH
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Editar ${planned.exercise} · serie ${planned.number}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Prescrito: ${if (planned.extra) "serie añadida, sin prescripción" else prescriptionLabel(planned)}",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(status == SetStatus.SKIPPED, { status = if (it) SetStatus.SKIPPED else SetStatus.COMPLETED })
                    Text("Serie omitida")
                }
                if (status == SetStatus.COMPLETED) {
                    Stepper("Carga", loadLabel(weight, planned.unit),
                        { weight = (weight - planned.weightStepTenths).coerceAtLeast(0) },
                        { weight = (weight + planned.weightStepTenths).coerceAtMost(planned.maxWeightTenths) })
                    Stepper("Repeticiones", "$reps", { reps = (reps - 1).coerceAtLeast(0) }, { reps = (reps + 1).coerceAtMost(planned.maxReps) })
                    Text("RIR", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(rir == null, { rir = null }, { Text("—") })
                        (0..4).forEach { value -> FilterChip(rir == value, { rir = value }, { Text(rirLabel(value)) }) }
                    }
                }
                OutlinedTextField(notes, { if (it.length <= MAX_NOTE_LENGTH) notes = it }, label = { Text("Notas") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
                if (status == SetStatus.COMPLETED && reps == 0) Text("Una serie completada necesita al menos 1 repetición.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                onSave(RecordEdit(state.sessionId, state.revision, index, status,
                    if (status == SetStatus.COMPLETED) SetValues(weight, reps, rir) else record.values, notes))
            }) { Text("Guardar cambios") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun Stepper(label: String, value: String, minus: () -> Unit, plus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = minus, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text("−") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium); Text(value, style = MaterialTheme.typography.titleMedium)
        }
        FilledTonalButton(onClick = plus, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
    }
}

/** Deviation actions for the current position of a planned session. */
@Composable
fun DeviationActions(state: WorkoutState, dispatch: (WorkoutCommand) -> Unit) {
    if (state.source == null) return
    val plan = state.plan
    val last = state.records.lastOrNull()?.let { plan[it.index].exercise } ?: plan[state.index].exercise
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.phase == Phase.ActiveSet) {
            if (plan[state.index].extra) OutlinedButton(onClick = { dispatch(state.command(WorkoutEvent.RemoveExtraSet)) },
                modifier = Modifier.fillMaxWidth()) { Text("Quitar serie extra") }
            else OutlinedButton(onClick = { dispatch(state.command(WorkoutEvent.SkipSet)) }, modifier = Modifier.fillMaxWidth()) { Text("Omitir serie") }
        }
        if (state.phase != Phase.Idle && plan.size < MAX_PLAN_SIZE)
            OutlinedButton(onClick = { dispatch(state.command(WorkoutEvent.AddSet)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Añadir serie extra de $last")
            }
    }
}
