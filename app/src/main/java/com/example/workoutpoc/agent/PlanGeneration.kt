package com.example.workoutpoc.agent

import com.example.workoutpoc.exchange.ExchangeCodec
import com.example.workoutpoc.exchange.TrainingContext
import com.example.workoutpoc.planning.BriefStatus
import com.example.workoutpoc.planning.PlanOutline
import com.example.workoutpoc.planning.ProgramValidationException
import java.time.Instant
import java.util.UUID

object PlanPrompts {
    fun system(prompt: String) = prompt.trimEnd() + "\n\n" + """
        ## Modo dentro de la app
        No hay MCP ni herramientas. El contexto llega dentro de <contexto>.
        Responde con un único objeto JSON, sin texto ni bloque de código alrededor.
        Si necesitas preguntar, no propongas todavía. Devuelve:
        {"questions":[{"topic":"tema breve","text":"la pregunta","options":["opción recomendada","otra opción"]}]}
        De 1 a 5 preguntas, de 2 a 4 opciones distintas por pregunta, recomendada primero. La app ofrece respuesta libre.
        Las respuestas llegan en el siguiente mensaje: son datos confirmados. No repitas preguntas respondidas.
        Al terminar, devuelve el paquete PlanOutline completo. La app asigna importId y createdAt.
        Si la app devuelve errores de validación, corrige eso y devuelve el JSON completo.
    """.trimIndent() + "\n\n" + WeekPrompts.MEASUREMENTS

    fun request(contextJson: String, today: String, note: String?) = buildString {
        append("Evalúa el ciclo anterior y propón un nuevo plan de 12 semanas.\nFecha de hoy: $today.\n")
        note?.let { append("\nObjetivos o cambios indicados por la persona:\n$it\n") }
        append("\n<contexto>\n$contextJson\n</contexto>")
    }
}

/** A new cycle is a separate program: past plans, loads and results are never overwritten. */
class PlanGeneration(
    chat: AgentChat, private val context: TrainingContext,
    archived: List<PlanOutline> = emptyList(),
    newImportId: () -> String = { UUID.randomUUID().toString() },
    now: () -> String = { Instant.now().toString() },
    /** The app also knows explicit not-performed marks, which are not fabricated as training sessions. */
    private val cycleComplete: Boolean = context.nextWeeks.any { it.status == BriefStatus.CYCLE_COMPLETE }
) {
    private val known = context.outlines + context.previousOutlines + archived
    private val conversation = GenerationConversation(chat, "PlanOutline") { pkg ->
        val outline = pkg.outline!!
        val problems = buildList {
            if (outline.weeks.size != 12) add("El nuevo plan debe tener exactamente 12 semanas, numeradas del 1 al 12.")
            if (outline.version != 1) add("Un ciclo nuevo empieza con version 1.")
            if (known.any { it.id == outline.id }) add("Usa un id de plan nuevo para conservar el ciclo anterior.")
            if (context.programs.any { it.id == outline.programId } || known.any { it.programId == outline.programId })
                add("Usa un programId nuevo: el ciclo anterior y sus resultados deben conservarse.")
        }
        if (problems.isNotEmpty()) throw ProgramValidationException(problems.joinToString("\n"))
        pkg.copy(importId = newImportId(), createdAt = now()).also(ExchangeCodec::validate)
    }

    suspend fun start(contextJson: String, today: String, note: String?): GenerationStep {
        if (!cycleComplete)
            throw AgentException("Termina el ciclo activo antes de generar un nuevo plan de 12 semanas. El primer plan se importa manualmente.")
        return conversation.start(PlanPrompts.request(contextJson, today, note))
    }

    suspend fun answer(answers: List<Pair<AgentQuestion, String>>): GenerationStep = conversation.answer(answers)
}
