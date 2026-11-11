package com.example.workoutpoc.agent

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.workoutpoc.home.HomeUiState
import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.Spacing

/** One step of the weekly cycle, with what the app knows about it right now. */
private data class Step(val title: String, val status: String, val tone: Tone, val detail: String, val action: String, val onClick: () -> Unit)

/**
 * The Agente tab: in-app generation with the user's Claude key first, then the weekly cycle as four steps (send context, answer questions, import the proposal, review it),
 * each with its state derived from your data, then the tools behind them. The agent proposes, the rules validate,
 * you approve; the MCP server and manual JSON are equal paths through the same steps.
 */
@Composable
fun AgentScreen(home: HomeUiState, onPlans: () -> Unit, onQuestions: () -> Unit, onProposals: () -> Unit,
                onExchange: () -> Unit, onImport: () -> Unit, onGenerate: () -> Unit) {
    val summary = home.summary
    val week = summary?.week
    val toPlan = summary?.weekToPlan
    val steps = listOf(
        Step("Enviar contexto al agente",
            when { summary?.cycleComplete == true -> "Ciclo terminado"; toPlan != null -> "Semana $toPlan por planificar"; week != null -> "Semana ${week.number} en curso"; else -> "Sin programa" },
            if (toPlan != null) Tone.Warning else Tone.Info,
            "Prepara el contexto local y cópialo o expórtalo; con el servidor MCP, expórtalo como archivo de contexto.",
            "Preparar contexto", onExchange),
        Step("Responder sus preguntas",
            if (home.openQuestions > 0) "${home.openQuestions} sin responder" else "Ninguna abierta",
            if (home.openQuestions > 0) Tone.Warning else Tone.Success,
            "Lo que falta para planificar; tus respuestas viajan en el próximo contexto.", "Ver preguntas", onQuestions),
        Step("Importar su propuesta", "Archivo o envío firmado", Tone.Info,
            "Un archivo JSON o un envío firmado del servidor MCP. También puedes pegarlo en Intercambio JSON.", "Importar archivo", onImport),
        Step("Revisar y aprobar",
            if (home.pendingProposals > 0) "${home.pendingProposals} por revisar" else "Ninguna pendiente",
            if (home.pendingProposals > 0) Tone.Warning else Tone.Success,
            "Reglas, cambios explicados y tu decisión. Aprobar añade la semana a tu programa.", "Ver propuestas", onProposals),
    )
    AppScreen("Agente", "", {}, busy = !home.ready) {
        item { SupportingText("El agente propone, las reglas validan y tú apruebas. Nada cambia en tus programas sin tu aprobación.") }
        summary?.outline?.let { o -> item { SupportingText("Plan activo: ${o.name} · ${o.weeks.size} semanas", small = true) } }
        summary?.cycleNotice?.let { notice -> item { Banner(notice, tone = Tone.Warning) } }
        item {
            ContentCard {
                Text("Con Claude, desde la app", style = MaterialTheme.typography.titleMedium)
                SupportingText("Importa manualmente tu primer plan. Después, el agente genera las semanas y el siguiente ciclo con tu historial y las mediciones de Progreso → Cuerpo. Revisas el resultado antes de usarlo.", small = true)
                PrimaryAction(if (summary?.cycleComplete == true) "Generar nuevo plan de 12 semanas" else "Generar siguiente semana", onGenerate, icon = AppIcons.Bolt)
            }
        }
        item { SectionHeader("Ciclo semanal", supporting = "Con un agente externo: servidor MCP o JSON a mano.") }
        steps.forEachIndexed { i, step ->
            item {
                ContentCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text("${i + 1}. ${step.title}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        StatusPill(step.status, step.tone)
                    }
                    SupportingText(step.detail, small = true)
                    TonalAction(step.action, step.onClick)
                }
            }
        }
        item { SectionHeader("Herramientas") }
        item { NavRow("Planes de periodización", onPlans, icon = AppIcons.Calendar, supporting = "El ciclo de varias semanas que el agente sigue") }
        item { NavRow("Intercambio JSON", onExchange, icon = AppIcons.Swap,
            supporting = "Intercambios guardados, pegar JSON, exportar y emparejar el servidor MCP") }
        item { NavRow("Propuestas de semana", onProposals, icon = AppIcons.Bolt, supporting = "Historial de decisiones y reglas") }
        item { NavRow("Preguntas de planificación", onQuestions, icon = AppIcons.Question, supporting = "Abiertas y respondidas") }
    }
}
