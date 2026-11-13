package com.example.workoutpoc.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.domain.PastSetEdit
import com.example.workoutpoc.domain.SetStatus
import com.example.workoutpoc.domain.SetValues
import com.example.workoutpoc.domain.WeightUnit
import com.example.workoutpoc.domain.rirLabel
import com.example.workoutpoc.domain.weightLabel
import com.example.workoutpoc.ui.Banner
import com.example.workoutpoc.ui.SupportingText
import com.example.workoutpoc.ui.Tone
import com.example.workoutpoc.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** One editable set row. Weight is typed in the set's unit, as shown; RIR is null when the user has not chosen one. */
private data class SetDraft(
    val completed: Boolean, val weight: String, val reps: String, val rir: Int?, val notes: String,
    val work: Boolean, val unit: WeightUnit, val recordedUnit: WeightUnit?, val original: HistorySet
) {
    /** kg and lb can be corrected; machine positions and bodyweight keep their own representation. */
    val switchable: Boolean get() = original.prescribedUnit == WeightUnit.KG || original.prescribedUnit == WeightUnit.LB
}

/**
 * Edits an archived planned session (requirement 9): its date and the recorded values of each set. Times and the
 * prescription stay as they are; the domain validates the result and keeps the replaced versions.
 */
@Composable
fun PastSessionEditor(session: SessionSummary, onDismiss: () -> Unit, onSave: (Long?, List<PastSetEdit>) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val start = session.startedAtMillis
    val originalDate = remember(start) { start?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } }
    val originalTime = remember(start) { start?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(12, 0) }
    var dateText by remember(session.sessionId) { mutableStateOf(originalDate?.toString().orEmpty()) }
    var error by remember(session.sessionId) { mutableStateOf<String?>(null) }
    val drafts = remember(session.sessionId) {
        mutableStateListOf(*session.sets.map { h ->
            SetDraft(completed = h.status == SetStatus.COMPLETED, weight = weightLabel(h.values.weightTenths),
                reps = h.values.reps.toString(), rir = h.values.rir, notes = h.notes.orEmpty(), work = !h.warmup,
                unit = h.unit, recordedUnit = if (h.unit != h.prescribedUnit) h.unit else null, original = h)
        }.toTypedArray())
    }

    fun save() {
        error = null
        val date = try { LocalDate.parse(dateText.trim()) } catch (_: Exception) { error = "Indica la fecha como AAAA-MM-DD."; return }
        // Without a recorded start the date is set at 12:00 local time; otherwise the time of day is kept.
        val newStart = if (date == originalDate) null
            else date.atTime(originalTime).atZone(zone).toInstant().toEpochMilli()
        val edits = mutableListOf<PastSetEdit>()
        drafts.forEachIndexed { index, d ->
            if (!d.completed) {
                edits += PastSetEdit(index, SetStatus.SKIPPED, d.original.values, d.notes.trim().ifEmpty { null }, d.recordedUnit)
                return@forEachIndexed
            }
            val weight = d.weight.trim().replace(',', '.').toDoubleOrNull()
            val reps = d.reps.trim().toIntOrNull()
            // NaN and infinities parse as doubles; reject them before the tenths conversion.
            if (weight == null || !weight.isFinite() || weight < 0 || weight > 1_000_000) { error = "Revisa la carga de ${d.original.exercise} serie ${d.original.number}."; return }
            if (reps == null || reps < 1) { error = "Una serie completada necesita al menos 1 repetición (${d.original.exercise} serie ${d.original.number})."; return }
            if (d.work && d.rir == null) { error = "Selecciona el RIR (${d.original.exercise} serie ${d.original.number})."; return }
            edits += PastSetEdit(index, SetStatus.COMPLETED,
                SetValues((weight * 10).roundToInt(), reps, if (d.work) d.rir else null),
                d.notes.trim().ifEmpty { null }, d.recordedUnit)
        }
        onSave(newStart, edits)
    }

    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Editar sesión pasada") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(dateText, { dateText = it }, label = { Text("Fecha (AAAA-MM-DD)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (session.sets.isEmpty()) SupportingText("Sin series registradas.")
                drafts.forEachIndexed { index, d ->
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text("${d.original.exercise} · ${if (d.original.warmup) "aproximación" else if (d.original.extra) "serie extra" else "serie"} ${d.original.number}",
                            style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(!d.completed, { drafts[index] = d.copy(completed = !it) })
                            Spacer(Modifier.width(Spacing.xs))
                            Text("Serie omitida")
                        }
                        if (d.completed) {
                            if (d.switchable) Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                listOf(WeightUnit.KG, WeightUnit.LB).forEach { u ->
                                    FilterChip(d.unit == u, {
                                        drafts[index] = d.copy(unit = u, recordedUnit = if (u != d.original.prescribedUnit) u else null)
                                    }, label = { Text(unitLabel(u)) })
                                }
                            }
                            OutlinedTextField(d.weight, { drafts[index] = d.copy(weight = it) },
                                label = { Text("Carga (${unitLabel(d.unit)})") }, singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(d.reps, { drafts[index] = d.copy(reps = it.filter(Char::isDigit)) },
                                label = { Text("Repeticiones") }, singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth())
                            if (d.work) {
                                SupportingText("RIR")
                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    (0..4).forEach { value ->
                                        FilterChip(d.rir == value, { drafts[index] = d.copy(rir = value) },
                                            label = { Text(rirLabel(value)) })
                                    }
                                }
                            }
                        }
                        OutlinedTextField(d.notes, { drafts[index] = d.copy(notes = it.take(1000)) },
                            label = { Text("Notas") }, modifier = Modifier.fillMaxWidth())
                    }
                }
                error?.let { Banner(it, tone = Tone.Error) }
            }
        },
        confirmButton = { Button(onClick = { save() }) { Text("Guardar cambios") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

private fun unitLabel(unit: WeightUnit): String = when (unit) {
    WeightUnit.KG -> "kg"
    WeightUnit.LB -> "lb"
    WeightUnit.MACHINE_SETTING -> "posición"
    WeightUnit.BODYWEIGHT -> "kg extra"
}
