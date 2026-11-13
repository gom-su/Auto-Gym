package com.example.workoutpoc.history

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.workoutpoc.domain.SetStatus
import com.example.workoutpoc.domain.loadLabel
import com.example.workoutpoc.domain.rirLabel
import com.example.workoutpoc.presentation.durationLabel
import com.example.workoutpoc.workoutApp
import com.example.workoutpoc.planning.DayStatus
import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryUiState(val ready: Boolean = false, val busy: Boolean = false, val message: String? = null,
                          val sessions: List<SessionSummary> = emptyList(),
                          /** Sessions already archived: only these can be corrected (the current one is not archived yet). */
                          val editable: Set<String> = emptySet())

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.workoutApp
    private val mutableUi = MutableStateFlow(HistoryUiState())
    val ui = mutableUi.asStateFlow()
    private var pendingCsv: String? = null
    init { refresh() }
    /** Prepares the CSV from storage, then [onReady] opens the system save dialog. */
    fun prepareCsv(onReady: (String) -> Unit) {
        if (ui.value.busy) return
        mutableUi.value = mutableUi.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                app.repository.restore()
                val now = System.currentTimeMillis()
                pendingCsv = HistoryCsv.encode(app.repository.archived.value.sessions, app.repository.state.value, now)
                onReady("auto-gym-historial-${java.time.LocalDate.now()}.csv")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableUi.value = mutableUi.value.copy(message = "No se pudo preparar el CSV. Tus datos se conservan. Reintenta.") }
            finally { mutableUi.value = mutableUi.value.copy(busy = false) }
        }
    }
    /** Writes the prepared CSV to the destination the user chose; nothing is written without one. */
    fun writeCsv(uri: android.net.Uri?) {
        val text = pendingCsv
        pendingCsv = null
        if (uri == null || ui.value.busy) return
        mutableUi.value = mutableUi.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                if (text == null) throw IllegalStateException()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: throw IllegalStateException()
                }
                mutableUi.value = mutableUi.value.copy(message = "Historial exportado en CSV.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableUi.value = mutableUi.value.copy(message = "No se pudo exportar el CSV. Vuelve a intentarlo.") }
            finally { mutableUi.value = mutableUi.value.copy(busy = false) }
        }
    }
    /** Saves a correction of an archived session (requirement 9). [onDone] gets true only when it was stored. */
    fun saveEdit(sessionId: String, newStartMillis: Long?, sets: List<com.example.workoutpoc.domain.PastSetEdit>, onDone: (Boolean) -> Unit) {
        if (ui.value.busy) return
        mutableUi.value = mutableUi.value.copy(busy = true, message = null)
        viewModelScope.launch {
            var saved = false
            try {
                saved = app.repository.editArchivedSession(sessionId, newStartMillis, sets)
                mutableUi.value = mutableUi.value.copy(message = if (saved) "Sesión actualizada." else "No había cambios que guardar.")
            } catch (e: CancellationException) { throw e }
            catch (e: com.example.workoutpoc.domain.WorkoutEditException) {
                mutableUi.value = mutableUi.value.copy(message = e.message ?: "Revisa los datos de la sesión.")
            }
            catch (_: Exception) { mutableUi.value = mutableUi.value.copy(message = "No se pudo guardar la sesión. Tus datos se conservan.") }
            finally { mutableUi.value = mutableUi.value.copy(busy = false) }
            // The reload keeps the confirmation on screen instead of clearing it.
            if (saved) load("Sesión actualizada.")
            onDone(saved)
        }
    }

    fun refresh() = load(null)

    private fun load(message: String?) {
        if (ui.value.busy) return
        mutableUi.value = mutableUi.value.copy(busy = true, message = message)
        viewModelScope.launch {
            try {
                app.repository.restore()
                val archivedIds = app.repository.archived.value.sessions.map { it.sessionId }.toSet()
                mutableUi.value = mutableUi.value.copy(ready = true, editable = archivedIds,
                    sessions = history(app.repository.archived.value.sessions, app.repository.state.value, System.currentTimeMillis()))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableUi.value = mutableUi.value.copy(message = "No se pudo leer el historial. Tus datos se conservan. Reintenta.") }
            finally { mutableUi.value = mutableUi.value.copy(busy = false) }
        }
    }
}

