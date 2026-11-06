package com.example.workoutpoc

import androidx.datastore.core.*
import androidx.datastore.core.okio.*
import com.example.workoutpoc.migration.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.File

class MigrationTest {
    @get:Rule val folder = TemporaryFolder()
    private fun fixture(week: Int = 1) = javaClass.classLoader!!.getResourceAsStream("examples/obsidian-week-v3-w$week.md")!!
        .bufferedReader().use { it.readText() }
    private fun document(week: Int = 1) = ObsidianParser.parse(fixture(week), "synthetic.md")

    @Test fun `both dialects preserve hierarchy source lines and separate comments`() {
        val first = document()
        val second = document(2)
        for (d in listOf(first, second)) {
            assertEquals(2, d.days.size)
            assertTrue(d.metadata.containsKey("microcycle"))
            assertTrue(d.notes.any { it.author == "AGENT" })
            assertTrue(d.lines.any { it.kind == "WARMUP" })
            assertEquals(d.original, d.lines.joinToString("\n") { it.text })
            assertEquals((1..d.lines.size).toList(), d.lines.map { it.line })
            d.issues.forEach { assertTrue(it.line in 1..d.lines.size) }
            assertEquals(d, MigrationCodec.json.decodeFromString<MigrationDocument>(MigrationCodec.json.encodeToString(d)))
            MigrationCodec.validate(d)
        }
        val generic = first.days.first().exercises.first().comments.single()
        assertNull(generic.author)
        val split = second.days.first().exercises.first().comments
        assertEquals(listOf("AGENT", "USER"), split.map { it.author })
        assertEquals(listOf("Ajustar después de revisar.", "Técnica estable."), split.map { it.text })
        assertEquals(NumberRange(25.0), first.days.first().cardio.single { it.field == "Tiempo" }.durationMinutes)
        assertEquals(NumberRange(20.0, 30.0), second.days.first().cardio.single { it.field == "Tiempo" }.durationMinutes)
        assertNull(first.days.first().status)
        assertEquals("PARTIAL", second.days.first().status)
    }

    @Test fun `ranges groups extra sets unknown zero and attribution stay independent`() {
        val d = document()
        val press = d.days.first().exercises[0]
        assertEquals(3, press.prescription.sets)
        assertEquals(NumberRange(8.0, 12.0), press.prescription.reps)
        assertEquals(NumberRange(3.0), press.prescription.rir)
        assertEquals(listOf(12.0, 12.0, 16.0), press.reps.values)
        assertEquals(listOf(3.0, 2.0, 0.0), press.rir.values)
        assertEquals("PER_HAND", press.load.basis)
        val row = d.days.first().exercises[1]
        assertEquals(2, row.prescription.sets)
        assertEquals(listOf(12.0, 14.0, 16.0), row.reps.values)
        assertEquals(listOf(null, null, 0.0), row.rir.values)
        assertNull(row.load.basis)
        assertTrue(d.issues.any { it.code == "COUNT_REPS" && it.line == row.line })
        assertTrue(d.issues.any { it.code == "AMBIGUOUS_RIR" && it.line == row.line })
        assertTrue(d.days.first().exercises[2].reps.values.isEmpty())
        assertEquals("BODYWEIGHT", d.days.first().exercises[2].load.unit)
        assertEquals(NumberRange(2.0, 3.0), document(2).days.first().exercises[0].prescription.rir)
        assertEquals("PER_SIDE", document(2).days.first().exercises[0].load.basis)
        assertEquals("TOTAL", document(2).days.first().exercises[1].load.basis)
        assertEquals(listOf(0.0, 10.0), document(2).days[1].exercises.single().reps.values)
        assertNull(document(2).days.first().exercises[2].load.magnitude)
    }

    @Test fun `unknown format metadata columns code and broken tables cannot claim complete migration`() {
        val d = ObsidianParser.parse("""
            ---
            week: 1
            week: 2
            unknown:
              - preserve
            ---
            # Día A
            ## Desconocido
            | Ejercicio | Objetivo | Sorpresa |
            | --- | --- | --- |
            | Ficticio | 2×10 | conservar |
            | rota |
            ```
            # Día B
            ```
        """.trimIndent(), "test")
        assertEquals(1, d.days.size)
        assertEquals("conservar", d.days.single().exercises.single().columns["sorpresa"])
        assertTrue(d.issues.map { it.code }.containsAll(listOf("DUPLICATE_METADATA", "UNKNOWN_METADATA", "UNKNOWN_COLUMN", "UNKNOWN_SECTION", "UNSUPPORTED_TABLE", "UNINTERPRETED")))
        val old = ObsidianParser.parse("# Semana antigua\n### Martes\n| Ejercicio | Series |\n| --- | --- |\n| Ficticio | 2 |", "old")
        assertTrue(old.days.isEmpty())
        assertTrue(old.issues.any { it.code == "UNSUPPORTED_FORMAT" })
        val broken = ObsidianParser.parse("---\nweek: 1\n# Día A", "broken")
        assertTrue(broken.days.isEmpty())
        assertTrue(broken.issues.any { it.code == "FRONTMATTER" })
    }

