package com.example.workoutpoc.agent

import com.example.workoutpoc.exchange.ExchangeCodec
import com.example.workoutpoc.exchange.ExchangePackage
import com.example.workoutpoc.planning.ProgramValidationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable private data class QuestionSet(val questions: List<AgentQuestion>)

/** Shared conversational protocol for a week or a new cycle; only validated packages reach storage. */
internal class GenerationConversation(
    private val chat: AgentChat,
    private val packageName: String,
    private val accept: suspend (ExchangePackage) -> ExchangePackage
) {
    private var questionRounds = 0
    private var repairs = 0
    private var toldToPropose = false

    suspend fun start(request: String): GenerationStep = read(chat.send(request))

    suspend fun answer(answers: List<Pair<AgentQuestion, String>>): GenerationStep = read(chat.send(WeekPrompts.answers(answers, packageName)))

    private suspend fun read(reply: AgentReply): GenerationStep {
        if (reply.truncated) throw AgentException("La respuesta de Claude se cortó antes de terminar. No se guardó nada; reintenta.")
        val problems = mutableListOf<String>()
        val json = extractJson(reply.text)
        val root = json?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        when {
            json == null || root == null -> problems += "La respuesta no es un único objeto JSON."
            "questions" in root ->
                if (questionRounds >= MAX_QUESTION_ROUNDS) {
                    val explanation = if (packageName == "PlanOutline") "notes" else "differences"
                    problems += "Ya hiciste tus preguntas. Resuelve lo que falte con la opción segura, explícalo en $explanation y entrega el paquete $packageName."
                    // Said once without spending the correction round: the proposal that follows may still need it.
                    if (!toldToPropose) { toldToPropose = true; return read(chat.send(WeekPrompts.repair(problems))) }
                }
                else try { return GenerationStep.Questions(questions(json)).also { questionRounds++ } }
                catch (e: AgentException) { problems += e.message.orEmpty() }
            else -> try {
                val pkg = ExchangeCodec.decode(json)
                if (pkg.type.name != packageName) throw ProgramValidationException("Se esperaba un paquete de tipo $packageName o un objeto con questions.")
                return GenerationStep.Proposal(accept(pkg))
            } catch (e: ProgramValidationException) { problems += e.message ?: "El paquete no es válido." }
        }
        if (repairs >= MAX_REPAIRS) throw AgentException("Claude devolvió una respuesta que la app no acepta: ${problems.first()} No se guardó nada.")
        repairs++
        return read(chat.send(WeekPrompts.repair(problems)))
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
