package com.example.workoutpoc.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.Closeable

/** The model's answer to one turn. [truncated] means it hit the output limit and the text is incomplete. */
data class AgentReply(val text: String, val truncated: Boolean = false, val inputTokens: Long = 0, val outputTokens: Long = 0)

/**
 * One conversation with the model. It only grows: each [send] adds the user's turn and the model's whole answer to
 * what was sent before, and a failed call adds nothing, so it can be sent again.
 */
interface AgentChat : Closeable {
    suspend fun send(text: String): AgentReply
    override fun close() {}
}

fun interface AgentChatFactory { fun open(apiKey: String, system: String): AgentChat }

object ClaudeModel {
    const val ID = "claude-opus-5-5"
    /** Room for the model's reasoning plus a whole week of JSON; responses are streamed, so the size costs no timeout. */
    const val MAX_TOKENS = 64_000L
    /** If a safety classifier declines the request, the API retries it on another model instead of failing. */
    const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
}

/** Conversations through the official SDK. [configure] points the client at a local stand-in in tests only. */
class ClaudeChatFactory(private val configure: (AnthropicOkHttpClient.Builder) -> Unit = {}) : AgentChatFactory {
    override fun open(apiKey: String, system: String): AgentChat =
        ClaudeChat(AnthropicOkHttpClient.builder().apiKey(apiKey).also(configure).build(), system)
}

private class ClaudeChat(private val client: AnthropicClient, private val system: String) : AgentChat {
    /** Completed turns. The model's messages are replayed exactly as received, reasoning blocks included. */
    private val turns = mutableListOf<Pair<String, BetaMessage>>()

    private fun request(next: String): MessageCreateParams = MessageCreateParams.builder()
        .model(ClaudeModel.ID).maxTokens(ClaudeModel.MAX_TOKENS).system(system)
        .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.HIGH).build())
        .addBeta(ClaudeModel.FALLBACK_BETA).fallbacksDefault()
        .apply { turns.forEach { (user, reply) -> addUserMessage(user); addMessage(reply) } }
        .addUserMessage(next).build()

    override suspend fun send(text: String): AgentReply {
        val message = try {
            runInterruptible(Dispatchers.IO) {
                val whole = BetaMessageAccumulator.create()
                client.beta().messages().createStreaming(request(text)).use { events -> events.stream().forEach { whole.accumulate(it) } }
                whole.message()
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // A cancelled call surfaces as an I/O failure of the interrupted stream; cancellation wins.
            currentCoroutineContext().ensureActive()
            throw AgentException(explain(e))
        }
        val stop = message.stopReason().orElse(null)
        if (stop == BetaStopReason.REFUSAL) throw AgentException("Claude declinó la solicitud" +
            (message.stopDetails().flatMap { it.explanation() }.map { ": $it" }.orElse(".")) + " No se guardó nada.")
        if (stop == BetaStopReason.MODEL_CONTEXT_WINDOW_EXCEEDED)
            throw AgentException("El contexto supera lo que el modelo puede leer. No se guardó nada.")
        val reply = message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
        turns += text to message
        return AgentReply(reply, stop == BetaStopReason.MAX_TOKENS, message.usage().inputTokens(), message.usage().outputTokens())
    }

    override fun close() { client.close() }

    private fun explain(e: Exception): String = when (e) {
        is UnauthorizedException -> "La API rechazó la llave: no es válida o fue revocada. Cámbiala y reintenta."
        is PermissionDeniedException -> "La llave no tiene permiso para usar el modelo ${ClaudeModel.ID}."
        is RateLimitException -> "Se alcanzó el límite de uso de la API. Espera un momento y reintenta."
        is AnthropicServiceException -> when {
            e.statusCode() == 402 -> "La cuenta de la API no tiene crédito disponible."
            e.statusCode() == 413 -> "El contexto es demasiado grande para enviarlo."
            e.statusCode() >= 500 -> "El servicio de Claude no está disponible ahora. Reintenta en unos minutos."
            else -> "La API rechazó la solicitud (HTTP ${e.statusCode()}). No se guardó nada."
        }
        is AnthropicIoException -> "No se pudo conectar con la API de Claude. Revisa tu conexión a Internet."
        else -> "La respuesta de Claude llegó incompleta. Reintenta."
    }
}
