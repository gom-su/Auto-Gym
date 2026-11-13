package com.example.workoutpoc.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.domain.MAX_NOTE_LENGTH
import com.example.workoutpoc.domain.PastSetEdit
import com.example.workoutpoc.domain.SetStatus
import com.example.workoutpoc.domain.SetValues
import com.example.workoutpoc.domain.WeightUnit
import com.example.workoutpoc.domain.loadLabel
import com.example.workoutpoc.domain.rirLabel
import com.example.workoutpoc.domain.weightLabel
import com.example.workoutpoc.ui.AppIcons
import com.example.workoutpoc.ui.AppScreen
import com.example.workoutpoc.ui.ContentCard
import com.example.workoutpoc.ui.EmptyState
import com.example.workoutpoc.ui.IconBadge
import com.example.workoutpoc.ui.MaxContentWidth
import com.example.workoutpoc.ui.QuietAction
import com.example.workoutpoc.ui.SectionHeader
import com.example.workoutpoc.ui.SegmentedChoice
import com.example.workoutpoc.ui.StatusPill
import com.example.workoutpoc.ui.SupportingText
import com.example.workoutpoc.ui.TonalAction
import com.example.workoutpoc.ui.Tone
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The typed values of one set. Weight is in the set's unit, as shown; RIR is null when none is chosen. */
internal data class SetDraft(val completed: Boolean, val weight: String, val reps: String, val rir: Int?,
                             val notes: String, val unit: WeightUnit)

/** What is wrong with a draft, per field, so each message sits next to the field it is about. */
internal data class SetErrors(val weight: String? = null, val reps: String? = null, val rir: String? = null) {
    val any: Boolean get() = weight != null || reps != null || rir != null
    val first: String? get() = weight ?: reps ?: rir
}

private fun draftOf(h: HistorySet) = SetDraft(h.status == SetStatus.COMPLETED, weightLabel(h.values.weightTenths),
    h.values.reps.toString(), h.values.rir, h.notes.orEmpty(), h.unit)

/** kg and lb can be corrected; machine positions and bodyweight keep their own representation. */
private val HistorySet.switchable: Boolean get() = prescribedUnit == WeightUnit.KG || prescribedUnit == WeightUnit.LB

private fun HistorySet.kindLabel(): String =
    "${if (warmup) "Aproximación" else if (extra) "Serie extra" else "Serie"} $number"

/** Tenths of the typed load, or null when it is not a number. NaN and infinities parse as doubles, so they are rejected. */
private fun tenthsOf(text: String): Int? {
    val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return if (value.isFinite() && value >= 0 && value <= 1_000_000) (value * 10).roundToInt() else null
}

/**
 * The corrections being made to an archived session: its date and one draft per recorded set. Kept apart from the
 * layout so the list, the pinned actions and the leave confirmation read the same state.
 */
@Stable
internal class PastSessionEditorState(val session: SessionSummary, private val zone: ZoneId,
                                      date: LocalDate?, drafts: List<SetDraft>?, expanded: Int?, showErrors: Boolean) {
    private val start = session.startedAtMillis
    val originalDate: LocalDate? = start?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    /** Without a recorded start the date is set at 12:00 local time; otherwise the time of day is kept. */
    val timeOfDay: LocalTime = start?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(12, 0)
    val initial: List<SetDraft> = session.sets.map(::draftOf)
    val drafts = mutableStateListOf<SetDraft>().apply { addAll(drafts?.takeIf { it.size == initial.size } ?: initial) }
    var date by mutableStateOf(date)
    /** The one set whose fields are open; the others show their values in one line. */
    var expanded by mutableStateOf(expanded?.takeIf { it in initial.indices })
    /** Set by the first attempt to save: from then on every problem is shown, not only those of fields already typed in. */
    var showErrors by mutableStateOf(showErrors)

    /** Consecutive sets of one exercise, as indexes into [drafts]: one card each. */
    val groups: List<List<Int>> = buildList<MutableList<Int>> {
        session.sets.forEachIndexed { i, h ->
            if (isNotEmpty() && session.sets[last().last()].exercise == h.exercise) last().add(i) else add(mutableListOf(i))
        }
    }

    fun changed(index: Int): Boolean = drafts[index] != initial[index]
    val changedSets: Int get() = drafts.indices.count(::changed)
    val dateChanged: Boolean get() = date != originalDate
    val dirty: Boolean get() = dateChanged || changedSets > 0

    fun errors(index: Int): SetErrors {
        val d = drafts[index]
        val h = session.sets[index]
        if (!d.completed) return SetErrors()
        val tenths = tenthsOf(d.weight)
        val reps = d.reps.trim().toIntOrNull()
        return SetErrors(
            weight = when {
                tenths == null -> "Indica la carga."
                tenths > h.maxWeightTenths -> "El máximo es ${weightLabel(h.maxWeightTenths)}."
                else -> null
            },
            reps = when {
                reps == null || reps < 1 -> "Al menos 1 repetición."
                reps > h.maxReps -> "El máximo es ${h.maxReps}."
                else -> null
            },
            rir = if (!h.warmup && d.rir == null) "Selecciona el RIR." else null)
    }

    val firstInvalid: Int? get() = drafts.indices.firstOrNull { errors(it).any }

    fun stepWeight(index: Int, direction: Int) {
        val h = session.sets[index]
        val next = ((tenthsOf(drafts[index].weight) ?: 0) + direction * h.weightStepTenths).coerceIn(0, h.maxWeightTenths)
        drafts[index] = drafts[index].copy(weight = weightLabel(next))
    }

    fun stepReps(index: Int, direction: Int) {
        val next = ((drafts[index].reps.trim().toIntOrNull() ?: 0) + direction).coerceIn(0, session.sets[index].maxReps)
        drafts[index] = drafts[index].copy(reps = next.toString())
    }

    /** The new start (null when the date is unchanged) and every set as it now stands. Call only when nothing is invalid. */
    fun result(): Pair<Long?, List<PastSetEdit>> {
        val newStart = date?.takeIf { it != originalDate }?.atTime(timeOfDay)?.atZone(zone)?.toInstant()?.toEpochMilli()
        val edits = drafts.mapIndexed { index, d ->
            val h = session.sets[index]
            val notes = d.notes.trim().ifEmpty { null }
            val recordedUnit = if (d.unit != h.prescribedUnit) d.unit else null
            if (!d.completed) PastSetEdit(index, SetStatus.SKIPPED, h.values, notes, recordedUnit)
            else PastSetEdit(index, SetStatus.COMPLETED,
                SetValues(tenthsOf(d.weight)!!, d.reps.trim().toInt(), if (h.warmup) null else d.rir), notes, recordedUnit)
        }
        return newStart to edits
    }
}