    @Test fun `identity uses original bytes and parser instead of filename or display labels`() {
        val first = document()
        assertEquals(first.id, ObsidianParser.parse(fixture(), "renamed.md").id)
        assertNotEquals(first.id, ObsidianParser.parse(fixture() + "\n", "synthetic.md").id)
        val crlf = fixture().replace("\n", "\r\n")
        assertEquals(crlf, ObsidianParser.parse(crlf, "windows.md").lines.joinToString("\n") { it.text })
        assertNotEquals(first.id, ObsidianParser.parse(crlf, "windows.md").id)
    }

    @Test fun `unrecognized sections stay raw and large result groups remain bounded`() {
        val d = ObsidianParser.parse(fixture().replace("**Estado:** ✅ / 🟡 / ❌", "**Apartado privado:** texto local\n**Estado:** ✅ / 🟡 / ❌") +
            "\n# Semana 2\n# Día C\n", "bounded")
        assertTrue(d.issues.any { it.code == "UNKNOWN_FIELD" })
        assertTrue(d.issues.any { it.code == "MULTIPLE_WEEKS" })
        assertTrue(d.days.flatMap { it.cardio }.none { it.field == "Apartado privado" })
        assertTrue(d.lines.any { it.text.contains("Apartado privado") && it.kind == "UNINTERPRETED" })
        val groups = ObsidianParser.parse(fixture().replace("2x12,1x16", "1000x12,1000x12,1000x12"), "bounded")
        assertEquals(1001, groups.days.first().exercises.first().reps.values.size)
        assertTrue(groups.days.first().exercises.first().reps.values.last() == null)
        assertTrue(groups.issues.any { it.code == "AMBIGUOUS_REPS" })
    }

    @Test fun `invalid ranges results and oversized UTF8 are rejected or flagged without inference`() {
        assertThrows(MigrationException::class.java) { ObsidianParser.parse(" ", "blank") }
        assertThrows(MigrationException::class.java) { ObsidianParser.parse("é".repeat(MigrationCodec.MAX_BYTES / 2 + 1), "big") }
        val raw = fixture().replace("3×8–12 @ RIR 3", "3×12–8 @ RIR 30")
            .replace("2x12,1x16", "9999999999999999x12,-2,1001")
        val d = ObsidianParser.parse(raw, "invalid")
        assertTrue(d.issues.any { it.code == "PRESCRIPTION" })
        assertNull(d.days.first().exercises.first().prescription.reps)
        assertTrue(d.issues.any { it.code == "AMBIGUOUS_REPS" })
        val slash = ObsidianParser.parse(fixture().replace("2x12,1x16", "12/11/10"), "slash")
        assertEquals(listOf<Double?>(null), slash.days.first().exercises.first().reps.values)
        val escaped = ObsidianParser.parse(fixture().replace("Pausa breve.", "Texto \\| conservado."), "escaped")
        assertEquals("Texto \\| conservado.", escaped.days.first().exercises.first().comments.single().text)
    }

    private class MemoryStore : DataStore<MigrationArchive> {
        val state = MutableStateFlow(MigrationArchive())
        var fail = false
        override val data: Flow<MigrationArchive> = state
        override suspend fun updateData(transform: suspend (MigrationArchive) -> MigrationArchive): MigrationArchive {
            if (fail) throw IOException("disk full")
            return transform(state.value).also { state.value = it }
        }
    }

    @Test fun `concurrent imports never duplicate and repeat imports preserve saved review`() = runTest {
        val repo = MigrationRepository(MemoryStore())
        val d = document()
        val results = coroutineScope { (1..20).map { async { repo.save(d) } }.awaitAll() }
        assertEquals(1, results.count { it })
        repo.acknowledge(d.id, d.issues.first().id, true)
        val reviewed = repo.archive.value
        assertFalse(repo.save(d.copy(source = "renamed.md")))
        assertEquals(reviewed, repo.archive.value)
        assertTrue(repo.save(ObsidianParser.parse(d.original + "\n", d.source)))
        assertEquals(2, repo.archive.value.documents.size)
        try { repo.save(d.copy(title = "invented")); fail("Expected rejection") } catch (_: MigrationException) { }
        assertEquals(reviewed.documents.first(), repo.archive.value.documents.first())
        repo.acknowledge(d.id, d.issues.first().id, false)
        assertFalse(repo.archive.value.documents.first().issues.first().acknowledged)
    }

