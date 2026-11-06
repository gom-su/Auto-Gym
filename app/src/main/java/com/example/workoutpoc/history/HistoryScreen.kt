package com.example.workoutpoc.history

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.workoutpoc.domain.SetStatus
import com.example.workoutpoc.domain.loadLabel
import com.example.workoutpoc.domain.rirLabel
import com.example.workoutpoc.presentation.durationLabel
import com.example.workoutpoc.workoutApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryUiState(val ready: Boolean = false, val busy: Boolean = false, val message: String? = null,
                          val sessions: List<SessionSummary> = emptyList())

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.workoutApp
    private val mutableUi = MutableStateFlow(HistoryUiState())
    val ui = mutableUi.asStateFlow()
    init { refresh() }
    fun refresh() {
        if (ui.value.busy) return
        mutableUi.value = mutableUi.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                app.repository.restore()
                mutableUi.value = mutableUi.value.copy(ready = true,
                    sessions = history(app.repository.archived.value.sessions, app.repository.state.value, System.currentTimeMillis()))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableUi.value = mutableUi.value.copy(message = "No se pudo leer el historial. Tus datos se conservan. Reintenta.") }
            finally { mutableUi.value = mutableUi.value.copy(busy = false) }
        }
    }
}

/** Read-only list of recorded sessions, newest first, with a detail view per session. */
@Composable
fun HistoryScreen(ui: HistoryUiState, onBack: () -> Unit, onRetry: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val format = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val session = ui.sessions.find { it.sessionId == selected }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                item { TextButton(onClick = { if (session != null) selected = null else onBack() }) {
                    Text(if (session != null) "Ver todas las sesiones" else "Volver a Programas") } }
                item { Text("Historial de sesiones", style = MaterialTheme.typography.headlineMedium) }
                if (ui.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                ui.message?.let { item { Text(it) } }
                if (!ui.ready) item { Button(onClick = onRetry, enabled = !ui.busy) { Text("Reintentar") } }
                if (session == null) {
                    if (ui.ready && ui.sessions.isEmpty()) item { Text("Aún no hay sesiones registradas.") }
                    items(ui.sessions, key = { it.sessionId }) { s ->
                        OutlinedButton(onClick = { selected = s.sessionId }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("${s.startedAtMillis?.let { format.format(Date(it)) } ?: "sin fecha"} · ${s.title}")
                                Text(statusLine(s), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else {
                    item { Text(session.title, style = MaterialTheme.typography.titleLarge) }
                    item {
                        Text(listOfNotNull(session.startedAtMillis?.let { "Inicio ${format.format(Date(it))}" },
                            session.endedAtMillis?.let { "fin ${format.format(Date(it))}" },
                            session.activeMillis?.let { "tiempo activo ${durationLabel(it)}" }).joinToString(" · "))
                    }
                    item { Text(statusLine(session), style = MaterialTheme.typography.titleSmall) }
                    item { Text("Series", style = MaterialTheme.typography.titleMedium) }
                    if (session.sets.isEmpty()) item { Text("Sin series registradas.") }
                    items(session.sets.size) { i ->
                        val h = session.sets[i]
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${h.exercise} · ${if (h.warmup) "aproximación" else if (h.extra) "serie extra" else "serie"} ${h.number}",
                                    style = MaterialTheme.typography.titleSmall)
                                Text(if (h.status == SetStatus.SKIPPED) "Omitida" else
                                    "${loadLabel(h.values.weightTenths, h.unit)} × ${h.values.reps} @ RIR ${rirLabel(h.values.rir)}" + if (h.edited) " · editada" else "")
                                h.notes?.let { Text("Nota: $it", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    if (session.cardio.isNotEmpty()) {
                        item { Text("Cardio", style = MaterialTheme.typography.titleMedium) }
                        items(session.cardio, key = { "c-${it.id}" }) { c ->
                            Text(listOfNotNull(c.modality, c.durationSeconds?.let { "duración ${durationLabel(it * 1000L)}" },
                                c.distanceTenths?.let { "${it / 10.0} ${c.distanceUnit!!.name.lowercase()}" }, c.notes).joinToString(" · "))
                        }
                    }
                }
            }
        }
    }
}

private fun statusLine(s: SessionSummary): String =
    "${s.status.label}${if (s.inProgress) " (en curso)" else if (s.endedEarly) " (terminada antes)" else ""} · " +
        "${s.completedSets} completadas · ${s.omittedSets} omitidas · ${s.unreachedSets} sin registro" +
        (if (s.extraSets > 0) " · ${s.extraSets} extra" else "") + (if (s.warmupSets > 0) " · ${s.warmupSets} aproximaciones" else "") +
        (if (s.cardio.isNotEmpty()) " · cardio ${s.cardio.size}" else "") + if (s.demo) " · demo" else ""
