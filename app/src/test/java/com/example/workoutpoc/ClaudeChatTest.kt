package com.example.workoutpoc

import com.example.workoutpoc.agent.AgentException
import com.example.workoutpoc.agent.ClaudeChatFactory
import com.example.workoutpoc.agent.ClaudeModel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * The SDK-backed conversation against a local stand-in for the API: what the app sends (model, key, system
 * prompt, effort, refusal fallback, streaming), that earlier turns are replayed unchanged, and how failures read.
 */
class ClaudeChatTest {
    private val key = "sk-ant-api03-synthetic-test-key-0000000000wxyz"
    private val server = MockWebServer()
    private val bodies = mutableListOf<JsonObject>()
    private val headers = mutableListOf<Map<String, String>>()
    private val paths = mutableListOf<String>()
    private var respond: () -> MockResponse = { stream("hola") }

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                bodies += Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                headers += request.headers.names().associate { it.lowercase() to request.headers.values(it).joinToString(",") }
                paths += request.path.orEmpty()
                return respond()
            }
        }
        server.start()
    }
    @After fun stop() { try { server.shutdown() } catch (_: Exception) { } }

    private fun chat() = ClaudeChatFactory { it.baseUrl(server.url("/").toString().trimEnd('/')) }.open(key, "PROMPT DEL SISTEMA")

    private fun event(type: String, data: String) = "event: $type\ndata: $data\n\n"

    /** A streamed reply with a reasoning block followed by [text]. */
    private fun stream(text: String, stop: String = "end_turn"): MockResponse {
        val usage = """"input_tokens":120,"cache_creation_input_tokens":0,"cache_read_input_tokens":0"""
        val sse = event("message_start", """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"${ClaudeModel.ID}","content":[],"stop_reason":null,"stop_sequence":null,"usage":{$usage,"output_tokens":1}}}""") +
            event("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"firma-1"}}""") +
            event("content_block_stop", """{"type":"content_block_stop","index":0}""") +
            event("content_block_start", """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":${JsonPrimitive(text)}}}""") +
            event("content_block_stop", """{"type":"content_block_stop","index":1}""") +
            event("message_delta", """{"type":"message_delta","delta":{"stop_reason":"$stop","stop_sequence":null},"usage":{$usage,"output_tokens":42}}""") +
            event("message_stop", """{"type":"message_stop"}""")
        return MockResponse().setResponseCode(200).setHeader("content-type", "text/event-stream").setBody(sse)
    }

    private fun error(status: Int, type: String) = MockResponse().setResponseCode(status).setHeader("content-type", "application/json")
        .setBody("""{"type":"error","error":{"type":"$type","message":"synthetic"}}""")

    @Test fun `a turn sends the key, the model, the system prompt, effort and the refusal fallback, streamed`() = runBlocking {
        val reply = chat().use { it.send("Planifica la semana") }
        assertEquals("hola", reply.text)
        assertFalse(reply.truncated)
        assertEquals(120L, reply.inputTokens); assertEquals(42L, reply.outputTokens)
        val body = bodies.single()
        assertTrue(paths.single().startsWith("/v1/messages"))
        // The SDK chooses the header for its API version (x-api-key, or a bearer token since 2026-10-21).
        assertEquals(key, headers.single()["x-api-key"] ?: headers.single()["authorization"]?.removePrefix("Bearer "))
        assertTrue(headers.single()["anthropic-beta"]!!.contains(ClaudeModel.FALLBACK_BETA))
        assertEquals(ClaudeModel.ID, body["model"]!!.jsonPrimitive.content)
        assertEquals(ClaudeModel.MAX_TOKENS, body["max_tokens"]!!.jsonPrimitive.long)
        assertEquals("PROMPT DEL SISTEMA", body["system"]!!.jsonPrimitive.content)
        assertEquals("high", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
        // Opus 5.5 rejects sampling parameters and any thinking configuration other than adaptive.
        assertTrue(listOf("temperature", "top_p", "top_k", "thinking").none { it in body })
        val message = body["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
    }

    @Test fun `the next turn replays the conversation unchanged, reasoning blocks included`() = runBlocking {
        chat().use { chat ->
            chat.send("primero")
            respond = { stream("segundo") }
            assertEquals("segundo", chat.send("respuestas").text)
        }
        val messages = bodies[1]["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        val replayed = messages[1]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("thinking", "text"), replayed.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("firma-1", replayed[0]["signature"]!!.jsonPrimitive.content)
        assertEquals("hola", replayed[1]["text"]!!.jsonPrimitive.content)
    }

    @Test fun `a rejected key is explained and the failed turn is not kept`() = runBlocking {
        chat().use { chat ->
            respond = { error(401, "authentication_error") }
            try { chat.send("uno"); fail("Expected the key to be rejected") }
            catch (e: AgentException) { assertTrue(e.message, e.message!!.contains("llave")); assertFalse(e.message!!.contains(key)) }
            respond = { stream("ok") }
            assertEquals("ok", chat.send("dos").text)
        }
        assertEquals(1, bodies.last()["messages"]!!.jsonArray.size)
    }

    @Test fun `no credit, an unavailable service and a cut reply are told apart`() = runBlocking {
        chat().use { chat ->
            respond = { error(402, "billing_error") }
            try { chat.send("x"); fail("Expected a billing failure") } catch (e: AgentException) { assertTrue(e.message, e.message!!.contains("crédito")) }
            respond = { error(400, "invalid_request_error") }
            try { chat.send("x"); fail("Expected a rejected request") } catch (e: AgentException) { assertTrue(e.message, e.message!!.contains("HTTP 400")) }
            respond = { stream("{\"a\":", stop = "max_tokens") }
            assertTrue(chat.send("x").truncated)
        }
    }

    @Test fun `no connection is reported as such`() = runBlocking {
        val chat = chat()
        server.shutdown()
        try { chat.send("x"); fail("Expected a connection failure") }
        catch (e: AgentException) { assertTrue(e.message, e.message!!.contains("conectar")) }
        finally { chat.close() }
    }
}
