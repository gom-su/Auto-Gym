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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.domain.CardioEntry
import com.example.workoutpoc.domain.DistanceUnit
import com.example.workoutpoc.domain.MAX_EDITED_MILLIS
import com.example.workoutpoc.domain.MAX_NOTE_LENGTH
import com.example.workoutpoc.domain.MAX_REST_OBJECTIVE_SECONDS
import com.example.workoutpoc.domain.PastRestEdit
import com.example.workoutpoc.domain.PastSessionEdit
import com.example.workoutpoc.domain.PastSetEdit
import com.example.workoutpoc.domain.PauseTime
import com.example.workoutpoc.domain.RestTime
import com.example.workoutpoc.domain.SessionTimes
import com.example.workoutpoc.domain.SetStatus
import com.example.workoutpoc.domain.SetValues
import com.example.workoutpoc.domain.WaitTime
import com.example.workoutpoc.domain.WeightUnit
import com.example.workoutpoc.domain.loadLabel
import com.example.workoutpoc.domain.rirLabel
import com.example.workoutpoc.domain.weightLabel
import com.example.workoutpoc.presentation.durationLabel
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The typed values of one set. Weight is in the set's unit, as shown; RIR is null when none is chosen. [duration] is m:ss. */
@Serializable
internal data class SetDraft(val completed: Boolean, val weight: String, val reps: String, val rir: Int?,
                             val notes: String, val unit: WeightUnit, val duration: String = "")

/** A rest as typed: real time (m:ss, empty when not measured) and objective in seconds. */
@Serializable
internal data class RestDraft(val real: String, val objective: String)

/** A cardio entry as typed. Distance is in [unit]; an empty distance stores none. */
@Serializable
internal data class CardioDraft(val modality: String, val duration: String, val distance: String, val unit: DistanceUnit,
                                val notes: String)

/** Everything typed in the editor, as plain values so it survives rotation and process death. */
@Serializable
internal data class EditorDrafts(
    val date: String?, val start: String?, val end: String?,
    val sets: List<SetDraft>, val rests: List<RestDraft>, val waits: List<String>, val pauses: List<String>,
    val cardio: List<CardioDraft>, val expanded: String?, val showErrors: Boolean
)

/** What is wrong with a draft, per field, so each message sits next to the field it is about. */
internal data class SetErrors(val weight: String? = null, val reps: String? = null, val rir: String? = null) {
    val any: Boolean get() = weight != null || reps != null || rir != null
}

internal data class CardioErrors(val modality: String? = null, val duration: String? = null, val distance: String? = null,
                                 val entry: String? = null) {
    val any: Boolean get() = modality != null || duration != null || distance != null || entry != null
}

private fun draftOf(h: HistorySet, millis: Long?) = SetDraft(h.status == SetStatus.COMPLETED, weightLabel(h.values.weightTenths),
    h.values.reps.toString(), h.values.rir, h.notes.orEmpty(), h.unit, millis?.let(::durationText).orEmpty())

private fun cardioDraftOf(c: CardioEntry) = CardioDraft(c.modality, c.durationSeconds?.let { durationText(it * 1000L) }.orEmpty(),
    c.distanceTenths?.let(::weightLabel).orEmpty(), c.distanceUnit ?: DistanceUnit.KM, c.notes.orEmpty())

/** kg and lb can be corrected; machine positions and bodyweight keep their own representation. */
private val HistorySet.switchable: Boolean get() = prescribedUnit == WeightUnit.KG || prescribedUnit == WeightUnit.LB

private fun HistorySet.kindLabel(): String =
    "${if (warmup) "Aproximación" else if (extra) "Serie extra" else "Serie"} $number"

/** Tenths of the typed number, or null when it is not one. NaN and infinities parse as doubles, so they are rejected. */
private fun tenthsOf(text: String): Int? {
    val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return if (value.isFinite() && value >= 0 && value <= 1_000_000) (value * 10).roundToInt() else null
}

/** A time as typed in the editor: m:ss or h:mm:ss, the same shape the history shows. */
internal fun durationText(millis: Long): String = durationLabel(millis)

/** Milliseconds of "m:ss", "h:mm:ss" or plain seconds; null when it is none of them or above 24 h. */
internal fun parseDuration(text: String): Long? {
    val parts = text.trim().split(':')
    if (parts.size > 3 || parts.any { it.isEmpty() || it.length > 5 || !it.all(Char::isDigit) }) return null
    val numbers = parts.map { it.toLong() }
    // Only the first part may exceed 59: "95" and "1:35" are both a minute and 35 s, "1:95" is not a time.
    if (numbers.drop(1).any { it > 59 }) return null
    val seconds = numbers.fold(0L) { total, n -> total * 60 + n }
    return (seconds * 1000).takeIf { it <= MAX_EDITED_MILLIS }
}

/** Error of a typed time: null when valid. An empty field is valid only where no time was recorded. */
private fun durationError(text: String, initial: String): String? = when {
    text.isBlank() -> if (initial.isBlank()) null else "Indica el tiempo (0:00 si no hubo)."
    parseDuration(text) == null -> "Usa m:ss, por ejemplo 1:30."
    else -> null
}

