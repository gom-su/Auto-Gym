package com.example.workoutpoc

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.ProgramValidationException
import com.example.workoutpoc.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/** INT-02: no external write is accepted without a paired key, and conflicts or retries never lose records. */
class SignedSyncTest {
    @get:Rule val folder = TemporaryFolder()
    // Synthetic, test-only pairing key and an envelope signed with it by mcp/auto_gym_mcp.py (cross-language check).
    private val shared = File("../mcp/examples/test-pairing-key.txt").readText().trim().split(".")
    private val key = PairedKey(shared[1], shared[2], 1)
    private val signed = File("../mcp/examples/signed-week-proposal-from-mcp.json").readText()
    private val envelope = Json.decodeFromString<SyncEnvelope>(signed)
    private val issued = Instant.parse(envelope.issuedAt).toEpochMilli()
    private val state = SyncState(keys = listOf(key))

    private fun resign(e: SyncEnvelope): String {
        val secret = java.util.Base64.getUrlDecoder().decode(key.secret)
        return Json.encodeToString(SyncEnvelope.serializer(), e.copy(mac = SignedSync.mac(secret, SignedSync.signingInput(e))))
    }
    private fun rejected(text: String, s: SyncState = state, now: Long = issued + 3_600_000): String =
        (SignedSync.verify(text, s, now) as? Verification.Rejected)?.reason ?: error("accepted")

    @Test fun `an envelope signed by the MCP server with the paired key verifies`() {
        assertTrue(SignedSync.isEnvelope(signed))
        val v = SignedSync.verify(signed, state, issued + 3_600_000) as Verification.Accepted
        assertEquals(ExchangeType.WeekProposal, v.pkg.type)
        assertEquals("Agente vía MCP", v.pkg.proposal!!.source)
    }

    @Test fun `unauthorized, altered, disallowed, stale and future deliveries are rejected`() {
        assertTrue(rejected(signed, SyncState()).contains("no emparejada"))
        assertTrue(rejected(signed, SyncState(keys = listOf(key.copy(revoked = true)))).contains("revocada"))
        assertTrue(rejected(signed.replace("Semana firmada por MCP", "Semana alterada por MCP")).contains("Firma no válida"))
        assertTrue(rejected(Json.encodeToString(SyncEnvelope.serializer(), envelope.copy(keyId = "k-other"))).contains("no emparejada"))
        // Other payload types cannot be written from outside, even when correctly signed.
        val results = ExchangeCodec.encode(ExchangePackage(1, ExchangeType.TrainingResults, "r", "2026-11-06T12:00:00Z", results = TrainingResults(emptyList())))
        assertTrue(rejected(resign(envelope.copy(payloadType = "TrainingResults", payload = results))).contains("no permitido"))
        // A correctly signed envelope whose declared type does not match its content.
        val mismatch = resign(envelope.copy(payloadType = "WeekProposal", payload = results))
        assertTrue(rejected(mismatch).contains("no coincide"))
        assertTrue(rejected(signed, now = issued + 8 * 86_400_000L).contains("caducado"))
        assertTrue(rejected(signed, now = issued - 3_600_000).contains("futuro"))
        assertTrue(rejected("{\"syncVersion\":2}").isNotBlank())
        assertTrue(rejected(signed, SyncState(keys = listOf(key), seen = listOf(SeenNonce(key.keyId, envelope.nonce, issued)))).contains("repetido"))
    }

    private fun <T> store(file: File, scope: CoroutineScope, s: Serializer<T>): DataStore<T> =
        DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM, object : OkioSerializer<T> {
            override val defaultValue: T = s.defaultValue
            override suspend fun readFrom(source: BufferedSource): T = s.readFrom(source.inputStream())
            override suspend fun writeTo(t: T, sink: BufferedSink) { s.writeTo(t, sink.outputStream()) }
        }) { file.absolutePath.toPath() }, scope = scope)

    @Test fun `accepting records the nonce once, replays and conflicts never replace stored data`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dir = folder.newFolder()
        val sync = SyncRepository(store(dir.resolve("sync.json"), scope, SyncStateSerializer), { issued + 3_600_000 })
        val exchanges = ExchangeRepository(store(dir.resolve("x.json"), scope, ExchangeArchiveSerializer))
        sync.restore(); exchanges.restore()
        // Pair with the shared synthetic key (as the user would by pasting it on both sides).
        val stateWithKey = SyncState(keys = listOf(key))
        val syncStore = store(dir.resolve("sync2.json"), scope, SyncStateSerializer)
        syncStore.updateData { stateWithKey }
        val repo = SyncRepository(syncStore, { issued + 3_600_000 })
        repo.restore()
        var saves = 0
        val first = repo.accept(signed) { saves++; exchanges.save(it) }
        assertTrue(first is Verification.Accepted)
        assertEquals(1, exchanges.archive.value.packages.size)
        // A retry of the same delivery (e.g. after a disconnection) is rejected as a replay and changes nothing.
        val again = repo.accept(signed) { saves++; exchanges.save(it) }
        assertTrue((again as Verification.Rejected).reason.contains("repetido"))
        assertEquals(1, saves); assertEquals(1, exchanges.archive.value.packages.size)
        // A different, correctly signed delivery reusing the importId with other content conflicts and is not stored.
        val pkg = (first as Verification.Accepted).pkg
        val conflicting = ExchangeCodec.encode(pkg.copy(proposal = pkg.proposal!!.copy(reasons = listOf("Otro motivo"))))
        val secondDelivery = resign(envelope.copy(nonce = "0123456789abcdef0123456789abcdef", payload = conflicting))
        try { repo.accept(secondDelivery) { exchanges.save(it) }; fail("conflict must not replace the archived package") }
        catch (_: ProgramValidationException) { }
        assertEquals(pkg, exchanges.archive.value.packages.single())
        // Pairing creates fresh keys; revoking blocks further deliveries.
        val paired = sync.pair()
        assertTrue(SignedSync.shareable(paired).startsWith("agk1.${paired.keyId}."))
        sync.revoke(paired.keyId)
        assertTrue(sync.state.value.keys.single().revoked)
        scope.cancel(); scope.coroutineContext[Job]!!.join()
    }
}
