package com.example.workoutpoc

import androidx.datastore.core.*
import androidx.datastore.core.okio.*
import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.*
import com.example.workoutpoc.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException

class ExchangeTest {
    @get:Rule val folder = TemporaryFolder()
    private val date = "2026-10-30T12:00:00Z"
    private fun program() = ProgramCodec.decode(javaClass.classLoader!!.getResourceAsStream("examples/variable-program-v1.json")!!.bufferedReader().use { it.readText() })
    private fun results() = TrainingResults(listOf(SessionResult("session", "day-one-a", PerformanceStatus.PARTIAL, date, date,
        "Nota ficticia", listOf(SetResult("result-set", "set-one-squat-1", "squat", PerformanceStatus.COMPLETED,
            0, 0, PrescribedLoad(0.0, LoadUnit.LB, LoadBasis.PER_HAND), date, null),
            SetResult("missing-set", null, "squat", PerformanceStatus.OMITTED, null, null, null, null, "Omitida")),
        listOf(CardioResult("cardio", PerformanceStatus.PARTIAL, 0, 1.5, "KM", "Ficticio")))),
        DemoSnapshot(WorkoutState(sessionId = "demo", phase = Phase.Resting, index = 0, revision = 5,
            records = listOf(SetRecord(0, "Ficticio", 1, SetValues(), 1234)), rest = RestDeadline(9, 10, 1))))
    private fun packages(): List<ExchangePackage> {
        val p = program()
        return listOf(
            ExchangePackage(1, ExchangeType.TrainingContext, "context", date, context = TrainingContext(listOf(p), "Base", results(),
                listOf("Regla ficticia v1"), listOf("Objetivo ficticio"), listOf(BodyMeasurement("measure", date, "mass", 70.5, "KG")))),
            ExchangePackage(1, ExchangeType.WeekProposal, "proposal", date, proposal = WeekProposal("proposal-v1", 1, p.id, p.blocks[0].id,
                "Agente ficticio", p.blocks[0].weeks[0], listOf("Cambio ficticio"), listOf("Razón ficticia"))),
            ExchangePackage(1, ExchangeType.TrainingResults, "results", date, results = results()))
    }
    @Test fun `all supported fields round trip including missing zero notes units dates and legacy snapshot`() {
        packages().forEach { assertEquals(it, ExchangeCodec.decode(ExchangeCodec.encode(it))) }
    }
    @Test fun `public synthetic examples are valid and lossless`() {
        for (name in listOf("training-context-v1", "week-proposal-v1", "training-results-v1")) {
            val text = javaClass.classLoader!!.getResourceAsStream("examples/$name.json")!!.bufferedReader().use { it.readText() }
            val decoded = ExchangeCodec.decode(text)
            assertEquals(decoded, ExchangeCodec.decode(ExchangeCodec.encode(decoded)))
        }
    }
    @Test fun `redaction requires opt in and has stable different identity without modifying stored context`() {
        val original = packages()[0]
        val redacted = ExchangePrivacy.forExport(original, false)
        assertTrue(redacted.context!!.measurements.isEmpty())
        assertNotEquals(original.importId, redacted.importId)
        assertEquals(redacted, ExchangePrivacy.forExport(original, false))
        assertEquals(original, ExchangePrivacy.forExport(original, true))
        assertEquals(1, original.context!!.measurements.size)
        assertEquals(original.context!!.history, redacted.context!!.history)
    }
    @Test fun `invalid version fields types units dates and discriminators rejected`() {
        val text = ExchangeCodec.encode(packages()[2])
        val bad = listOf("{}", "not json", text.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
            text.replace("\"schemaVersion\": 1", "\"extra\": 1, \"schemaVersion\": 1"),
            text.replace("TrainingResults", "WeekProposal"), text.replace("2026-10-30T12:00:00Z", "yesterday"),
            text.replace("\"LB\"", "\"UNKNOWN\""), text.replace("\"PER_HAND\"", "\"BODYWEIGHT\""),
            text.replace("\"KM\"", "\"UNKNOWN\""), text.replace("\"distanceUnit\": \"KM\"", "\"distanceUnit\": null"),
            text.replace("\"reps\": 0", "\"reps\": -1"), "á".repeat(ExchangeCodec.MAX_BYTES / 2 + 1))
        bad.forEach { assertThrows(ProgramValidationException::class.java) { ExchangeCodec.decode(it) } }
        assertThrows(ProgramValidationException::class.java) { ExchangeCodec.encode(packages()[0].copy(results = results())) }
        assertThrows(ProgramValidationException::class.java) { ExchangeCodec.encode(packages()[2].copy(results = results().copy(sessions = results().sessions + results().sessions))) }
    }
    private class MemoryStore : DataStore<ExchangeArchive> {
        val state = MutableStateFlow(ExchangeArchiveSerializer.defaultValue)
        var fail = false
        override val data: Flow<ExchangeArchive> = state
        override suspend fun updateData(transform: suspend (ExchangeArchive) -> ExchangeArchive): ExchangeArchive {
            if (fail) throw IOException("disk full")
            return transform(state.value).also { state.value = it }
        }
    }
    @Test fun `atomic idempotent imports reject conflicts and publish only saved data`() = runTest {
        val store = MemoryStore(); val repo = ExchangeRepository(store); val data = packages()[0]
        val imported = coroutineScope { (1..12).map { async { repo.save(data) } }.awaitAll() }
        assertEquals(1, imported.count { it })
        try { repo.save(data.copy(createdAt = "2026-10-31T12:00:00Z")); fail() } catch (_: ProgramValidationException) { }
        assertEquals(listOf(data), repo.archive.value.packages)
        store.fail = true
        try { repo.save(packages()[1]); fail() } catch (_: IOException) { }
        assertEquals(listOf(data), repo.archive.value.packages)
        store.fail = false
        assertTrue(repo.save(packages()[1]))
    }
    @Test fun `export imports into clean persistent store and survives recreation without loss`() = runTest {
        val file = folder.newFolder().resolve("exchange.json")
        fun store(scope: CoroutineScope) = DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM,
            object : OkioSerializer<ExchangeArchive> {
                override val defaultValue = ExchangeArchiveSerializer.defaultValue
                override suspend fun readFrom(source: BufferedSource) = ExchangeArchiveSerializer.readFrom(source.inputStream())
                override suspend fun writeTo(t: ExchangeArchive, sink: BufferedSink) = ExchangeArchiveSerializer.writeTo(t, sink.outputStream())
            }) { file.absolutePath.toPath() }, scope = scope)
        val source = ExchangeRepository(MemoryStore())
        packages().forEach { source.save(it) }
        val exported = source.archive.value.packages.map(ExchangeCodec::encode)
        val first = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val clean = ExchangeRepository(store(first))
        exported.forEach { clean.save(ExchangeCodec.decode(it)) }
        first.cancel(); first.coroutineContext[Job]!!.join()
        val second = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val restored = ExchangeRepository(store(second)); restored.restore()
            assertEquals(source.archive.value, restored.archive.value)
            exported.forEach { assertFalse(restored.save(ExchangeCodec.decode(it))) }
        } finally { second.cancel(); second.coroutineContext[Job]!!.join() }
    }
    @Test fun `future corrupt or incomplete archives fail without reset`() = runTest {
        for (text in listOf("{}", "{bad", "{\"schemaVersion\":2,\"packages\":[]}", "{\"schemaVersion\":1}")) {
            try { ExchangeArchiveSerializer.readFrom(ByteArrayInputStream(text.toByteArray())); fail() }
            catch (_: CorruptionException) { }
        }
    }
}
