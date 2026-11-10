package com.example.workoutpoc.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.planning.BriefStatus
import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.Spacing

/**
 * Generar siguiente semana: the week the app says comes next, an optional note, and one button that sends the
 * context to Claude. The model's questions are answered here; its proposal lands in Propuestas de semana as pending.
 */
@Composable
fun GenerateScreen(
    ui: GenerateUiState, onBack: () -> Unit, onRetry: () -> Unit, onSaveKey: (String) -> Unit, onClearKey: () -> Unit,
    onGenerate: (String) -> Unit, onAnswer: (List<String>) -> Unit, onCancel: () -> Unit, onProposals: () -> Unit, onPlans: () -> Unit
) {
    var note by rememberSaveable { mutableStateOf("") }
    val brief = ui.brief
    val newCycle = ui.newCycle
    AppScreen(if (newCycle) "Nuevo plan de 12 semanas" else "Generar siguiente semana", "Volver a Agente", onBack, backEnabled = !ui.generating, busy = ui.busy, message = ui.message) {
        if (!ui.ready && !ui.busy) item { RetryAction("Reintentar", onRetry, enabled = true) }
        item { SupportingText("La app envía tu historial de entrenamiento, planes y mediciones corporales a Claude con tu llave API. Revisas y apruebas el resultado antes de usarlo.") }
        ui.savedPlan?.let { name -> item {
            ContentCard {
                Text("Nuevo plan guardado", style = MaterialTheme.typography.titleMedium)
                SupportingText("$name · 12 semanas. El ciclo anterior y sus resultados se conservan.", small = true)
                PrimaryAction("Revisar y activar plan", onPlans, icon = AppIcons.Calendar)
            }
        } }
        ui.savedWeek?.let { saved ->
            item {
                ContentCard {
                    Text("Semana $saved propuesta", style = MaterialTheme.typography.titleMedium)
                    SupportingText("Revisa los cambios, las reglas y los motivos antes de decidir.", small = true)
                    PrimaryAction("Revisar y aprobar", onProposals, icon = AppIcons.Bolt)
                }
            }
        }
        if (!ui.key.present) keySection(ui, onSaveKey, onClearKey)

        item { SectionHeader(if (newCycle) "Siguiente ciclo" else "Semana a generar") }
        if (ui.ready && ui.outline == null) {
            item { EmptyState("No hay plan activo. Importa tu plan de varias semanas y actívalo; el botón genera cada semana a partir de él.") }
            item { TonalAction("Ver planes de periodización", onPlans, icon = AppIcons.Calendar) }
        } else if (brief != null) item {
            ContentCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(brief.weekNumber?.let { "Semana $it de ${brief.totalWeeks}" } ?: "Ciclo terminado", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    when (brief.status) {
                        BriefStatus.READY -> StatusPill(if (brief.target?.deload == true) "Descarga" else "Lista", Tone.Success)
                        BriefStatus.ALREADY_PLANNED -> StatusPill("Ya planificada", Tone.Info)
                        BriefStatus.CYCLE_COMPLETE -> StatusPill("Ciclo completo", Tone.Info)
                    }
                }
                ui.outline?.let { SupportingText(it.name, small = true) }
                Text(brief.message, style = MaterialTheme.typography.bodyMedium)
                if (brief.weekNumber == brief.totalWeeks || brief.referenceWeekNumber == brief.totalWeeks)
                    SupportingText(if (ui.cycleReady) "El ciclo terminó. Debes generar un nuevo plan de 12 semanas."
                        else "Esta es la última semana. Termina de registrar sus días y genera un nuevo plan de 12 semanas.")
                if (ui.pending > 0) SupportingText((if (ui.pending == 1) "Hay 1 propuesta pendiente" else "Hay ${ui.pending} propuestas pendientes") +
                    " de este programa. Decídela antes de generar otra.", small = true)
            }
        }

        val questions = ui.questions
        if (questions != null) {
            item { SectionHeader("Preguntas de Claude", supporting = "Tus respuestas se envían y la generación continúa.") }
            item(key = "questions") { QuestionsForm(questions, enabled = !ui.busy, onAnswer, onCancel) }
            if (ui.generating) item { SupportingText("Consultando a Claude con tus respuestas. Deja la app abierta.") }
        } else if (brief?.status == BriefStatus.READY || (newCycle && ui.cycleReady)) {
            item {
                OutlinedTextField(note, { if (it.length <= 2000) note = it }, Modifier.fillMaxWidth(), enabled = !ui.busy, minLines = 2,
                    label = { Text(if (newCycle) "Objetivos o cambios para el nuevo ciclo (opcional)" else "Nota para esta semana (opcional)") },
                    supportingText = { Text("Molestias, sueño o cambios de horario. Las mediciones de Progreso → Cuerpo ya se incluyen.") })
            }
            item {
                PrimaryAction(if (newCycle) "Generar nuevo plan de 12 semanas" else "Generar semana ${brief?.weekNumber}", { onGenerate(note) }, enabled = ui.canGenerate, icon = AppIcons.Agent, tall = true)
            }
            if (ui.generating) {
                item { SupportingText("Consultando a Claude. Suele tardar entre uno y tres minutos; deja la app abierta.") }
                item { SecondaryAction("Cancelar", onCancel) }
            } else item {
                SupportingText("Se envían a la API de Claude (Anthropic) tus programas, tus sesiones registradas, el plan activo y tus respuestas de planificación. " +
                    "También se envían tus mediciones corporales con fecha, unidad y método para planificar semanas y ciclos. Cada generación se cobra a tu cuenta de la API.", small = true)
            }
        }
        if (ui.key.present) keySection(ui, onSaveKey, onClearKey)
    }
}

