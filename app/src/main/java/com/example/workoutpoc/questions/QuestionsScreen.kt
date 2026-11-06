package com.example.workoutpoc.questions

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
import com.example.workoutpoc.catalog.CatalogException
import com.example.workoutpoc.catalog.Equipment
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
        app.planningAnswers.answer(q.id, value)
        // An equipment answer also completes the catalog entry, so the gap closes at its source.
        if (q.use == QuestionUse.EQUIPMENT && value != null) {
            val equipment = PlanningQuestions.equipmentFromAnswer(value)
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
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                item { TextButton(onClick = onBack, enabled = !ui.busy) { Text("Volver a Programas") } }
                item { Text("Preguntas de planificación", style = MaterialTheme.typography.headlineMedium) }
                if (ui.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                ui.message?.let { item { Text(it) } }
                if (!ui.ready) item { Button(onClick = onRetry, enabled = !ui.busy) { Text("Reintentar") } }
                item { Text("Datos que mejoran la planificación del agente. Responde lo que sepas o márcalo como no disponible; nada se completa por suposición y nada bloquea la exportación del contexto.",
                    style = MaterialTheme.typography.bodySmall) }
                item { Text("Abiertas (${open.size})", style = MaterialTheme.typography.titleLarge) }
                if (open.isEmpty()) item { Text("No quedan preguntas abiertas.") }
                items(open, key = { "open-${it.id}" }) { q -> OpenQuestion(q, !ui.busy && ui.ready, onAnswer) }
                if (answered.isNotEmpty()) item { Text("Respondidas (${answered.size})", style = MaterialTheme.typography.titleLarge) }
                items(answered, key = { "done-${it.id}" }) { q ->
                    val a = ui.answers.find(q.id)!!
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(q.text, style = MaterialTheme.typography.titleSmall)
                            Text(if (a.unavailable) "No disponible" else "Respuesta: " +
                                (if (q.use == QuestionUse.EQUIPMENT) PlanningQuestions.equipmentFromAnswer(a.value!!)?.label() ?: a.value else a.value))
                            TextButton(onClick = { onReopen(q) }, enabled = !ui.busy) { Text("Cambiar respuesta") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenQuestion(q: PlanningQuestion, enabled: Boolean, onAnswer: (PlanningQuestion, String?) -> Unit) {
    var text by rememberSaveable(q.id) { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(q.text, style = MaterialTheme.typography.titleSmall)
            Text(q.why, style = MaterialTheme.typography.bodySmall)
            if (q.use == QuestionUse.EQUIPMENT) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Equipment.entries.forEach { e -> AssistChip({ onAnswer(q, e.name) }, { Text(e.label()) }, enabled = enabled) }
                }
            } else {
                OutlinedTextField(text, { if (it.length <= 1000) text = it }, label = { Text("Respuesta") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { onAnswer(q, text) }, enabled = enabled && text.isNotBlank()) { Text("Guardar respuesta") }
            }
            OutlinedButton(onClick = { onAnswer(q, null) }, enabled = enabled) { Text("No disponible") }
        }
    }
}