    @Test fun `failed saves and review updates publish no unsaved changes and retry works`() = runTest {
        val store = MemoryStore()
        val repo = MigrationRepository(store)
        store.fail = true
        try { repo.save(document()); fail("Expected failure") } catch (_: IOException) { }
        assertTrue(repo.archive.value.documents.isEmpty())
        store.fail = false
        repo.save(document())
        val before = repo.archive.value
        store.fail = true
        try { repo.acknowledge(document().id, document().issues.first().id, true); fail("Expected failure") } catch (_: IOException) { }
        assertEquals(before, repo.archive.value)
        store.fail = false
        repo.acknowledge(document().id, document().issues.first().id, true)
        assertTrue(repo.archive.value.documents.single().issues.first().acknowledged)
    }

    @Test fun `real atomic file restores original interpretations and review after recreation`() = runTest {
        val file = folder.newFolder().resolve("migrations.json")
        fun store(scope: CoroutineScope) = DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM,
            object : OkioSerializer<MigrationArchive> {
                override val defaultValue = MigrationArchive()
                override suspend fun readFrom(source: BufferedSource) = MigrationArchiveSerializer.readFrom(source.inputStream())
                override suspend fun writeTo(t: MigrationArchive, sink: BufferedSink) = MigrationArchiveSerializer.writeTo(t, sink.outputStream())
            }) { file.absolutePath.toPath() }, scope = scope)
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = MigrationRepository(store(firstScope))
        first.save(document()); first.acknowledge(document().id, document().issues.first().id, true)
        val expected = first.archive.value
        firstScope.cancel(); firstScope.coroutineContext[Job]!!.join()
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val second = MigrationRepository(store(secondScope))
            second.restore()
            assertEquals(expected, second.archive.value)
            assertFalse(second.save(document()))
        } finally { secondScope.cancel(); secondScope.coroutineContext[Job]!!.join() }
    }

    @Test fun `corrupt future and tampered archives fail without resetting source bytes`() = runTest {
        val valid = MigrationArchive(documents = listOf(document()))
        val bad = listOf("{bad", "{}", "{\"schemaVersion\":2,\"documents\":[]}",
            MigrationCodec.json.encodeToString(valid.copy(documents = listOf(document().copy(parserVersion = 2)))),
            MigrationCodec.json.encodeToString(valid.copy(documents = listOf(document().copy(title = "invented")))),
            MigrationCodec.json.encodeToString(valid.copy(documents = listOf(document(), document()))))
        for (text in bad) {
            val bytes = text.toByteArray()
            try { MigrationArchiveSerializer.readFrom(ByteArrayInputStream(bytes)); fail("Expected corruption") }
            catch (_: CorruptionException) { }
            assertArrayEquals(text.toByteArray(), bytes)
        }
        try { MigrationArchiveSerializer.readFrom(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))); fail("Expected invalid UTF8") }
        catch (_: CorruptionException) { }
    }

    /** Explicit opt-in; no real text is printed, copied into fixtures, or included in assertion messages. */
    @Test fun `private Workout v3 acceptance when local source directory is provided`() {
        val path = System.getenv("ENT03_PRIVATE_WORKOUT_DIR")
        assumeTrue("Private validation requires ENT03_PRIVATE_WORKOUT_DIR", path != null)
        for ((name, expectedWeek) in listOf("W1 - Semana 2 de Julio.md" to "1", "W2 - Semana 3 de Julio.md" to "2")) {
            val raw = File(path!!, name).readText(Charsets.UTF_8)
            val d = ObsidianParser.parse(raw, "private.md")
            assertTrue(d.metadata["week"] == expectedWeek)
            assertEquals(4, d.days.size)
            assertEquals(listOf(6, 6, 5, 3), d.days.map { it.exercises.size })
            assertTrue(d.days.all { it.cardio.any { c -> c.durationMinutes != null } })
            assertTrue(d.issues.any { it.code == "LOAD" })
            assertTrue(d.issues.any { it.code == "AMBIGUOUS_RIR" })
            assertTrue(d.issues.any { it.code == "COUNT_REPS" })
            assertTrue(d.original == d.lines.joinToString("\n") { it.text })
            assertTrue(d.notes.any { it.author == "AGENT" })
            assertTrue(d.days.flatMap { it.exercises }.all { it.comments.isNotEmpty() })
            MigrationCodec.validate(d)
        }
    }
}
