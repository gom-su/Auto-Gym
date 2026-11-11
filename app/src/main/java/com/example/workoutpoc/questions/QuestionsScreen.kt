package com.example.workoutpoc.questions

import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.AppTheme
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular
import androidx.compose.ui.Alignment
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
import com.example.workoutpoc.catalog.CatalogException
import com.example.workoutpoc.catalog.label
import com.example.workoutpoc.workoutApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class QuestionsUiState(val ready: Boolean = false, val busy: Boolean = false, val message: String? = null,
                            val questions: List<PlanningQuestion> = emptyList(), val answers: PlanningAnswers = PlanningAnswers())

class QuestionsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.workoutApp
    private val mutableUi = MutableStateFlow(QuestionsUiState())
    val ui = mutableUi.asStateFlow()
    init { refresh() }

    fun refresh() = op { load() }
    fun answer(q: PlanningQuestion, value: String?) = op {
        val equipment = if (q.use == QuestionUse.EQUIPMENT && value != null) PlanningQuestions.equipmentFromAnswer(value) else null
        app.planningAnswers.answer(q.id, equipment?.name ?: value)
        // An equipment answer also completes the catalog entry, so the gap closes at its source.
        if (q.use == QuestionUse.EQUIPMENT && value != null) {
            val entry = app.catalog.catalog.value.exercises.find { it.id == q.exerciseId }
            if (equipment != null && entry != null) app.catalog.update(entry.copy(equipment = equipment))
        }
        load()
        mutableUi.value = mutableUi.value.copy(message = if (value == null) "Marcada como no disponible." else "Respuesta guardada.")
    }
    fun reopen(q: PlanningQuestion) = op { app.planningAnswers.clear(q.id); load() }

    private suspend fun load() {
        app.planningAnswers.restore(); app.catalog.restore()
        mutableUi.value = mutableUi.value.copy(ready = true, questions = PlanningQuestions.questions(app.catalog.catalog.value),
            answers = app.planningAnswers.answers.value)
    }
    private fun op(block: suspend () -> Unit) {
        if (ui.value.busy) return
        mutableUi.value = mutableUi.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: CatalogException) { mutableUi.value = mutableUi.value.copy(message = e.message) }
            catch (e: IllegalArgumentException) { mutableUi.value = mutableUi.value.copy(message = e.message) }
            catch (_: Exception) { mutableUi.value = mutableUi.value.copy(message = "No se pudo guardar. Tus datos se conservan. Reintenta.") }
            finally { mutableUi.value = mutableUi.value.copy(busy = false) }
        }
    }
}

@Composable
fun QuestionsScreen(ui: QuestionsUiState, onBack: () -> Unit, onRetry: () -> Unit,
                    onAnswer: (PlanningQuestion, String?) -> Unit, onReopen: (PlanningQuestion) -> Unit) {
    val open = PlanningQuestions.open(ui.questions, ui.answers)
    val answered = ui.questions - open.toSet()
    AppScreen("Preguntas de planificación", "Volver a Programas", onBack, backEnabled = !ui.busy, busy = ui.busy, message = ui.message) {
        if (!ui.ready && !ui.busy) item { RetryAction("Reintentar", onRetry, enabled = !ui.busy) }
        item { SupportingText("Datos que mejoran la planificación del agente. Responde lo que sepas o márcalo como no disponible; nada se completa por suposición y nada bloquea la exportación del contexto.") }
        if (ui.questions.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text("${answered.size} de ${ui.questions.size} respondidas", style = MaterialTheme.typography.labelLarge.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { answered.size.toFloat() / ui.questions.size }, modifier = Modifier.fillMaxWidth().height(6.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {})
            }
        }
        item { SectionHeader("Abiertas (${open.size})") }
        if (open.isEmpty()) item { EmptyState("No quedan preguntas abiertas.", icon = AppIcons.CheckCircle) }
        items(open, key = { "open-${it.id}" }) { q -> OpenQuestion(q, !ui.busy && ui.ready, onAnswer) }
        if (answered.isNotEmpty()) item { SectionHeader("Respondidas (${answered.size})") }
        items(answered, key = { "done-${it.id}" }) { q ->
            val a = ui.answers.find(q.id)!!
            ContentCard {
                Text(q.text, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Icon(if (a.unavailable) AppIcons.Remove else AppIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = if (a.unavailable) MaterialTheme.colorScheme.onSurfaceVariant else AppTheme.extended.success)
                    Text(if (a.unavailable) "No disponible" else "Respuesta: " +
                        (if (q.use == QuestionUse.EQUIPMENT) PlanningQuestions.equipmentFromAnswer(a.value!!)?.label() ?: a.value else a.value),
                        modifier = Modifier.weight(1f))
                }
                QuietAction("Cambiar respuesta", { onReopen(q) }, enabled = !ui.busy, icon = AppIcons.Edit)
            }
        }
    }

}

@Composable
private fun OpenQuestion(q: PlanningQuestion, enabled: Boolean, onAnswer: (PlanningQuestion, String?) -> Unit) {
    var text by rememberSaveable(q.id) { mutableStateOf("") }
    var picked by rememberSaveable(q.id, q.options) { mutableStateOf(-1) }
    val answer = questionAnswer(q.options, picked, text)
    ContentCard {
        Text(q.text, style = MaterialTheme.typography.titleSmall)
        Text(q.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        QuestionAnswerChoices(q.options, picked, { picked = it }, text, { text = it }, enabled)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onAnswer(q, null) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("No disponible") }
            Button(onClick = { answer?.let { onAnswer(q, it) } }, enabled = enabled && answer != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Guardar respuesta") }
        }
    }
}
