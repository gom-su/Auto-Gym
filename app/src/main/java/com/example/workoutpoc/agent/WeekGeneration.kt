package com.example.workoutpoc.agent

import com.example.workoutpoc.exchange.ExchangeCodec
import com.example.workoutpoc.exchange.ExchangePackage
import com.example.workoutpoc.exchange.ExchangeType
import com.example.workoutpoc.exchange.WeekProposal
import com.example.workoutpoc.planning.BriefStatus
import com.example.workoutpoc.planning.NextWeekBrief
import com.example.workoutpoc.planning.ProgramValidationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

/** A question the model asks before proposing. The first option is the one it recommends. */
@Serializable data class AgentQuestion(val topic: String = "", val text: String, val options: List<String>)
@Serializable private data class QuestionSet(val questions: List<AgentQuestion>)

sealed interface GenerationStep {
    /** The model needs these answered before it proposes; continue with [WeekGeneration.answer]. */
    data class Questions(val questions: List<AgentQuestion>) : GenerationStep
    /** A proposal that decodes and has no blocking issue, ready to be saved as pending. */
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
        - Si la app devuelve errores de validación, corrige solo eso y devuelve otra vez el JSON completo.
    """.trimIndent()

    fun system(weekly: String) = weekly.trimEnd() + "\n\n" + IN_APP_MODE

    fun request(contextJson: String, today: String, note: String?) = buildString {
        append("Planifica la próxima semana del plan activo.\nFecha de hoy: $today.\n")
        note?.let { append("\nNota de la persona para esta semana:\n$it\n") }
        append("\nContexto exportado de Auto-Gym (TrainingContext v1):\n<contexto>\n$contextJson\n</contexto>")
    }

    fun answers(answers: List<Pair<AgentQuestion, String>>) = buildString {
        append("Respuestas de la persona (datos confirmados):\n")
        answers.forEachIndexed { i, (q, a) -> append("${i + 1}. ${q.text}\n   Respuesta: $a\n") }
        append("\nCon esto, entrega el paquete WeekProposal. Pregunta de nuevo solo si una respuesta abre una duda decisiva nueva.")
    }

    fun repair(problems: List<String>) = buildString {
        append("La app no pudo aceptar tu respuesta:\n")
        problems.forEach { append("- $it\n") }
        append("\nCorrige solo eso y devuelve de nuevo el JSON completo, sin texto fuera.")
    }
}

/**
 * One request for the next week: sends the context, lets the model ask at most [MAX_QUESTION_ROUNDS] rounds of
 * questions, and accepts only a proposal for the week the app's brief names that decodes and has no blocking issue.
 * A reply the app cannot accept is sent back once with the reasons; a second one ends the generation with nothing saved.
 */
class WeekGeneration(
    private val chat: AgentChat, private val brief: NextWeekBrief,
    /** Proposal IDs with the versions already archived or decided, so a regenerated proposal takes the next version. */
    private val taken: Set<Pair<String, Int>>,
    /** Blocking issues the deterministic rules find in a proposal; empty when it could be approved. */
    private val blocking: suspend (WeekProposal) -> List<String>,
    private val newImportId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> String = { Instant.now().toString() }
) {
    private var questionRounds = 0
    private var repairs = 0
    private var toldToPropose = false

    suspend fun start(contextJson: String, today: String, note: String?): GenerationStep {
        if (brief.status != BriefStatus.READY) throw AgentException(brief.message)
        return read(chat.send(WeekPrompts.request(contextJson, today, note)))
    }

    suspend fun answer(answers: List<Pair<AgentQuestion, String>>): GenerationStep = read(chat.send(WeekPrompts.answers(answers)))

    private suspend fun read(reply: AgentReply): GenerationStep {
        if (reply.truncated) throw AgentException("La respuesta de Claude se cortó antes de terminar. No se guardó nada; reintenta.")
        val problems = mutableListOf<String>()
        val json = extractJson(reply.text)
        val root = json?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        when {
            json == null || root == null -> problems += "La respuesta no es un único objeto JSON."
            "questions" in root ->
                if (questionRounds >= MAX_QUESTION_ROUNDS) {
                    problems += "Ya hiciste tus preguntas. Resuelve lo que falte con la opción segura, explícalo en differences y entrega el paquete WeekProposal."
                    // Said once without spending the correction round: the proposal that follows may still need it.
                    if (!toldToPropose) { toldToPropose = true; return read(chat.send(WeekPrompts.repair(problems))) }
                }
                else try { return GenerationStep.Questions(questions(json)).also { questionRounds++ } }
                catch (e: AgentException) { problems += e.message.orEmpty() }
            else -> try {
                val pkg = ExchangeCodec.decode(json)
                val proposal = pkg.proposal?.takeIf { pkg.type == ExchangeType.WeekProposal }
                    ?: throw ProgramValidationException("Se esperaba un paquete de tipo WeekProposal o un objeto con questions.")
                if (proposal.programId != brief.programId) problems += "programId debe ser ${brief.programId}, el del plan activo."
                if (proposal.blockId != brief.blockId) problems += "blockId debe ser ${brief.blockId}, el bloque de la semana ${brief.weekNumber}."
                if (problems.isEmpty()) problems += blocking(proposal)
                if (problems.isEmpty()) return GenerationStep.Proposal(stamp(pkg, proposal))
            } catch (e: ProgramValidationException) { problems += e.message ?: "El paquete no es válido." }
        }
        if (repairs >= MAX_REPAIRS) throw AgentException("Claude devolvió una respuesta que la app no acepta: ${problems.first()} No se guardó nada.")
        repairs++
        return read(chat.send(WeekPrompts.repair(problems)))
    }

    /** Identity, time and origin come from the app, never from the model. */
    private fun stamp(pkg: ExchangePackage, proposal: WeekProposal): ExchangePackage {
        var version = proposal.version
        while ((proposal.id to version) in taken) version++
        return pkg.copy(importId = newImportId(), createdAt = now(), proposal = proposal.copy(version = version, source = WeekPrompts.SOURCE))
            .also(ExchangeCodec::validate)
    }

    companion object {
        const val MAX_QUESTION_ROUNDS = 2
        const val MAX_REPAIRS = 1
        private val lenient = Json { ignoreUnknownKeys = true }

        /** The outermost JSON object of [text], whether the model wrapped it in a code block or in a sentence. */
        fun extractJson(text: String): String? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            return if (start in 0 until end) text.substring(start, end + 1) else null
        }

        fun questions(json: String): List<AgentQuestion> {
            fun refuse(message: String): Nothing = throw AgentException(message)
            val set = try { lenient.decodeFromString<QuestionSet>(json) }
                catch (e: Exception) { refuse("questions debe ser una lista de objetos con topic, text y options.") }
            if (set.questions.size !in 1..5) refuse("Haz de 1 a 5 preguntas.")
            return set.questions.map { q ->
                val options = q.options.map { it.trim() }
                if (q.text.isBlank() || q.text.length > 1000) refuse("Cada pregunta necesita un text de 1 a 1000 caracteres.")
                if (options.size !in 2..4 || options.any { it.isEmpty() || it.length > 300 } || options.distinct().size != options.size)
                    refuse("Cada pregunta necesita de 2 a 4 options distintas, de 1 a 300 caracteres.")
                AgentQuestion(q.topic.trim().take(100), q.text.trim(), options)
            }
        }
    }
}