/** Survives rotation and process death as plain strings: date, open set, then six fields per set. */
@Composable
private fun rememberEditorState(session: SessionSummary, focus: Int?): PastSessionEditorState {
    val zone = remember { ZoneId.systemDefault() }
    return rememberSaveable(session.sessionId, saver = listSaver<PastSessionEditorState, String>(
        save = { s ->
            listOf(s.date?.toString().orEmpty(), (s.expanded ?: -1).toString(), s.showErrors.toString()) +
                s.drafts.flatMap { d -> listOf(d.completed.toString(), d.weight, d.reps, d.rir?.toString().orEmpty(), d.notes, d.unit.name) }
        },
        restore = { v ->
            val drafts = v.drop(3).chunked(6).map { f ->
                SetDraft(f[0].toBoolean(), f[1], f[2], f[3].toIntOrNull(), f[4], WeightUnit.entries.find { it.name == f[5] } ?: WeightUnit.KG)
            }
            PastSessionEditorState(session, zone, v[0].ifEmpty { null }?.let(LocalDate::parse), drafts, v[1].toInt().takeIf { it >= 0 }, v[2].toBoolean())
        })) {
        PastSessionEditorState(session, zone, null, null, focus, false).also { it.date = it.originalDate }
    }
}

/** List items above the first exercise card: the heading, the date and the "Series" header. */
private const val ITEMS_BEFORE_SETS = 3

/**
 * Corrects an archived planned session (requirement 9): its date and the recorded values of each set. It takes the
 * whole screen. Sets are grouped by exercise and shown one line each; tapping one opens its fields, so only the set
 * being corrected takes space. Save and cancel stay pinned at the bottom. Times and the prescription stay as they
 * are; the domain validates the result and keeps the replaced versions.
 *
 * [focusSet] opens the editor on that set, for when the user tapped it in the session detail.
 */