/** Typed time stepped by [stepSeconds], never below zero. */
private fun stepDuration(text: String, direction: Int, stepSeconds: Int = 5): String {
    val seconds = (parseDuration(text) ?: 0) / 1000
    return durationText(((seconds + direction * stepSeconds).coerceIn(0, MAX_EDITED_MILLIS / 1000)) * 1000)
}

private fun setKey(index: Int) = "s$index"
private fun cardioKey(index: Int) = "c$index"

/**
 * The corrections being made to an archived session: its date and start, one draft per recorded set, and its times
 * and cardio item by item. Kept apart from the layout so the list, the pinned actions and the leave confirmation read
 * the same state.
 */
@Stable
internal class PastSessionEditorState(val session: SessionSummary, private val zone: ZoneId, saved: EditorDrafts?) {
    val times: SessionTimes = session.times ?: SessionTimes(false, emptyMap(), emptyList(), emptyList(), emptyList(), null)
    private val start = session.startedAtMillis
    val originalDate: LocalDate? = start?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    /** Without a recorded start the date is set at 12:00 local time; otherwise the time of day is kept. */
    val originalStart: LocalTime = start?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(12, 0)
    /** The end is typed only for sessions without a measured timeline; otherwise it follows the times. */
    val originalEnd: LocalTime? = session.endedAtMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime().withNano(0) }
    val endEditable: Boolean get() = !times.measured

    val initialSets: List<SetDraft> = session.sets.mapIndexed { i, h -> draftOf(h, times.setMillis[i]) }
    val initialRests: List<RestDraft> = times.rests.map { RestDraft(it.realMillis?.let(::durationText).orEmpty(), it.objectiveSeconds.toString()) }
    val initialWaits: List<String> = times.waits.map { durationText(it.millis) }
    val initialPauses: List<String> = times.pauses.map { durationText(it.millis) }
    val initialCardio: List<CardioDraft> = session.cardio.map(::cardioDraftOf)

    val sets = mutableStateListOf<SetDraft>().apply { addAll(saved?.sets?.takeIf { it.size == initialSets.size } ?: initialSets) }
    val rests = mutableStateListOf<RestDraft>().apply { addAll(saved?.rests?.takeIf { it.size == initialRests.size } ?: initialRests) }
    val waits = mutableStateListOf<String>().apply { addAll(saved?.waits?.takeIf { it.size == initialWaits.size } ?: initialWaits) }
    val pauses = mutableStateListOf<String>().apply { addAll(saved?.pauses?.takeIf { it.size == initialPauses.size } ?: initialPauses) }
    val cardio = mutableStateListOf<CardioDraft>().apply { addAll(saved?.cardio?.takeIf { it.size == initialCardio.size } ?: initialCardio) }
    var date by mutableStateOf(if (saved != null) saved.date?.let(LocalDate::parse) else originalDate)
    var startTime by mutableStateOf(saved?.start?.let(LocalTime::parse) ?: originalStart)
    var endTime by mutableStateOf(if (saved != null) saved.end?.let(LocalTime::parse) else originalEnd)
    /** The one set ("s<index>") or cardio entry ("c<index>") whose fields are open; the others show one line. */
    var expanded by mutableStateOf(saved?.expanded)
    /** Set by the first attempt to save: from then on every problem is shown, not only those of fields already typed in. */
    var showErrors by mutableStateOf(saved?.showErrors ?: false)

    fun drafts() = EditorDrafts(date?.toString(), startTime.toString(), endTime?.toString(), sets.toList(), rests.toList(),
        waits.toList(), pauses.toList(), cardio.toList(), expanded, showErrors)

    /** Consecutive sets of one exercise, as indexes into [sets]: one card each. */
    val groups: List<List<Int>> = buildList<MutableList<Int>> {
        session.sets.forEachIndexed { i, h ->
            if (isNotEmpty() && session.sets[last().last()].exercise == h.exercise) last().add(i) else add(mutableListOf(i))
        }
    }

    // Times that belong to a set (its rest, waits and pauses) or to a cardio entry, as indexes into the lists above.
    fun restsOf(set: Int) = times.rests.indices.filter { times.rests[it].setIndex == set }
    fun waitsOfSet(set: Int) = times.waits.indices.filter { times.waits[it].setIndex == set }
    fun pausesOfSet(set: Int) = times.pauses.indices.filter { times.pauses[it].setIndex == set }
    private fun stepOf(entry: Int) = session.cardio[entry].plannedCardioId
    fun waitsOfCardio(entry: Int) = stepOf(entry)?.let { id -> times.waits.indices.filter { times.waits[it].cardioStepId == id } }.orEmpty()
    fun pausesOfCardio(entry: Int) = stepOf(entry)?.let { id -> times.pauses.indices.filter { times.pauses[it].cardioStepId == id } }.orEmpty()

    fun restChanged(i: Int) = rests[i] != initialRests[i]
    fun waitChanged(i: Int) = waits[i] != initialWaits[i]
    fun pauseChanged(i: Int) = pauses[i] != initialPauses[i]
    fun setChanged(index: Int): Boolean = sets[index] != initialSets[index] || restsOf(index).any(::restChanged) ||
        waitsOfSet(index).any(::waitChanged) || pausesOfSet(index).any(::pauseChanged)
    fun cardioChanged(entry: Int): Boolean = cardio[entry] != initialCardio[entry] ||
        waitsOfCardio(entry).any(::waitChanged) || pausesOfCardio(entry).any(::pauseChanged)

    val changedSets: Int get() = sets.indices.count(::setChanged)
    val changedCardio: Int get() = cardio.indices.count(::cardioChanged)
    val dateChanged: Boolean get() = date != originalDate
    val startChanged: Boolean get() = startTime != originalStart
    val endChanged: Boolean get() = endEditable && endTime != originalEnd
    val dirty: Boolean get() = dateChanged || startChanged || endChanged || changedSets > 0 || changedCardio > 0

    fun restore(index: Int) {
        sets[index] = initialSets[index]
        restsOf(index).forEach { rests[it] = initialRests[it] }
        waitsOfSet(index).forEach { waits[it] = initialWaits[it] }
        pausesOfSet(index).forEach { pauses[it] = initialPauses[it] }
    }

    fun restoreCardio(entry: Int) {
        cardio[entry] = initialCardio[entry]
        waitsOfCardio(entry).forEach { waits[it] = initialWaits[it] }
        pausesOfCardio(entry).forEach { pauses[it] = initialPauses[it] }
    }

    fun errors(index: Int): SetErrors {
        val d = sets[index]
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

    fun setDurationError(index: Int) = durationError(sets[index].duration, initialSets[index].duration)
    fun restRealError(i: Int) = durationError(rests[i].real, initialRests[i].real)
    fun restObjectiveError(i: Int): String? = rests[i].objective.trim().toIntOrNull().let {
        if (it == null || it !in 0..MAX_REST_OBJECTIVE_SECONDS) "Entre 0 y $MAX_REST_OBJECTIVE_SECONDS s." else null
    }
    fun waitError(i: Int) = durationError(waits[i], initialWaits[i])
    fun pauseError(i: Int) = durationError(pauses[i], initialPauses[i])

    /** Whether anything about a set, its values or its times, keeps the edit from being saved. */
    fun setInvalid(index: Int): Boolean = errors(index).any || setDurationError(index) != null ||
        restsOf(index).any { restRealError(it) != null || restObjectiveError(it) != null } ||
        waitsOfSet(index).any { waitError(it) != null } || pausesOfSet(index).any { pauseError(it) != null }

    fun cardioErrors(entry: Int): CardioErrors {
        val d = cardio[entry]
        val planned = session.cardio[entry].plannedCardioId != null
        val duration = when {
            d.duration.isBlank() -> if (planned) "El cardio planificado necesita su duración." else null
            parseDuration(d.duration) == null -> "Usa m:ss o h:mm:ss."
            else -> null
        }
        val distance = if (d.distance.isNotBlank() && tenthsOf(d.distance) == null) "Número no válido." else null
        return CardioErrors(
            modality = if (d.modality.isBlank()) "Indica la modalidad." else null,
            duration = duration, distance = distance,
            entry = if (d.duration.isBlank() && d.distance.isBlank() && d.notes.isBlank()) "Registra al menos duración, distancia o una nota." else null)
    }

    fun cardioInvalid(entry: Int): Boolean = cardioErrors(entry).any ||
        waitsOfCardio(entry).any { waitError(it) != null } || pausesOfCardio(entry).any { pauseError(it) != null }

    /** The first item that needs attention, as an [expanded] key. */
    val firstInvalid: String? get() = sets.indices.firstOrNull(::setInvalid)?.let(::setKey)
        ?: cardio.indices.firstOrNull(::cardioInvalid)?.let(::cardioKey)

    fun stepWeight(index: Int, direction: Int) {
        val h = session.sets[index]
        val next = ((tenthsOf(sets[index].weight) ?: 0) + direction * h.weightStepTenths).coerceIn(0, h.maxWeightTenths)
        sets[index] = sets[index].copy(weight = weightLabel(next))
    }

    fun stepReps(index: Int, direction: Int) {
        val next = ((sets[index].reps.trim().toIntOrNull() ?: 0) + direction).coerceIn(0, session.sets[index].maxReps)
        sets[index] = sets[index].copy(reps = next.toString())
    }

    private fun startInstant(): Long? = date?.atTime(startTime)?.atZone(zone)?.toInstant()?.toEpochMilli()

    /** The session's end as it now stands, for the summary: typed, or the recorded end moved by the corrected times. */
    fun endPreview(): Long? {
        if (endEditable) {
            val start = startInstant() ?: return null
            val end = endTime ?: return null
            val startDay = date ?: return null
            // An end earlier in the day than the start is the next day.
            val day = if (end.isBefore(startTime)) startDay.plusDays(1) else startDay
            return day.atTime(end).atZone(zone).toInstant().toEpochMilli().takeIf { it >= start }
        }
        val recorded = session.endedAtMillis ?: return null
        val shift = (startInstant() ?: return recorded) - (session.startedAtMillis ?: return recorded)
        return recorded + shift + totalsDelta().let { it.activity + it.rest + it.pause + it.wait }
    }

    data class Delta(val activity: Long, val rest: Long, val pause: Long, val wait: Long)

    private fun change(text: String, initial: String): Long =
        if (text == initial) 0 else (parseDuration(text) ?: 0) - (parseDuration(initial) ?: 0)

    /** How much each total changes with what is typed, item by item, as the domain will apply it. */
    fun totalsDelta(): Delta {
        val setDelta = sets.indices.sumOf { change(sets[it].duration, initialSets[it].duration) }
        val cardioDelta = cardio.indices.filter { session.cardio[it].plannedCardioId != null }
            .sumOf { change(cardio[it].duration, initialCardio[it].duration) }
        return Delta(if (times.measured) setDelta + cardioDelta else 0,
            rests.indices.sumOf { change(rests[it].real, initialRests[it].real) },
            pauses.indices.sumOf { change(pauses[it], initialPauses[it]) },
            waits.indices.sumOf { change(waits[it], initialWaits[it]) })
    }

    /** Sum of the excess of each rest over its objective, as typed (requirement 12). */
    fun excessMillis(): Long = rests.indices.sumOf { i ->
        val real = parseDuration(rests[i].real) ?: return@sumOf 0L
        val objective = rests[i].objective.trim().toIntOrNull() ?: return@sumOf 0L
        (real - objective * 1000L).coerceAtLeast(0)
    }

    /** Every correction as the domain takes it. Call only when nothing is invalid. */
    fun result(): PastSessionEdit {
        val newStart = startInstant()?.takeIf { dateChanged || startChanged }
        val edits = sets.mapIndexed { index, d ->
            val h = session.sets[index]
            val notes = d.notes.trim().ifEmpty { null }
            val recordedUnit = if (d.unit != h.prescribedUnit) d.unit else null
            if (!d.completed) PastSetEdit(index, SetStatus.SKIPPED, h.values, notes, recordedUnit)
            else PastSetEdit(index, SetStatus.COMPLETED,
                SetValues(tenthsOf(d.weight)!!, d.reps.trim().toInt(), if (h.warmup) null else d.rir), notes, recordedUnit)
        }
        val setMillis = sets.indices.filter { sets[it].duration != initialSets[it].duration && sets[it].duration.isNotBlank() }
            .associateWith { parseDuration(sets[it].duration)!! }
        val restEdits = rests.indices.filter(::restChanged).map { i ->
            PastRestEdit(times.rests[i].id, rests[i].objective.trim().toInt(), rests[i].real.takeIf { it.isNotBlank() }?.let(::parseDuration))
        }
        val waitMillis = waits.indices.filter(::waitChanged).associate { times.waits[it].id to parseDuration(waits[it])!! }
        val pauseMillis = pauses.indices.filter(::pauseChanged).associateWith { parseDuration(pauses[it])!! }
        val cardioEdits = cardio.indices.filter { cardio[it] != initialCardio[it] }.map { i ->
            val d = cardio[i]
            val distance = d.distance.takeIf { it.isNotBlank() }?.let(::tenthsOf)
            session.cardio[i].copy(modality = d.modality.trim(),
                durationSeconds = d.duration.takeIf { it.isNotBlank() }?.let { (parseDuration(it)!! / 1000).toInt() },
                distanceTenths = distance, distanceUnit = distance?.let { d.unit }, notes = d.notes.trim().ifEmpty { null })
        }
        return PastSessionEdit(newStart, edits, setMillis, restEdits, waitMillis, pauseMillis, cardioEdits,
            endPreview()?.takeIf { endChanged })
    }
}

