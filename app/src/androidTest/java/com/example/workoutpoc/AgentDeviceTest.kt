package com.example.workoutpoc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.workoutpoc.agent.ClaudeChatFactory
import com.example.workoutpoc.agent.ClaudeModel
import com.example.workoutpoc.agent.KeystoreApiKeyVault
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress

/**
 * What only a device can show about the in-app agent: the key survives a round trip through the Android Keystore
 * without ever being on disk in clear, and the Claude SDK runs on Android end to end (request, TLS, streamed reply,
 * replayed history) against a local stand-in. No real key and no real API are involved.
 */
@RunWith(AndroidJUnit4::class)
class AgentDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = "sk-ant-api03-synthetic-device-key-000000wxyz"

    @Test fun keyIsStoredEncryptedAndRemoved() {
        // Its own file and Keystore entry: a real key saved on this device is never touched.
        val vault = KeystoreApiKeyVault(context, "device-test-key")
        val file = File(context.noBackupFilesDir, "device-test-key.bin")
        try {
            vault.clear()
            assertNull(vault.read())
            vault.write(key)
            assertFalse("The key must not be on disk in clear", file.readBytes().toString(Charsets.ISO_8859_1).contains(key))
            // A new instance, as after a restart, reads it back with the Keystore key.
            assertEquals(key, KeystoreApiKeyVault(context, "device-test-key").read())
            vault.write("$key-2")
            assertEquals("$key-2", vault.read())
        } finally { vault.clear() }
        assertFalse(file.exists())
        assertNull(vault.read())
    }

    private fun event(type: String, data: String) = "event: $type\ndata: $data\n\n"

    private fun stream(text: String): MockResponse {
        val usage = """"input_tokens":120,"cache_creation_input_tokens":0,"cache_read_input_tokens":0"""
        return MockResponse().setResponseCode(200).setHeader("content-type", "text/event-stream").setBody(
            event("message_start", """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"${ClaudeModel.ID}","content":[],"stop_reason":null,"stop_sequence":null,"usage":{$usage,"output_tokens":1}}}""") +
            event("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"firma-1"}}""") +
            event("content_block_stop", """{"type":"content_block_stop","index":0}""") +
            event("content_block_start", """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""") +
            event("content_block_delta", """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"$text"}}""") +
            event("content_block_stop", """{"type":"content_block_stop","index":1}""") +
            event("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{$usage,"output_tokens":42}}""") +
            event("message_stop", """{"type":"message_stop"}"""))
    }

    @Test fun sdkStreamsAConversationOnAndroid() {
        // The app allows no cleartext traffic, so the stand-in speaks TLS with a certificate made for this test.
        val host = InetAddress.getByName("localhost").canonicalHostName
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName(host).build()
        val serverSide = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientSide = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverSide.sslSocketFactory(), false)
        server.enqueue(stream("primero")); server.enqueue(stream("segundo"))
        server.start()
        try {
            val chat = ClaudeChatFactory {
                it.baseUrl(server.url("/").toString().trimEnd('/'))
                it.sslSocketFactory(clientSide.sslSocketFactory()); it.trustManager(clientSide.trustManager)
            }.open(key, "PROMPT")
            runBlocking {
                chat.use {
                    assertEquals("primero", it.send("uno").text)
                    val second = it.send("dos")
                    assertEquals("segundo", second.text)
                    assertEquals(42L, second.outputTokens)
                }
            }
            server.takeRequest()
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals(ClaudeModel.ID, body["model"]!!.jsonPrimitive.content)
            assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
            val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("user", "assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
            assertEquals(listOf("thinking", "text"), messages[1]["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        } finally { server.shutdown() }
    }
}