/** First on the screen while there is no key (nothing works without it); last once it is saved. */
private fun androidx.compose.foundation.lazy.LazyListScope.keySection(ui: GenerateUiState, onSaveKey: (String) -> Unit, onClearKey: () -> Unit) {
    item { SectionHeader("Llave API de Claude") }
    item(key = "api-key") { ApiKeyCard(ui.key, enabled = !ui.busy, onSaveKey, onClearKey) }
}

/** One card per question: the model's options, the recommended one first, plus a free answer. */
@Composable
private fun QuestionsForm(questions: List<AgentQuestion>, enabled: Boolean, onAnswer: (List<String>) -> Unit, onCancel: () -> Unit) {
    // The choice per question: an option index, options.size for the free answer, or -1. Saved, so a rotation or a
    // scroll that takes the form off screen does not clear what was already answered.
    val key = questions.joinToString("|") { it.text }
    var picked by rememberSaveable(key) { mutableStateOf(List(questions.size) { -1 }) }
    var other by rememberSaveable(key) { mutableStateOf(List(questions.size) { "" }) }
    fun answerOf(i: Int): String? = picked[i].takeIf { it >= 0 }?.let { p -> questions[i].options.getOrNull(p) ?: other[i].trim().ifBlank { null } }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        questions.forEachIndexed { i, q ->
            ContentCard {
                if (q.topic.isNotBlank()) Eyebrow("PREGUNTA ${i + 1} · ${q.topic.uppercase()}") else Eyebrow("PREGUNTA ${i + 1}")
                Text(q.text, style = MaterialTheme.typography.titleSmall)
                (q.options + "Otra respuesta").forEachIndexed { o, option ->
                    val selected = picked[i] == o
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected, enabled = enabled, role = Role.RadioButton) { picked = picked.toMutableList().also { it[i] = o } },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        RadioButton(selected, onClick = null, enabled = enabled)
                        Text(if (o == 0) "$option (recomendada)" else option, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (picked[i] == q.options.size)
                    OutlinedTextField(other[i], { text -> if (text.length <= 1000) other = other.toMutableList().also { it[i] = text } }, Modifier.fillMaxWidth(), enabled = enabled,
                        label = { Text("Tu respuesta") })
            }
        }
        val answers = questions.indices.map(::answerOf)
        PrimaryAction("Enviar respuestas", { onAnswer(answers.filterNotNull()) }, enabled = enabled && answers.none { it == null }, icon = AppIcons.Agent)
        QuietAction("Cancelar generación", onCancel, enabled = enabled, fill = true)
    }
}