@Composable
fun PastSessionEditor(session: SessionSummary, busy: Boolean, message: String?, focusSet: Int?,
                      onClose: () -> Unit, onSave: (Long?, List<PastSetEdit>) -> Unit) {
    val state = rememberEditorState(session, focusSet)
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState(
        state.expanded?.let { open -> ITEMS_BEFORE_SETS + state.groups.indexOfFirst { open in it } } ?: 0)
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    val leave: () -> Unit = { if (state.dirty) confirmDiscard = true else onClose() }
    BackHandler(enabled = !busy) { leave() }

    fun save() {
        val invalid = state.firstInvalid
        if (invalid != null) {
            // Open the first set that needs attention and bring it into view; its fields say what is wrong.
            state.showErrors = true
            state.expanded = invalid
            scope.launch { list.animateScrollToItem(ITEMS_BEFORE_SETS + state.groups.indexOfFirst { invalid in it }) }
            return
        }
        val (newStart, edits) = state.result()
        onSave(newStart, edits)
    }

    AppScreen("Editar sesión pasada", "Cancelar y volver a la sesión", leave, backEnabled = !busy, busy = busy, message = message,
        listState = list, drillUp = true,
        bottomBar = { EditorActions(state, busy, onCancel = leave, onSave = ::save) }) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text("Editar sesión pasada", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                SupportingText(session.title)
            }
        }
        item { DateCard(state, enabled = !busy) { pickDate = true } }
        item {
            SectionHeader("Series", supporting = if (session.sets.isEmpty()) null
                else "Toca una serie para corregirla. Las versiones anteriores se conservan.")
        }
        if (session.sets.isEmpty()) item { EmptyState("Sin series registradas.") }
        itemsIndexed(state.groups) { _, group -> ExerciseCard(state, group, enabled = !busy) }
    }

    if (pickDate) DatePick(state) { pickDate = false }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        icon = { Icon(AppIcons.Warning, contentDescription = null) },
        title = { Text("¿Descartar los cambios?") },
        text = { Text("Se perderán las correcciones que no has guardado (${changeSummary(state)}).") },
        confirmButton = { QuietAction("Descartar", { confirmDiscard = false; onClose() }, destructive = true) },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Seguir editando") } })
}

private fun changeSummary(state: PastSessionEditorState): String {
    val n = state.changedSets
    val parts = listOfNotNull(if (state.dateChanged) "fecha" else null,
        if (n == 1) "1 serie" else if (n > 1) "$n series" else null)
    return if (parts.isEmpty()) "Sin cambios" else parts.joinToString(" y ")
}

/** Save and cancel, always in reach. Save is off until something changed, and the line beside it says what did. */
@Composable
private fun EditorActions(state: PastSessionEditorState, busy: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(if (state.dirty) "Sin guardar: ${changeSummary(state)}" else "Sin cambios", style = MaterialTheme.typography.labelMedium,
                    color = if (state.dirty) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancelar", maxLines = 1) }
                    Button(onClick = onSave, enabled = state.dirty && !busy, modifier = Modifier.weight(1.4f).heightIn(min = 48.dp)) { Text("Guardar cambios", maxLines = 1) }
                }
            }
        }
    }
}

private val LongDate = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es"))
private val ShortDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("es"))
private val TimeOfDay = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun DateCard(state: PastSessionEditorState, enabled: Boolean, onPick: () -> Unit) {
    // The whole card opens the calendar; the pencil says so, as on the sets of the session detail.
    ContentCard(Modifier.clip(MaterialTheme.shapes.medium).clickable(enabled = enabled, onClickLabel = "Cambiar fecha", onClick = onPick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            IconBadge(AppIcons.Calendar)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Fecha del entrenamiento", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.date?.format(LongDate)?.replaceFirstChar(Char::uppercase) ?: "Sin fecha", style = MaterialTheme.typography.titleMedium)
            }
            Icon(AppIcons.Edit, contentDescription = "Cambiar fecha", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        if (state.dateChanged) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill("Modificada", Tone.Warning)
                Spacer(Modifier.weight(1f))
                QuietAction("Restaurar", { state.date = state.originalDate }, enabled = enabled, icon = AppIcons.Undo)
            }
            SupportingText(listOfNotNull(state.originalDate?.let { "Antes: ${it.format(ShortDate)}" },
                if (state.originalDate != null) "Se conserva la hora de inicio (${state.timeOfDay.format(TimeOfDay)})" else "Se guardará a las 12:00"
            ).joinToString(". ") + ".", small = true)
        }
    }
}

/** A calendar instead of a typed date. Days after today cannot be chosen: a recorded session is never in the future. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePick(state: PastSessionEditorState, onDone: () -> Unit) {
    val today = remember { LocalDate.now() }
    // The picker works in UTC days, whatever the device's zone.
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = (state.date ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            override fun isSelectableYear(year: Int) = year <= today.year
        })
    DatePickerDialog(onDismissRequest = onDone,
        confirmButton = {
            TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { state.date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                onDone()
            }) { Text("Aceptar") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancelar") } }) {
        DatePicker(picker)
    }
}

/** One exercise: its sets one line each, the open one with its fields under it. */
@Composable
private fun ExerciseCard(state: PastSessionEditorState, group: List<Int>, enabled: Boolean) {
    val exercise = state.session.sets[group.first()].exercise
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg, bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(exercise, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                SupportingText(if (group.size == 1) "1 serie" else "${group.size} series", small = true)
            }
            group.forEach { index ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SetRow(state, index, enabled)
                if (state.expanded == index) SetFields(state, index, enabled)
            }
        }
    }
}