private val draftJson = Json { ignoreUnknownKeys = true }

/** Survives rotation and process death as one JSON string of the drafts. */
@Composable
private fun rememberEditorState(session: SessionSummary, focus: Int?): PastSessionEditorState {
    val zone = remember { ZoneId.systemDefault() }
    return rememberSaveable(session.sessionId, saver = Saver<PastSessionEditorState, String>(
        save = { draftJson.encodeToString(EditorDrafts.serializer(), it.drafts()) },
        restore = { PastSessionEditorState(session, zone, draftJson.decodeFromString(EditorDrafts.serializer(), it)) })) {
        PastSessionEditorState(session, zone, null).also { s -> s.expanded = focus?.takeIf { it in session.sets.indices }?.let(::setKey) }
    }
}

/** List items above the first exercise card: the heading, the date and time, the times summary and the "Series" header. */
private const val ITEMS_BEFORE_SETS = 4

/**
 * Corrects an archived planned session (requirement 9): its date and start, the recorded values of each set, every
 * time item by item (set, rest and its objective, machine wait, pause) and the recorded cardio. It takes the whole
 * screen. Sets are grouped by exercise and shown one line each; tapping one opens its values and its times, so only
 * the item being corrected takes space. The summary of times follows what is typed. Save and cancel stay pinned at the
 * bottom. The prescription stays as it is; the domain validates the result.
 *
 * [focusSet] opens the editor on that set, for when the user tapped it in the session detail.
 */
