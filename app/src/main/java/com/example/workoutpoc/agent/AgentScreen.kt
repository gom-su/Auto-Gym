package com.example.workoutpoc.agent

import androidx.compose.runtime.Composable
import com.example.workoutpoc.ui.*

/** The Agente tab: everything the external agent reads, proposes or asks, and what you decide about it. */
@Composable
fun AgentScreen(onPlans: () -> Unit, onQuestions: () -> Unit, onProposals: () -> Unit, onExchange: () -> Unit) {
    AppScreen("Agente", "", {}) {
        item { SupportingText("El agente propone, las reglas validan y tú apruebas. Nada cambia en tus programas sin tu aprobación.") }
        item { NavRow("Intercambio JSON", onExchange, icon = AppIcons.Swap,
            supporting = "Prepara el contexto para el agente, importa sus envíos y empareja el servidor MCP") }
        item { NavRow("Preguntas de planificación", onQuestions, icon = AppIcons.Question,
            supporting = "Lo que el agente necesita saber antes de planificar") }
        item { NavRow("Propuestas de semana", onProposals, icon = AppIcons.Bolt,
            supporting = "Revisa, aprueba o rechaza; reglas e historial de decisiones") }
        item { NavRow("Planes de periodización", onPlans, icon = AppIcons.Calendar,
            supporting = "El ciclo de varias semanas que el agente sigue") }
    }
}
