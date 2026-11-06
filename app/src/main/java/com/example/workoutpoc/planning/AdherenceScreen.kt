package com.example.workoutpoc.planning

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
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.workoutApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AdherenceUiState(val ready: Boolean = false, val busy: Boolean = false, val message: String? = null,
                            val weeks: List<WeekAdherence> = emptyList(), val progress: GoalProgress? = null, val goals: Goals = Goals())

class AdherenceViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.workoutApp
    private val mutableUi = MutableStateFlow(AdherenceUiState())
    val ui = mutableUi.asStateFlow()
    init { refresh() }

    fun refresh() = op { load() }
    /** Writes first, then reloads in the same operation, so the screen never shows a state from before the write. */
    fun setNotPerformed(day: DayRef, value: Boolean) = op { app.weekAnnotations.setNotPerformed(day, value); load() }
    fun saveGoals(goals: Goals) = op {
        app.weekAnnotations.setGoals(goals); load()
        mutableUi.value = mutableUi.value.copy(message = "Objetivos guardados.")
    }

    private suspend fun load() {
        app.programs.restore(); app.repository.restore(); app.weekAnnotations.restore()
        val sessions: List<WorkoutState> = app.repository.archived.value.sessions + app.repository.state.value
        val a = app.weekAnnotations.annotations.value
        mutableUi.value = mutableUi.value.copy(ready = true, weeks = adherence(app.programs.library.value.programs, sessions, a),
            progress = goalProgress(sessions, a.goals, System.currentTimeMillis()), goals = a.goals)
    }

    private fun op(block: suspend () -> Unit) {
        if (ui.value.busy) return
        mutableUi.value = mutableUi.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { mutableUi.value = mutableUi.value.copy(message = e.message) }
            catch (_: Exception) { mutableUi.value = mutableUi.value.copy(message = "No se pudo leer o guardar. Tus datos se conservan. Reintenta.") }
            finally { mutableUi.value = mutableUi.value.copy(busy = false) }
        }
    }
}

@Composable
fun AdherenceScreen(ui: AdherenceUiState, onBack: () -> Unit, onRetry: () -> Unit,
                    onNotPerformed: (DayRef, Boolean) -> Unit, onSaveGoals: (Goals) -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                item { TextButton(onClick = onBack, enabled = !ui.busy) { Text("Volver a Programas") } }
                item { Text("Adherencia y objetivos", style = MaterialTheme.typography.headlineMedium) }
                if (ui.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                ui.message?.let { item { Text(it) } }
                if (!ui.ready) item { Button(onClick = onRetry, enabled = !ui.busy) { Text("Reintentar") } }
                ui.progress?.let { p -> item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Esta semana", style = MaterialTheme.typography.titleMedium)
                            Text("Sesiones con al menos una serie completada: ${p.sessions}" + (p.sessionsGoal?.let { " de $it" } ?: " · sin objetivo"))
                            Text("Cardio registrado: ${p.cardioMinutes} min" + (p.cardioGoal?.let { " de $it" } ?: " · sin objetivo"))
                        }
                    }
                } }
                item { GoalEditor(ui.goals, !ui.busy && ui.ready, onSaveGoals) }
                item { Text("Completado: todas las series prescritas. Parcial: alguna completada o en curso. No realizado: lo declaraste o ninguna serie se completó. Sin registro: no hay datos; no se asume que no entrenaste.",
                    style = MaterialTheme.typography.bodySmall) }
                if (ui.weeks.isEmpty()) item { Text("No hay programas guardados.") }
                ui.weeks.forEach { w ->
                    item {
                        Text("${w.program.name} · ${w.week.name}", style = MaterialTheme.typography.titleMedium)
                        Text(DayStatus.entries.joinToString(" · ") { "${it.label}: ${w.count(it)}" }, style = MaterialTheme.typography.bodySmall)
                    }
                    items(w.days, key = { "${w.program.id}-${w.week.id}-${it.day.id}" }) { d ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${d.day.name} · ${d.status.label}" + if (d.inProgress) " (en curso)" else "", style = MaterialTheme.typography.titleSmall)
                                Text("Series prescritas: ${d.completedSets}/${d.prescribedSets} completadas · ${d.omittedSets} omitidas · ${d.unrecordedSets} sin registro" +
                                    (if (d.extraSets > 0) " · ${d.extraSets} extra" else "") + (if (d.cardioMinutes > 0) " · cardio ${d.cardioMinutes} min" else ""),
                                    style = MaterialTheme.typography.bodySmall)
                                val ref = DayRef(w.program.id, w.week.id, d.day.id)
                                // Only a day with no session can be declared as not performed; recorded facts take precedence.
                                if (d.sessions == 0) TextButton(onClick = { onNotPerformed(ref, d.status != DayStatus.NOT_PERFORMED) }, enabled = !ui.busy) {
                                    Text(if (d.status == DayStatus.NOT_PERFORMED) "Quitar «no realizado» de ${w.week.name} · ${d.day.name}" else "Marcar ${w.week.name} · ${d.day.name} como no realizado")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalEditor(goals: Goals, enabled: Boolean, onSave: (Goals) -> Unit) {
    var sessions by rememberSaveable(goals) { mutableStateOf(goals.sessionsPerWeek?.toString().orEmpty()) }
    var cardio by rememberSaveable(goals) { mutableStateOf(goals.cardioMinutesPerWeek?.toString().orEmpty()) }
    val s = sessions.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    val c = cardio.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    val valid = (sessions.isBlank() || s in 1..14) && (cardio.isBlank() || c in 1..3000)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Objetivos semanales (vacío = sin objetivo)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(sessions, { sessions = it.take(2) }, label = { Text("Sesiones") }, singleLine = true, modifier = Modifier.weight(1f),
                isError = sessions.isNotBlank() && s !in 1..14)
            OutlinedTextField(cardio, { cardio = it.take(4) }, label = { Text("Cardio (min)") }, singleLine = true, modifier = Modifier.weight(1f),
                isError = cardio.isNotBlank() && c !in 1..3000)
        }
        Button(onClick = { onSave(Goals(s, c)) }, enabled = enabled && valid && Goals(s, c) != goals) { Text("Guardar objetivos") }
    }
}