@Composable
fun PastSessionEditor(session: SessionSummary, busy: Boolean, message: String?, focusSet: Int?,
                      onClose: () -> Unit, onSave: (PastSessionEdit) -> Unit) {
    val state = rememberEditorState(session, focusSet)
    val scope = rememberCoroutineScope()
    fun itemOf(key: String?): Int? = when {
        key == null -> null
        key.startsWith("s") -> key.drop(1).toIntOrNull()?.let { open -> ITEMS_BEFORE_SETS + state.groups.indexOfFirst { open in it } }
        // Cardio follows the exercise cards and its own header.
        else -> key.drop(1).toIntOrNull()?.let { ITEMS_BEFORE_SETS + state.groups.size + 1 + it }
    }
    val list = rememberLazyListState(itemOf(state.expanded) ?: 0)
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf<String?>(null) }
    val leave: () -> Unit = { if (state.dirty) confirmDiscard = true else onClose() }
    // Back is held while a save runs, so it cannot fall through to the tabs and leave the screen mid-save.
    BackHandler { if (!busy) leave() }

    fun save() {
        val invalid = state.firstInvalid
        if (invalid != null) {
            // Open the first item that needs attention and bring it into view; its fields say what is wrong.
            state.showErrors = true
            state.expanded = invalid
            itemOf(invalid)?.let { scope.launch { list.animateScrollToItem(it) } }
            return
        }
        onSave(state.result())
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
        item { DateCard(state, enabled = !busy, onPickDate = { pickDate = true }, onPickTime = { pickTime = it }) }
        item { TimesSummary(state) }
        item {
            SectionHeader("Series", supporting = if (session.sets.isEmpty()) null
                else "Toca una serie para corregir sus valores y sus tiempos. Las versiones anteriores de los valores se conservan.")
        }
        if (session.sets.isEmpty()) item { EmptyState("Sin series registradas.") }
        itemsIndexed(state.groups) { _, group -> ExerciseCard(state, group, enabled = !busy) }
        if (session.cardio.isNotEmpty()) {
            item { SectionHeader("Cardio", supporting = "Toca un registro para corregirlo.") }
            itemsIndexed(session.cardio) { i, _ -> CardioCard(state, i, enabled = !busy) }
        }
    }

    if (pickDate) DatePick(state) { pickDate = false }
    pickTime?.let { which ->
        TimePick(if (which == "end") state.endTime ?: state.startTime else state.startTime,
            title = if (which == "end") "Hora de fin" else "Hora de inicio", onDismiss = { pickTime = null }) { picked ->
            if (which == "end") state.endTime = picked
            // A start picked at the minute it already had keeps its recorded seconds.
            else if (picked.hour != state.startTime.hour || picked.minute != state.startTime.minute) state.startTime = picked
            pickTime = null
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        icon = { Icon(AppIcons.Warning, contentDescription = null) },
        title = { Text("¿Descartar los cambios?") },
        text = { Text("Se perderán las correcciones que no has guardado (${changeSummary(state)}).") },
        confirmButton = { QuietAction("Descartar", { confirmDiscard = false; onClose() }, destructive = true) },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Seguir editando") } })
}

private fun changeSummary(state: PastSessionEditorState): String {
    val n = state.changedSets
    val c = state.changedCardio
    val parts = listOfNotNull(if (state.dateChanged) "fecha" else null,
        if (state.startChanged || state.endChanged) "hora" else null,
        if (n == 1) "1 serie" else if (n > 1) "$n series" else null,
        if (c == 1) "1 cardio" else if (c > 1) "$c cardios" else null)
    return if (parts.isEmpty()) "Sin cambios" else parts.joinToString(", ")
}

/** Save and cancel, always in reach. Save is off until something changed, and the line beside it says what did. */
@Composable
private fun EditorActions(state: PastSessionEditorState, busy: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(if (state.dirty) "Sin guardar: ${changeSummary(state)}" else "Sin cambios", style = MaterialTheme.typography.labelMedium,
                    color = if (state.dirty) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
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

/** One tappable line of the date card: an icon, a label and its value, with a pencil saying it can be changed. */
@Composable
private fun PickRow(label: String, value: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(enabled = enabled, onClickLabel = action, onClick = onClick)
        .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
        Icon(AppIcons.Edit, contentDescription = action, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DateCard(state: PastSessionEditorState, enabled: Boolean, onPickDate: () -> Unit, onPickTime: (String) -> Unit) {
    ContentCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            IconBadge(AppIcons.Calendar)
            Column(Modifier.weight(1f)) {
                PickRow("Fecha del entrenamiento", state.date?.format(LongDate)?.replaceFirstChar(Char::uppercase) ?: "Sin fecha",
                    "Cambiar fecha", enabled, onPickDate)
                PickRow("Hora de inicio", state.startTime.format(TimeOfDay), "Cambiar hora de inicio", enabled && state.date != null) { onPickTime("start") }
                if (state.endEditable) PickRow("Hora de fin", state.endTime?.format(TimeOfDay) ?: "Sin dato", "Cambiar hora de fin",
                    enabled && state.date != null) { onPickTime("end") }
                else {
                    val end = state.endPreview()
                    LabeledLine("Hora de fin", end?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().format(TimeOfDay) } ?: "Sin dato")
                }
            }
        }
        if (!state.endEditable && state.session.endedAtMillis != null) SupportingText("La hora de fin se calcula con los tiempos de la sesión.", small = true)
        if (state.dateChanged || state.startChanged || state.endChanged) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill("Modificada", Tone.Warning)
                Spacer(Modifier.weight(1f))
                QuietAction("Restaurar", {
                    state.date = state.originalDate; state.startTime = state.originalStart; state.endTime = state.originalEnd
                }, enabled = enabled, icon = AppIcons.Undo)
            }
            SupportingText(listOfNotNull(
                state.originalDate?.let { "Antes: ${it.format(ShortDate)}, inicio ${state.originalStart.format(TimeOfDay)}" },
                if (state.originalDate == null && !state.startChanged) "Se guardará a las 12:00" else null,
                if (state.endEditable && state.endTime?.isBefore(state.startTime) == true) "El fin cuenta como del día siguiente" else null
            ).joinToString(". ").ifEmpty { "Cambios sin guardar" } + ".", small = true)
        }
    }
}

