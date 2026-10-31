package com.example.workoutpoc

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.example.workoutpoc.planning.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException

class ProgramTest {
    @get:Rule val folder = TemporaryFolder()
    private fun fixture() = javaClass.classLoader!!.getResourceAsStream("examples/variable-program-v1.json")!!.bufferedReader().use { it.readText() }
    private fun program() = ProgramCodec.decode(fixture())
    private fun rejected(text: String) = assertThrows(ProgramValidationException::class.java) { ProgramCodec.decode(text) }

    @Test fun `variable weeks preserve hierarchy order identity and distinct prescriptions`() {
        val program = program()
        assertEquals(2, program.blocks.size)
        val weeks = program.blocks.first().weeks
        assertEquals(listOf(2, 1), weeks.map { it.days.size })
        assertEquals(listOf(1, 1), weeks[0].days.map { it.exercises.size })
        assertEquals(2, weeks[1].days.single().exercises.size)
        assertEquals(listOf(2, 1), weeks[0].days.flatMap { it.exercises }.map { it.sets.size })
        assertEquals(listOf(1, 3), weeks[1].days.single().exercises.map { it.sets.size })
        val first = weeks[0].days.first().exercises.single()
        val second = weeks[1].days.single().exercises.first()
        assertEquals(first.exerciseId, second.exerciseId)
        assertNotEquals(first.id, second.id)
        assertEquals("set-two-squat-1", second.sets.single().id)
        assertEquals(LoadBasis.PER_SIDE, second.sets.single().load!!.basis)
        assertEquals(program, ProgramCodec.decode(ProgramCodec.json.encodeToString(ProgramPackage(1, program))))
    }

    @Test fun `missing values remain different from explicit zero`() {
        val weeks = program().blocks.first().weeks
        val zero = weeks.first().days.last().exercises.single().sets.single()
        val missing = weeks.last().days.single().exercises.last().sets.last()
        assertEquals(0, zero.rir); assertEquals(0, zero.restSeconds)
        assertNull(missing.rir); assertNull(missing.restSeconds); assertNull(missing.reps); assertNull(missing.load)
        assertEquals(0.0, weeks.last().days.single().exercises.first().sets.single().load!!.value, 0.0)
    }

