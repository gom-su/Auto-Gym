package com.example.workoutpoc.planning

import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.AppTheme
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular
import androidx.compose.ui.Alignment
import com.example.workoutpoc.history.tone
import android.app.Application
import androidx.compose.foundation.layout.*
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
    AppScreen("Adherencia y objetivos", "Volver a Programas", onBack, backEnabled = !ui.busy, busy = ui.busy, message = ui.message) {
        if (!ui.ready && !ui.busy) item { RetryAction("Reintentar", onRetry, enabled = !ui.busy) }
        ui.progress?.let { p -> item {
            ContentCard {
                Text("Esta semana", style = MaterialTheme.typography.titleMedium)
                GoalProgressLine("Sesiones con al menos una serie completada: ${p.sessions}" + (p.sessionsGoal?.let { " de $it" } ?: " · sin objetivo"),
                    p.sessions, p.sessionsGoal)
                GoalProgressLine("Cardio registrado: ${p.cardioMinutes} min" + (p.cardioGoal?.let { " de $it" } ?: " · sin objetivo"),
                    p.cardioMinutes, p.cardioGoal)
            }
        } }
        item { ContentCard { GoalEditor(ui.goals, !ui.busy && ui.ready, onSaveGoals) } }
        item { SupportingText("Completado: todas las series prescritas. Parcial: alguna completada o en curso. No realizado: lo declaraste o ninguna serie se completó. Sin registro: no hay datos; no se asume que no entrenaste.",
            small = true) }
        if (ui.weeks.isEmpty()) item { EmptyState("No hay programas guardados.", icon = AppIcons.Library) }
        ui.weeks.forEach { w ->
            item {
                Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${w.program.name} · ${w.week.name}", style = MaterialTheme.typography.titleMedium)
                    Text(DayStatus.entries.joinToString(" · ") { "${it.label}: ${w.count(it)}" }, style = MaterialTheme.typography.bodySmall.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    w.averageSessionMillis?.let { average ->
                        SupportingText("Duración real promedio: ${com.example.workoutpoc.presentation.measuredTimeLabel(average)} · ${w.measuredSessions} sesiones medidas")
                    }
                }
            }
            items(w.days, key = { "${w.program.id}-${w.week.id}-${it.day.id}" }) { d ->
                ContentCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(d.day.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        StatusPill(d.status.label + if (d.inProgress) " (en curso)" else "", d.status.tone())
                    }
                    Text("Series prescritas: ${d.completedSets}/${d.prescribedSets} completadas · ${d.omittedSets} omitidas · ${d.unrecordedSets} sin registro" +
                        (if (d.extraSets > 0) " · ${d.extraSets} extra" else "") + (if (d.cardioMinutes > 0) " · cardio ${d.cardioMinutes} min" else ""),
                        style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val ref = DayRef(w.program.id, w.week.id, d.day.id)
                    // Only a day with no session can be declared as not performed; recorded facts take precedence.
                    if (d.sessions == 0) QuietAction(
                        if (d.status == DayStatus.NOT_PERFORMED) "Quitar «no realizado» de ${w.week.name} · ${d.day.name}" else "Marcar ${w.week.name} · ${d.day.name} como no realizado",
                        { onNotPerformed(ref, d.status != DayStatus.NOT_PERFORMED) }, enabled = !ui.busy)
                }
            }
        }
    }

}

/** One weekly goal: its sentence and, when a goal is set, a progress bar towards it. */
@Composable
private fun GoalProgressLine(text: String, value: Int, goal: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(text, style = MaterialTheme.typography.bodyMedium.tabular())
        if (goal != null && goal > 0) LinearProgressIndicator(progress = { (value.toFloat() / goal).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp), color = if (value >= goal) AppTheme.extended.success else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {})
    }
}

@Composable
private fun GoalEditor(goals: Goals, enabled: Boolean, onSave: (Goals) -> Unit) {
    var sessions by rememberSaveable(goals) { mutableStateOf(goals.sessionsPerWeek?.toString().orEmpty()) }
    var cardio by rememberSaveable(goals) { mutableStateOf(goals.cardioMinutesPerWeek?.toString().orEmpty()) }
    val s = sessions.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    val c = cardio.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    val valid = (sessions.isBlank() || s in 1..14) && (cardio.isBlank() || c in 1..3000)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("Objetivos semanales (vacío = sin objetivo)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(sessions, { sessions = it.take(2) }, label = { Text("Sesiones") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), supportingText = { Text("1–14") },
                isError = sessions.isNotBlank() && s !in 1..14)
            OutlinedTextField(cardio, { cardio = it.take(4) }, label = { Text("Cardio (min)") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), supportingText = { Text("1–3000") },
                isError = cardio.isNotBlank() && c !in 1..3000)
        }
        PrimaryAction("Guardar objetivos", { onSave(Goals(s, c)) }, enabled = enabled && valid && Goals(s, c) != goals, icon = AppIcons.Check)
    }
}