private fun summaryOf(d: SetDraft, h: HistorySet): String {
    if (!d.completed) return "Omitida"
    val load = tenthsOf(d.weight)?.let { loadLabel(it, d.unit) } ?: "carga sin indicar"
    return "$load × ${d.reps.ifBlank { "—" }}" + if (h.warmup) "" else " @ RIR ${rirLabel(d.rir)}"
}

@Composable
private fun SetRow(state: PastSessionEditorState, index: Int, enabled: Boolean) {
    val h = state.session.sets[index]
    val d = state.drafts[index]
    val open = state.expanded == index
    val invalid = state.showErrors && state.errors(index).any
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClickLabel = if (open) "Cerrar" else "Corregir") {
            state.expanded = if (open) null else index
        }.heightIn(min = 64.dp).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(h.kindLabel(), style = MaterialTheme.typography.labelLarge,
                color = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(summaryOf(d, h), style = MaterialTheme.typography.bodyLarge.tabular())
            if (!open && d.notes.isNotBlank()) Text("Nota: ${d.notes.trim()}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (invalid) StatusPill("Revisar", Tone.Error) else if (state.changed(index)) StatusPill("Modificada", Tone.Warning)
        Icon(if (open) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SetFields(state: PastSessionEditorState, index: Int, enabled: Boolean) {
    val h = state.session.sets[index]
    val d = state.drafts[index]
    val errors = state.errors(index)
    fun update(draft: SetDraft) { state.drafts[index] = draft }
    Column(Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SegmentedChoice("Estado", listOf(true, false), d.completed, { if (it) "Completada" else "Omitida" },
            { update(d.copy(completed = it)) }, enabled = enabled)
        if (d.completed) {
            if (h.switchable) SegmentedChoice("Unidad de la carga", listOf(WeightUnit.KG, WeightUnit.LB), d.unit, ::unitLabel,
                { update(d.copy(unit = it)) }, enabled = enabled)
            // Typed errors show once the field has something in it; an untouched empty field waits for the save attempt.
            StepField("Carga (${unitLabel(d.unit)})", d.weight, "carga", decimal = true, enabled = enabled,
                error = errors.weight?.takeIf { state.showErrors || d.weight.isNotBlank() },
                onChange = { text -> update(d.copy(weight = text.filter { it.isDigit() || it == ',' || it == '.' }.take(8))) },
                onStep = { state.stepWeight(index, it) })
            StepField("Repeticiones", d.reps, "repeticiones", decimal = false, enabled = enabled,
                error = errors.reps?.takeIf { state.showErrors || d.reps.isNotBlank() },
                onChange = { text -> update(d.copy(reps = text.filter(Char::isDigit).take(3))) },
                onStep = { state.stepReps(index, it) })
            if (!h.warmup) {
                SegmentedChoice<Int?>("RIR · repeticiones en reserva", (0..4).toList(), d.rir, ::rirLabel,
                    { update(d.copy(rir = it)) }, enabled = enabled)
                if (state.showErrors) errors.rir?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        OutlinedTextField(d.notes, { update(d.copy(notes = it.take(MAX_NOTE_LENGTH))) }, label = { Text("Notas") },
            enabled = enabled, minLines = 2, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (state.changed(index)) QuietAction("Restaurar", { update(state.initial[index]) }, enabled = enabled, icon = AppIcons.Undo)
            Spacer(Modifier.weight(1f))
            TonalAction("Listo", { state.expanded = null }, enabled = enabled, icon = AppIcons.Check, fill = false)
        }
    }
}

/** A number that can be typed or nudged: − and + for the usual small correction, the keyboard for any other value. */
@Composable
private fun StepField(label: String, value: String, what: String, decimal: Boolean, enabled: Boolean, error: String?,
                      onChange: (String) -> Unit, onStep: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        // The buttons sit level with the field's box, which starts below its floating label.
        FilledTonalIconButton(onClick = { onStep(-1) }, enabled = enabled, modifier = Modifier.padding(top = Spacing.sm).size(56.dp)) {
            Icon(AppIcons.Remove, contentDescription = "Reducir $what")
        }
        OutlinedTextField(value, onChange, Modifier.weight(1f), enabled = enabled, label = { Text(label) }, singleLine = true,
            isError = error != null, supportingText = error?.let { { Text(it) } },
            textStyle = MaterialTheme.typography.titleLarge.tabular().copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number, imeAction = ImeAction.Done))
        FilledTonalIconButton(onClick = { onStep(1) }, enabled = enabled, modifier = Modifier.padding(top = Spacing.sm).size(56.dp)) {
            Icon(AppIcons.Add, contentDescription = "Aumentar $what")
        }
    }
}

private fun unitLabel(unit: WeightUnit): String = when (unit) {
    WeightUnit.KG -> "kg"
    WeightUnit.LB -> "lb"
    WeightUnit.MACHINE_SETTING -> "posición"
    WeightUnit.BODYWEIGHT -> "kg extra"
}
