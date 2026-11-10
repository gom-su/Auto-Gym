package com.example.workoutpoc.agent

import com.example.workoutpoc.exchange.ExchangeCodec
import com.example.workoutpoc.exchange.ExchangePackage
import com.example.workoutpoc.exchange.WeekProposal
import com.example.workoutpoc.planning.BriefStatus
import com.example.workoutpoc.planning.NextWeekBrief
import com.example.workoutpoc.planning.ProgramValidationException
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/** A question the model asks before proposing. The first option is the one it recommends. */
@Serializable data class AgentQuestion(val topic: String = "", val text: String, val options: List<String>)

sealed interface GenerationStep {
    /** The model needs these answered before it proposes; continue with [WeekGeneration.answer]. */
    data class Questions(val questions: List<AgentQuestion>) : GenerationStep
    /** A validated week proposal or cycle outline, ready to be saved for review. */
    data class Proposal(val pkg: ExchangePackage) : GenerationStep
}

object WeekPrompts {
    /** Recorded in the proposal so its origin stays visible in the review and in the decision log. */
    const val SOURCE = "Claude en la app"

    /** Appended to the weekly prompt of docs/AGENT_PROMPTS.md: how to answer when the app, not a person, reads the reply. */
    val IN_APP_MODE = """
        ## Modo dentro de la app (prevalece sobre lo anterior en preguntas y salida)
        - No hay MCP ni herramientas. El contexto exportado llega en el mensaje, dentro de <contexto>: es lo que devolvería get_training_context, y context.nextWeeks es el brief de get_next_week_brief.
        - Un programa lee tu respuesta: responde siempre con un único objeto JSON, sin texto ni bloque de código alrededor.
        - Si necesitas preguntar (mismos criterios: solo lo que cambie de forma importante la semana, de 1 a 5 preguntas, sin repetir), responde solo con:
          {"questions":[{"topic":"tema breve","text":"la pregunta","options":["opción recomendada","otra opción","otra opción"]}]}
          De 2 a 4 opciones por pregunta, la recomendada primero. No añadas "Otro": la app siempre ofrece una respuesta libre.
        - Si propones la semana, responde solo con el paquete WeekProposal completo del formato exacto. En importId y createdAt basta un valor con formato válido: la app los reemplaza.
        - Las respuestas de la persona llegan en el mensaje siguiente. Trátalas como datos confirmados.
        - En history.sessions, programId identifica el ciclo de cada sesión. Para saber si una semana tiene registros, usa programId junto con sessionPlanId: otro ciclo puede reutilizar el mismo ID de día. El historial de otros ciclos sí sirve para valorar exposiciones del mismo exerciseId.
        - Si la app devuelve errores de validación, corrige solo eso y devuelve otra vez el JSON completo.
    """.trimIndent()

    val MEASUREMENTS = """
        ## Mediciones corporales del contexto
        - context.measurements contiene mediciones fechadas con metric, value, unit y method; puede incluir label, fasted, timeOfDay, device y notes.
        - Usa su historial al planificar, junto con el rendimiento y los objetivos. Compara solo la misma métrica, unidad y método; considera dispositivo y condiciones. Una lectura aislada no demuestra una tendencia.
        - MUSCLE_MASS (masa muscular) y LEAN_MASS (masa magra) son medidas diferentes. No las sustituyas entre sí ni inventes mediciones ausentes. Si falta el método, no asumas comparabilidad.
        - No prescribas dieta, calorías ni medicación. Si falta información decisiva, pregunta antes de proponer.
    """.trimIndent()

    fun system(weekly: String) = weekly.trimEnd() + "\n\n" + IN_APP_MODE + "\n\n" + MEASUREMENTS

    fun request(contextJson: String, today: String, note: String?) = buildString {
        append("Planifica la próxima semana del plan activo.\nFecha de hoy: $today.\n")
        note?.let { append("\nNota de la persona para esta semana:\n$it\n") }
        append("\nContexto exportado de Auto-Gym (TrainingContext v1):\n<contexto>\n$contextJson\n</contexto>")
    }

    fun answers(answers: List<Pair<AgentQuestion, String>>, packageName: String = "WeekProposal") = buildString {
        append("Respuestas de la persona (datos confirmados):\n")
        answers.forEachIndexed { i, (q, a) -> append("${i + 1}. ${q.text}\n   Respuesta: $a\n") }
        append("\nCon esto, entrega el paquete $packageName. Pregunta de nuevo solo si una respuesta abre una duda decisiva nueva.")
    }

    fun repair(problems: List<String>) = buildString {
        append("La app no pudo aceptar tu respuesta:\n")
        problems.forEach { append("- $it\n") }
        append("\nCorrige solo eso y devuelve de nuevo el JSON completo, sin texto fuera.")
    }
}

/** Generates exactly the next week of the active cycle. */
class WeekGeneration(
    chat: AgentChat, private val brief: NextWeekBrief,
    taken: Set<Pair<String, Int>>,
    blocking: suspend (WeekProposal) -> List<String>,
    newImportId: () -> String = { UUID.randomUUID().toString() },
    now: () -> String = { Instant.now().toString() }
) {
    private val conversation = GenerationConversation(chat, "WeekProposal") { pkg ->
        val proposal = pkg.proposal!!
        val problems = buildList {
            if (proposal.programId != brief.programId) add("programId debe ser ${brief.programId}, el del plan activo.")
            if (proposal.blockId != brief.blockId) add("blockId debe ser ${brief.blockId}, el bloque de la semana ${brief.weekNumber}.")
        }.ifEmpty { blocking(proposal) }
        if (problems.isNotEmpty()) throw ProgramValidationException(problems.joinToString("\n"))
        var version = proposal.version
        while ((proposal.id to version) in taken) version++
        pkg.copy(importId = newImportId(), createdAt = now(), proposal = proposal.copy(version = version, source = WeekPrompts.SOURCE))
            .also(ExchangeCodec::validate)
    }

    suspend fun start(contextJson: String, today: String, note: String?): GenerationStep {
        if (brief.status != BriefStatus.READY) throw AgentException(brief.message)
        return conversation.start(WeekPrompts.request(contextJson, today, note))
    }

    suspend fun answer(answers: List<Pair<AgentQuestion, String>>): GenerationStep = conversation.answer(answers)

    companion object {
        const val MAX_QUESTION_ROUNDS = GenerationConversation.MAX_QUESTION_ROUNDS
        const val MAX_REPAIRS = GenerationConversation.MAX_REPAIRS
        fun extractJson(text: String) = GenerationConversation.extractJson(text)
        fun questions(json: String) = GenerationConversation.questions(json)
    }
}