/** Recorded sessions, newest first, with a detail view per session; an archived one can be corrected from its detail. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(ui: HistoryUiState, onBack: () -> Unit, onRetry: () -> Unit, onExportCsv: () -> Unit = {},
                  onSaveEdit: (String, Long?, List<com.example.workoutpoc.domain.PastSetEdit>, (Boolean) -> Unit) -> Unit = { _, _, _, done -> done(false) }) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    // The set tapped in the detail, which the editor opens on; -1 when it was opened with its button.
    var editFocus by rememberSaveable { mutableStateOf(-1) }
    // "*" lists every program; otherwise a program ID, or "" for the demo session.
    var program by rememberSaveable { mutableStateOf("*") }
    val programs = programsIn(ui.sessions)
    val listed = if (program == "*") ui.sessions else ui.sessions.filter { (it.programId ?: "") == program }
    val format = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val session = ui.sessions.find { it.sessionId == selected }
    val canEdit = session != null && !session.demo && session.sessionId in ui.editable
    // Remembered here, above the editor, so the detail is where it was when the editor closes.
    val listState = rememberViewListState(session?.sessionId)
    if (session != null && canEdit && editing) {
        PastSessionEditor(session, ui.busy, ui.message, editFocus.takeIf { it >= 0 }, onClose = { editing = false }) { newStart, edits ->
            onSaveEdit(session.sessionId, newStart, edits) { saved -> if (saved) editing = false }
        }
        return
    }
    BackHandler(session != null) { selected = null }
    AppScreen("Historial de sesiones", if (session != null) "Ver todas las sesiones" else "Volver a Programas",
        { if (session != null) selected = null else onBack() }, busy = ui.busy, message = ui.message, listState = listState, drillUp = session != null) {
        if (!ui.ready && !ui.busy) item { RetryAction("Reintentar", onRetry, enabled = !ui.busy) }
        if (session == null) {
            if (ui.ready && ui.sessions.isEmpty()) item { EmptyState("Aún no hay sesiones registradas.", icon = AppIcons.History) }
            if (listed.isNotEmpty()) item {
                val t = totals(listed)
                MetricRow(listOf("${t.sessions}" to "sesiones", "${t.completedSets}" to "series hechas",
                    "${t.omittedSets}" to "omitidas", durationLabel(t.activeMillis) to "tiempo activo"))
            }
            if (programs.size > 1) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ChoiceChip(program == "*", { program = "*" }, label = { Text("Todos los programas") })
                    programs.forEach { (id, name) ->
                        ChoiceChip(program == (id ?: ""), { program = id ?: "" }, label = { Text(name) })
                    }
                }
            }
            if (ui.sessions.isNotEmpty()) item {
                TonalAction("Exportar CSV", onExportCsv, enabled = !ui.busy, icon = AppIcons.Download, fill = false)
            }
            items(listed, key = { it.sessionId }) { s ->
                NavRow("${s.startedAtMillis?.let { format.format(Date(it)) } ?: "sin fecha"} · ${s.title}", { selected = s.sessionId; editing = false },
                    supporting = statusLine(s), badge = { StatusPill(s.status.label, s.status.tone()) })
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(session.title, style = MaterialTheme.typography.headlineSmall)
                    SupportingText(listOfNotNull(session.startedAtMillis?.let { "Inicio ${format.format(Date(it))}" },
                        session.endedAtMillis?.let { "fin ${format.format(Date(it))}" },
                        session.activeMillis?.let { "tiempo activo ${durationLabel(it)}" }).joinToString(" · "))
                }
            }
            item {
                Text(statusLine(session), style = MaterialTheme.typography.titleSmall)
            }
            // Before the review, which can be several screens long: the action is found without scrolling.
            if (canEdit) item {
                TonalAction("Editar sesión pasada", { editFocus = -1; editing = true }, enabled = !ui.busy, icon = AppIcons.Edit)
            }
            session.review?.let { review ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        com.example.workoutpoc.presentation.SessionReviewContent(review)
                    }
                }
            }
            item { SectionHeader("Series", supporting = if (canEdit && session.sets.isNotEmpty()) "Toca una serie para corregirla." else null) }
            if (session.sets.isEmpty()) item { EmptyState("Sin series registradas.") }
            items(session.sets.size) { i ->
                val h = session.sets[i]
                ContentCard(if (canEdit) Modifier.clip(MaterialTheme.shapes.medium)
                    .clickable(enabled = !ui.busy, onClickLabel = "Corregir serie") { editFocus = i; editing = true } else Modifier) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${h.exercise} · ${if (h.warmup) "aproximación" else if (h.extra) "serie extra" else "serie"} ${h.number}",
                                style = MaterialTheme.typography.titleSmall)
                            Text(if (h.status == SetStatus.SKIPPED) "Omitida" else
                                "${loadLabel(h.values.weightTenths, h.unit)} × ${h.values.reps}" + (if (h.warmup) "" else " @ RIR ${rirLabel(h.values.rir)}") + if (h.edited) " · editada" else "",
                                style = MaterialTheme.typography.bodyLarge.tabular())
                        }
                        if (canEdit) Icon(AppIcons.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp))
                    }
                    h.notes?.let { Text("Nota: $it", style = MaterialTheme.typography.bodySmall) }
                    if (h.revisions.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = Spacing.xs), color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Versiones anteriores (${h.revisions.size})", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        h.revisions.forEach { v ->
                            Text("Hasta ${format.format(Date(v.replacedAtMillis))}: " +
                                (if (v.status == SetStatus.SKIPPED) "omitida" else
                                    "${loadLabel(v.values.weightTenths, v.recordedUnit ?: h.prescribedUnit)} × ${v.values.reps}" + (if (h.warmup) "" else " @ RIR ${rirLabel(v.values.rir)}")) +
                                (v.notes?.let { " · nota: $it" } ?: ""), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (session.cardio.isNotEmpty()) {
                item { SectionHeader("Cardio") }
                items(session.cardio, key = { "c-${it.id}" }) { c ->
                    ContentCard {
                        Text(listOfNotNull(c.modality, c.durationSeconds?.let { "duración ${durationLabel(it * 1000L)}" },
                            c.distanceTenths?.let { "${it / 10.0} ${c.distanceUnit!!.name.lowercase()}" }, c.notes).joinToString(" · "))
                    }
                }
            }
        }
    }
}

/** Colour of an adherence status wherever it is shown as a pill. */
fun DayStatus.tone(): Tone = when (this) {
    DayStatus.COMPLETED -> Tone.Success; DayStatus.PARTIAL -> Tone.Warning; DayStatus.NOT_PERFORMED -> Tone.Error; DayStatus.NO_RECORD -> Tone.Info
}

private fun statusLine(s: SessionSummary): String =
    "${s.status.label}${if (s.inProgress) " (en curso)" else if (s.endedEarly) " (terminada antes)" else ""} · " +
        "${s.completedSets} completadas · ${s.omittedSets} omitidas · ${s.unreachedSets} sin registro" +
        (if (s.extraSets > 0) " · ${s.extraSets} extra" else "") + (if (s.warmupSets > 0) " · ${s.warmupSets} aproximaciones" else "") +
        (if (s.cardio.isNotEmpty()) " · cardio ${s.cardio.size}" else "") + if (s.demo) " · demo" else ""