    @Test fun `bad contracts cannot discard fields or infer required structure`() {
        rejected("{}"); rejected("not json")
        rejected(fixture().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
        rejected(fixture().replace("\"schemaVersion\": 1", "\"extra\": 1, \"schemaVersion\": 1"))
        rejected(fixture().replace("\"version\": 1", "\"version\": 0"))
        rejected(fixture().replace("\"reps\": 8", "\"reps\": 0"))
        rejected(fixture().replace("\"rir\": 3", "\"rir\": -1"))
        rejected(fixture().replace("\"unit\": \"KG\"", "\"unit\": \"UNKNOWN\""))
        rejected(fixture().replace("\"basis\": \"TOTAL\"", "\"basis\": \"MACHINE_SETTING\""))
        rejected(fixture().replace("\"restSeconds\": 90", "\"restSeconds\": -1"))
        rejected(fixture().replace("\"value\": 20", "\"value\": -20"))
        rejected(fixture().replace("set-one-squat-2", "set-one-squat-1"))
        rejected(fixture().replace("day-two-c", "day-one-a"))
        rejected(fixture().replace("Bloque base", " "))
        rejected(" ".repeat(ProgramCodec.MAX_BYTES + 1))
    }

    @Test fun `empty hierarchy is rejected without fixed day or set counts`() {
        val p = program()
        assertThrows(ProgramValidationException::class.java) { ProgramCodec.validate(p.copy(blocks = emptyList())) }
        assertThrows(ProgramValidationException::class.java) { ProgramCodec.validate(p.copy(blocks = listOf(p.blocks.first().copy(weeks = emptyList())))) }
        val block = p.blocks.first()
        val emptyWeek = block.weeks.first().copy(days = emptyList())
        assertThrows(ProgramValidationException::class.java) {
            ProgramCodec.validate(p.copy(blocks = listOf(block.copy(weeks = listOf(emptyWeek)))))
        }
        val week = block.weeks.first()
        val day = week.days.first()
        fun withDay(changed: SessionPlan) = p.copy(blocks = listOf(block.copy(weeks = listOf(week.copy(days = listOf(changed))))))
        assertThrows(ProgramValidationException::class.java) { ProgramCodec.validate(withDay(day.copy(exercises = emptyList()))) }
        assertThrows(ProgramValidationException::class.java) {
            ProgramCodec.validate(withDay(day.copy(exercises = listOf(day.exercises.first().copy(sets = emptyList())))))
        }
    }

    private class MemoryStore : DataStore<ProgramLibrary> {
        val state = MutableStateFlow(ProgramLibrary(1, emptyList()))
        var fail = false
        override val data: Flow<ProgramLibrary> = state
        override suspend fun updateData(transform: suspend (t: ProgramLibrary) -> ProgramLibrary): ProgramLibrary {
            if (fail) throw IOException("disk full")
            return transform(state.value).also { state.value = it }
        }
    }

    @Test fun `imports are serialized idempotent and conflicts never replace saved programs`() = runTest {
        val store = MemoryStore()
        val repo = ProgramRepository(store)
        val p = program()
        val results = coroutineScope { (1..20).map { async { repo.importProgram(p) } }.awaitAll() }
        assertEquals(1, results.count { it == ImportResult.SAVED })
        assertEquals(listOf(p), repo.library.value.programs)
        try { repo.importProgram(p.copy(name = "Changed")); fail("Expected conflict") } catch (_: ProgramValidationException) { }
        assertEquals(listOf(p), repo.library.value.programs)
        repo.importProgram(p.copy(id = "another-program"))
        assertEquals(2, repo.library.value.programs.size)
    }

    @Test fun `write failure does not publish unsaved state and retry works`() = runTest {
        val store = MemoryStore()
        val repo = ProgramRepository(store)
        store.fail = true
        try { repo.importProgram(program()); fail("Expected IO failure") } catch (_: IOException) { }
        assertTrue(repo.library.value.programs.isEmpty())
        store.fail = false
        assertEquals(ImportResult.SAVED, repo.importProgram(program()))
    }

    @Test fun `real atomic file restores all associations after repository and store recreation`() = runTest {
        val file = folder.newFolder().resolve("programs.json")
        fun store(scope: CoroutineScope) = DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM,
            object : OkioSerializer<ProgramLibrary> {
                override val defaultValue = ProgramLibrary(1, emptyList())
                override suspend fun readFrom(source: BufferedSource) = ProgramLibrarySerializer.readFrom(source.inputStream())
                override suspend fun writeTo(t: ProgramLibrary, sink: BufferedSink) = ProgramLibrarySerializer.writeTo(t, sink.outputStream())
            }) { file.absolutePath.toPath() }, scope = scope)
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val original = program()
        ProgramRepository(store(firstScope)).importProgram(original)
        firstScope.cancel(); firstScope.coroutineContext[Job]!!.join()
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val restored = ProgramRepository(store(secondScope))
            restored.restore()
            assertEquals(listOf(original), restored.library.value.programs)
            assertEquals(ImportResult.ALREADY_IMPORTED, restored.importProgram(original))
        } finally { secondScope.cancel(); secondScope.coroutineContext[Job]!!.join() }
    }

    @Test fun `corrupt or future library is surfaced rather than reset`() = runTest {
        for (text in listOf("{bad", "{}", "{\"schemaVersion\":1}", "{\"programs\":[]}", "{\"schemaVersion\":2,\"programs\":[]}")) {
            try { ProgramLibrarySerializer.readFrom(ByteArrayInputStream(text.toByteArray())); fail("Expected corruption") }
            catch (_: CorruptionException) { }
        }
    }
}