@Composable
private fun LabeledLine(label: String, value: String) {
    Column(Modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/** The session's totals as they will be after saving, so a corrected time shows its effect at once. */
@Composable
private fun TimesSummary(state: PastSessionEditorState) {
    val time = state.session.review?.time
    ContentCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            IconBadge(AppIcons.Timer)
            Text("Tiempos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (state.times.editedAtMillis != null) StatusPill("Corregidos", Tone.Info)
        }
        if (time == null) {
            SupportingText("Sin dato. Esta sesión no registró tiempos por serie; puedes corregir sus descansos y la hora de fin.")
        } else {
            val d = state.totalsDelta()
            val activity = time.activityMillis + d.activity
            val rest = time.restMillis + d.rest
            val pause = time.pauseMillis + d.pause
            val wait = time.waitMillis + d.wait
            Text("Total ${durationLabel(activity + rest + pause + wait)}", style = MaterialTheme.typography.titleLarge.tabular())
            SupportingText("Actividad ${durationLabel(activity)} · Descanso ${durationLabel(rest)} · Pausa ${durationLabel(pause)}" +
                (if (wait > 0) " · Espera por máquina ${durationLabel(wait)}" else ""))
        }
        if (state.times.rests.isNotEmpty()) SupportingText("Exceso de descanso ${durationLabel(state.excessMillis())}")
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

/** A clock instead of a typed time, in 24-hour format as the rest of the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePick(initial: LocalTime, title: String, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val picker = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { TimePicker(picker) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(picker.hour, picker.minute)) }) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
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
                if (state.expanded == setKey(index)) SetFields(state, index, enabled)
            }
        }
    }
}

private fun summaryOf(d: SetDraft, h: HistorySet): String {
    if (!d.completed) return "Omitida"
    val load = tenthsOf(d.weight)?.let { loadLabel(it, d.unit) } ?: "carga sin indicar"
    return "$load × ${d.reps.ifBlank { "—" }}" + if (h.warmup) "" else " @ RIR ${rirLabel(d.rir)}"
}

/** The times of a set in one line: its duration and the rest after it, or nothing when none was recorded. */
private fun timesLine(state: PastSessionEditorState, index: Int): String? = listOfNotNull(
    state.sets[index].duration.takeIf { it.isNotBlank() }?.let { "Duración $it" },
    *state.restsOf(index).map { i ->
        "Descanso ${state.rests[i].real.ifBlank { "sin dato" }} (objetivo ${state.rests[i].objective} s)"
    }.toTypedArray(),
    state.waitsOfSet(index).takeIf { it.isNotEmpty() }?.let { w -> "Espera ${w.joinToString(" + ") { state.waits[it] }}" }
).joinToString(" · ").ifEmpty { null }

@Composable
private fun SetRow(state: PastSessionEditorState, index: Int, enabled: Boolean) {
    val h = state.session.sets[index]
    val d = state.sets[index]
    val open = state.expanded == setKey(index)
    val invalid = state.showErrors && state.setInvalid(index)
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClickLabel = if (open) "Cerrar" else "Corregir") {
            state.expanded = if (open) null else setKey(index)
        }.heightIn(min = 64.dp).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(h.kindLabel(), style = MaterialTheme.typography.labelLarge,
                color = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(summaryOf(d, h), style = MaterialTheme.typography.bodyLarge.tabular())
            if (!open) timesLine(state, index)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!open && d.notes.isNotBlank()) Text("Nota: ${d.notes.trim()}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (invalid) StatusPill("Revisar", Tone.Error) else if (state.setChanged(index)) StatusPill("Modificada", Tone.Warning)
        Icon(if (open) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SetFields(state: PastSessionEditorState, index: Int, enabled: Boolean) {
    val h = state.session.sets[index]
    val d = state.sets[index]
    val errors = state.errors(index)
    fun update(draft: SetDraft) { state.sets[index] = draft }
    Column(Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SegmentedChoice("Estado", listOf(true, false), d.completed, { if (it) "Completada" else "Omitida" },
            { update(d.copy(completed = it)) }, enabled = enabled)
        if (d.completed) {
            if (h.switchable) SegmentedChoice("Unidad de la carga", listOf(WeightUnit.KG, WeightUnit.LB), d.unit, ::unitLabel,
                { update(d.copy(unit = it)) }, enabled = enabled)
            // Typed errors show once the field has something in it; an untouched empty field waits for the save attempt.
            StepField("Carga (${unitLabel(d.unit)})", d.weight, "carga", KeyboardType.Decimal, enabled = enabled,
                error = errors.weight?.takeIf { state.showErrors || d.weight.isNotBlank() },
                onChange = { text -> update(d.copy(weight = text.filter { it.isDigit() || it == ',' || it == '.' }.take(8))) },
                onStep = { state.stepWeight(index, it) })
            StepField("Repeticiones", d.reps, "repeticiones", KeyboardType.Number, enabled = enabled,
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
        SetTimes(state, index, enabled)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (state.setChanged(index)) QuietAction("Restaurar", { state.restore(index) }, enabled = enabled, icon = AppIcons.Undo)
            Spacer(Modifier.weight(1f))
            TonalAction("Listo", { state.expanded = null }, enabled = enabled, icon = AppIcons.Check, fill = false)
        }
    }
}

/** The times that belong to a set: its own duration, the rest after it with its objective, and its waits and pauses. */
@Composable
private fun SetTimes(state: PastSessionEditorState, index: Int, enabled: Boolean) {
    val rests = state.restsOf(index)
    val waits = state.waitsOfSet(index)
    val pauses = state.pausesOfSet(index)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Tiempos", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    if (state.times.measured) DurationField("Duración de la serie", state.sets[index].duration, state.setDurationError(index), state.showErrors,
        enabled) { state.sets[index] = state.sets[index].copy(duration = it) }
    else SupportingText("Duración de la serie: Sin dato. Esta sesión no registró tiempos por serie.", small = true)
    rests.forEach { i -> RestFields(state, state.times.rests[i], i, enabled) }
    if (state.times.measured && rests.isEmpty()) SupportingText("Sin descanso registrado después de esta serie.", small = true)
    waits.forEach { i -> WaitField(state, state.times.waits[i], i, enabled) }
    pauses.forEach { i -> PauseField(state, state.times.pauses[i], i, enabled) }
}

@Composable
private fun RestFields(state: PastSessionEditorState, rest: RestTime, i: Int, enabled: Boolean) {
    val d = state.rests[i]
    val note = listOfNotNull(rest.originalSeconds?.takeIf { it != rest.objectiveSeconds }?.let { "Objetivo del plan: $it s" },
        "Saltado".takeIf { rest.skipped }, "Interrumpido al terminar la sesión".takeIf { rest.interrupted }).joinToString(" · ")
    DurationField("Descanso real", d.real, state.restRealError(i), state.showErrors, enabled,
        placeholder = "Sin dato") { state.rests[i] = state.rests[i].copy(real = it) }
    StepField("Objetivo de descanso (s)", d.objective, "objetivo de descanso", KeyboardType.Number, enabled = enabled,
        error = state.restObjectiveError(i),
        onChange = { text -> state.rests[i] = state.rests[i].copy(objective = text.filter(Char::isDigit).take(4)) },
        onStep = { dir ->
            val next = ((d.objective.trim().toIntOrNull() ?: 0) + dir * 15).coerceIn(0, MAX_REST_OBJECTIVE_SECONDS)
            state.rests[i] = state.rests[i].copy(objective = next.toString())
        })
    if (note.isNotEmpty()) SupportingText(note, small = true)
}

@Composable
private fun WaitField(state: PastSessionEditorState, wait: WaitTime, i: Int, enabled: Boolean) {
    DurationField(if (wait.fromRest) "Espera por máquina (tras el descanso)" else "Espera por máquina", state.waits[i],
        state.waitError(i), state.showErrors, enabled) { state.waits[i] = it }
}

@Composable
private fun PauseField(state: PastSessionEditorState, pause: PauseTime, i: Int, enabled: Boolean) {
    DurationField(if (pause.duringRest) "Pausa (durante el descanso)" else "Pausa", state.pauses[i],
        state.pauseError(i), state.showErrors, enabled) { state.pauses[i] = it }
}

/** A recorded cardio entry: one line, and its fields when open. */
@Composable
private fun CardioCard(state: PastSessionEditorState, entry: Int, enabled: Boolean) {
    val recorded = state.session.cardio[entry]
    val d = state.cardio[entry]
    val open = state.expanded == cardioKey(entry)
    val invalid = state.showErrors && state.cardioInvalid(entry)
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClickLabel = if (open) "Cerrar" else "Corregir") {
                    state.expanded = if (open) null else cardioKey(entry)
                }.heightIn(min = 64.dp).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (recorded.plannedCardioId != null) "Cardio planificado" else "Cardio añadido", style = MaterialTheme.typography.labelLarge,
                        color = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(listOfNotNull(d.modality.ifBlank { "Sin modalidad" }, d.duration.ifBlank { null },
                        d.distance.ifBlank { null }?.let { "$it ${d.unit.name.lowercase()}" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyLarge.tabular())
                    if (!open && d.notes.isNotBlank()) Text("Nota: ${d.notes.trim()}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (invalid) StatusPill("Revisar", Tone.Error) else if (state.cardioChanged(entry)) StatusPill("Modificado", Tone.Warning)
                Icon(if (open) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) CardioFields(state, entry, enabled)
        }
    }
}

@Composable
private fun CardioFields(state: PastSessionEditorState, entry: Int, enabled: Boolean) {
    val d = state.cardio[entry]
    val errors = state.cardioErrors(entry)
    fun update(draft: CardioDraft) { state.cardio[entry] = draft }
    Column(Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        OutlinedTextField(d.modality, { update(d.copy(modality = it.take(200))) }, Modifier.fillMaxWidth(), enabled = enabled,
            label = { Text("Modalidad") }, singleLine = true, isError = errors.modality != null && (state.showErrors || d.modality.isEmpty()),
            supportingText = errors.modality?.let { { Text(it) } })
        DurationField("Duración", d.duration, errors.duration, state.showErrors, enabled, stepSeconds = 30,
            placeholder = "Sin dato") { update(d.copy(duration = it)) }
        if (state.session.cardio[entry].plannedCardioId != null)
            SupportingText("Si la duración es menor al 95% de la planificada, el cardio cuenta como sin completar.", small = true)
        OutlinedTextField(d.distance, { text -> update(d.copy(distance = text.filter { it.isDigit() || it == ',' || it == '.' }.take(9))) },
            Modifier.fillMaxWidth(), enabled = enabled, label = { Text("Distancia (opcional)") }, singleLine = true,
            isError = errors.distance != null, supportingText = errors.distance?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done))
        if (d.distance.isNotBlank()) SegmentedChoice("Unidad de distancia", DistanceUnit.entries, d.unit, { it.name.lowercase() },
            { update(d.copy(unit = it)) }, enabled = enabled)
        OutlinedTextField(d.notes, { update(d.copy(notes = it.take(MAX_NOTE_LENGTH))) }, label = { Text("Notas") },
            enabled = enabled, minLines = 2, modifier = Modifier.fillMaxWidth())
        if (state.showErrors) errors.entry?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        state.waitsOfCardio(entry).forEach { i -> WaitField(state, state.times.waits[i], i, enabled) }
        state.pausesOfCardio(entry).forEach { i -> PauseField(state, state.times.pauses[i], i, enabled) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (state.cardioChanged(entry)) QuietAction("Restaurar", { state.restoreCardio(entry) }, enabled = enabled, icon = AppIcons.Undo)
            Spacer(Modifier.weight(1f))
            TonalAction("Listo", { state.expanded = null }, enabled = enabled, icon = AppIcons.Check, fill = false)
        }
    }
}

/** A time typed as m:ss, with − and + for the usual small correction. Its error shows once typed in or after a save attempt. */
@Composable
private fun DurationField(label: String, value: String, error: String?, showErrors: Boolean, enabled: Boolean,
                          stepSeconds: Int = 5, placeholder: String? = null, onChange: (String) -> Unit) {
    StepField(label, value, label.lowercase(), KeyboardType.Ascii, enabled = enabled,
        error = error?.takeIf { showErrors || value.isNotBlank() }, placeholder = placeholder,
        onChange = { text -> onChange(text.filter { it.isDigit() || it == ':' }.take(8)) },
        onStep = { onChange(stepDuration(value, it, stepSeconds)) })
}

/** A value that can be typed or nudged: − and + for the usual small correction, the keyboard for any other value. */
@Composable
private fun StepField(label: String, value: String, what: String, keyboard: KeyboardType, enabled: Boolean, error: String?,
                      placeholder: String? = null, onChange: (String) -> Unit, onStep: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        // The buttons sit level with the field's box, which starts below its floating label.
        FilledTonalIconButton(onClick = { onStep(-1) }, enabled = enabled, modifier = Modifier.padding(top = Spacing.sm).size(56.dp)) {
            Icon(AppIcons.Remove, contentDescription = "Reducir $what")
        }
        OutlinedTextField(value, onChange, Modifier.weight(1f), enabled = enabled, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true, isError = error != null, supportingText = error?.let { { Text(it) } },
            placeholder = placeholder?.let { { Text(it, Modifier.fillMaxWidth(), textAlign = TextAlign.Center) } },
            textStyle = MaterialTheme.typography.titleLarge.tabular().copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done))
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
