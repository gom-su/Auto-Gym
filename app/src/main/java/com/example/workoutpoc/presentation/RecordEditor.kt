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
import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.*

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
        icon = { Icon(AppIcons.Edit, contentDescription = null) },
        title = { Text("Editar ${planned.exercise} · serie ${planned.number}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SupportingText("Prescrito: ${if (planned.extra) "serie añadida, sin prescripción" else prescriptionLabel(planned)}", small = true)
                // The whole row toggles, so the label is part of the touch target and is read with the checkbox state.
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(status == SetStatus.SKIPPED, role = Role.Checkbox) {
                    status = if (it) SetStatus.SKIPPED else SetStatus.COMPLETED }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(status == SetStatus.SKIPPED, null)
                    Spacer(Modifier.width(Spacing.sm)); Text("Serie omitida")
                }
                if (status == SetStatus.COMPLETED) {
                    Stepper("Carga", loadLabel(weight, planned.unit), "Reducir carga", "Aumentar carga",
                        { weight = (weight - planned.weightStepTenths).coerceAtLeast(0) },
                        { weight = (weight + planned.weightStepTenths).coerceAtMost(planned.maxWeightTenths) })
                    Stepper("Repeticiones", "$reps", "Reducir repeticiones", "Aumentar repeticiones", { reps = (reps - 1).coerceAtLeast(0) }, { reps = (reps + 1).coerceAtMost(planned.maxReps) })
                    Text("RIR", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(rir == null, { rir = null }, SegmentedButtonDefaults.itemShape(0, 6), icon = {},
                            modifier = Modifier.semantics { contentDescription = "RIR sin indicar" }) { Text("—") }
                        (0..4).forEach { value -> SegmentedButton(rir == value, { rir = value }, SegmentedButtonDefaults.itemShape(value + 1, 6), icon = {}) {
                            Text(rirLabel(value), style = MaterialTheme.typography.labelLarge.tabular()) } }
                    }
                }
                OutlinedTextField(notes, { if (it.length <= MAX_NOTE_LENGTH) notes = it }, label = { Text("Notas") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
                if (status == SetStatus.COMPLETED && reps == 0) Banner("Una serie completada necesita al menos 1 repetición.", tone = Tone.Error)
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
private fun Stepper(label: String, value: String, minusLabel: String, plusLabel: String, minus: () -> Unit, plus: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.fillMaxWidth().padding(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = minus, modifier = Modifier.size(48.dp)) { Icon(AppIcons.Remove, contentDescription = minusLabel) }
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleLarge.tabular())
            }
            FilledTonalIconButton(onClick = plus, modifier = Modifier.size(48.dp)) { Icon(AppIcons.Add, contentDescription = plusLabel) }
        }
    }
}

/** Deviation actions for the current position of a planned session. */
@Composable
fun DeviationActions(state: WorkoutState, dispatch: (WorkoutCommand) -> Unit) {
    if (state.source == null) return
    val plan = state.plan
    val last = state.records.lastOrNull()?.let { plan[it.index].exercise } ?: plan[state.index].exercise
    val removeExtra = state.phase == Phase.ActiveSet && plan[state.index].extra
    val skip = state.phase == Phase.ActiveSet && !plan[state.index].extra
    val next = state.records.size
    val warmup = (state.phase == Phase.ActiveSet || state.phase == Phase.Resting) && next < plan.size && plan.size < MAX_PLAN_SIZE
    val extra = state.phase != Phase.Idle && plan.size < MAX_PLAN_SIZE
    if (!removeExtra && !skip && !warmup && !extra) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("Cambios sobre el plan", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (removeExtra) SecondaryAction("Quitar serie extra", { dispatch(state.command(WorkoutEvent.RemoveExtraSet)) }, icon = AppIcons.Remove)
        if (skip) SecondaryAction("Omitir serie", { dispatch(state.command(WorkoutEvent.SkipSet)) }, icon = AppIcons.ChevronRight)
        if (warmup) SecondaryAction("Añadir aproximación de ${plan[next].exercise}", { dispatch(state.command(WorkoutEvent.AddWarmupSet)) }, icon = AppIcons.Add)
        if (extra) SecondaryAction("Añadir serie extra de $last", { dispatch(state.command(WorkoutEvent.AddSet)) }, icon = AppIcons.Add)
    }
}
