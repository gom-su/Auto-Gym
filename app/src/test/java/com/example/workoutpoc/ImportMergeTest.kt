package com.example.workoutpoc

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.example.workoutpoc.planning.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** ENT-09: re-importing never duplicates weeks or sets, appends only new weeks, and reports conflicts by path. */
class ImportMergeTest {
    @get:Rule val folder = TemporaryFolder()
    private val full = ProgramCodec.decode(javaClass.classLoader!!.getResourceAsStream("examples/variable-program-v1.json")!!
        .bufferedReader().use { it.readText() })
    /** The same program as first published: only its first week. */
    private val firstWeekOnly = full.copy(blocks = listOf(full.blocks[0].copy(weeks = full.blocks[0].weeks.take(1))))

    private fun store(file: File, scope: CoroutineScope): DataStore<ProgramLibrary> =
        DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM, object : OkioSerializer<ProgramLibrary> {
            override val defaultValue = ProgramLibrarySerializer.defaultValue
            override suspend fun readFrom(source: BufferedSource) = ProgramLibrarySerializer.readFrom(source.inputStream())
            override suspend fun writeTo(t: ProgramLibrary, sink: BufferedSink) { ProgramLibrarySerializer.writeTo(t, sink.outputStream()) }
        }) { file.absolutePath.toPath() }, scope = scope)

    private fun TrainingProgram.weekIds() = blocks.flatMap { b -> b.weeks.map { it.id } }
    private fun TrainingProgram.setIds() = blocks.flatMap { b -> b.weeks.flatMap { w -> w.days.flatMap { d -> d.exercises.flatMap { e -> e.sets.map { it.id } } } } }

    @Test fun `second import of the same package is a no-op`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = folder.newFolder().resolve("programs-v1.json")
        val repo = ProgramRepository(store(file, scope)); repo.restore()
        assertEquals(ImportResult.SAVED, repo.importProgram(full))
        val bytes = file.readBytes()
        assertEquals(MergeKind.IDENTICAL, repo.preview(full).kind)
        assertEquals(ImportResult.ALREADY_IMPORTED, repo.importProgram(full))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(1, repo.library.value.programs.size)
        scope.cancel(); scope.coroutineContext[Job]!!.join()
    }

    @Test fun `a package with new weeks appends only those weeks without duplicating saved ones`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = ProgramRepository(store(folder.newFolder().resolve("programs-v1.json"), scope)); repo.restore()
        repo.importProgram(firstWeekOnly)
        val plan = repo.preview(full)
        assertEquals(MergeKind.ADDS_WEEKS, plan.kind)
        assertEquals(listOf("Semana 2", "Semana 3"), plan.addedWeeks); assertEquals(1, plan.unchangedWeeks)
        assertTrue(plan.summary().contains("2 semanas nuevas"))
        assertEquals(ImportResult.MERGED, repo.importProgram(full))
        val saved = repo.library.value.programs.single()
        assertEquals(full, saved)
        assertEquals(saved.weekIds().distinct(), saved.weekIds())
        assertEquals(saved.setIds().distinct(), saved.setIds())
        // Re-importing either package afterwards changes nothing.
        assertEquals(ImportResult.ALREADY_IMPORTED, repo.importProgram(full))
        assertEquals(ImportResult.ALREADY_IMPORTED, repo.importProgram(firstWeekOnly))
        assertEquals(MergeKind.IDENTICAL, repo.preview(firstWeekOnly).kind)
        assertEquals(listOf("Semana 2", "Semana 3"), repo.preview(firstWeekOnly).keptWeeks)
        assertEquals(full, repo.library.value.programs.single())
        scope.cancel(); scope.coroutineContext[Job]!!.join()
    }

    @Test fun `changes to saved content are conflicts listed by path and nothing is written`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = folder.newFolder().resolve("programs-v1.json")
        val repo = ProgramRepository(store(file, scope)); repo.restore()
        repo.importProgram(firstWeekOnly)
        val bytes = file.readBytes()
        val week = full.blocks[0].weeks[0]
        val day = week.days[0]; val ex = day.exercises[0]
        val changedSet = ex.sets[1].copy(reps = 10, load = PrescribedLoad(25.0, LoadUnit.KG, LoadBasis.TOTAL))
        val changed = full.copy(name = "Otro nombre", blocks = full.blocks.map { b -> b.copy(weeks = b.weeks.map { w ->
            if (w.id != week.id) w else w.copy(days = w.days.map { d -> if (d.id != day.id) d else d.copy(exercises = d.exercises.map { e ->
                if (e.id != ex.id) e else e.copy(sets = listOf(e.sets[0], changedSet)) }) }) }) })
        val plan = repo.preview(changed)
        assertEquals(MergeKind.CONFLICT, plan.kind)
        assertTrue(plan.conflicts.any { it.startsWith("Nombre del programa") })
        assertTrue(plan.conflicts.any { it.contains("serie 2: repeticiones 6 → 10") })
        assertTrue(plan.conflicts.any { it.contains("serie 2: carga") })
        assertTrue(plan.summary().contains("contenido diferente"))
        try { repo.importProgram(changed); fail("Expected conflict") }
        catch (e: ProgramValidationException) { assertTrue(e.message!!.contains("repeticiones 6 → 10")) }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(firstWeekOnly, repo.library.value.programs.single())
        scope.cancel(); scope.coroutineContext[Job]!!.join()
    }

    @Test fun `structural conflicts and reused ids in new weeks are reported`() {
        // A saved week moved to another block.
        val moved = full.copy(blocks = listOf(full.blocks[0].copy(weeks = full.blocks[0].weeks.drop(1)),
            TrainingBlock("block-moved", "Bloque nuevo", listOf(full.blocks[0].weeks[0]))))
        assertTrue(ProgramMerge.plan(firstWeekOnly, moved).conflicts.any { it.contains("aparece en otro bloque") })
        // Removing a set from a saved day.
        val w = full.blocks[0].weeks[0]
        val removed = firstWeekOnly.copy(blocks = listOf(full.blocks[0].copy(weeks = listOf(w.copy(days = w.days.map { d ->
            d.copy(exercises = d.exercises.map { e -> e.copy(sets = e.sets.take(1)) }) })))))
        assertTrue(ProgramMerge.plan(firstWeekOnly, removed).conflicts.any { it.contains("series eliminados") })
        // A new week that reuses a saved set ID.
        val reused = full.blocks[0].weeks[1].let { w2 -> w2.copy(days = w2.days.map { d -> d.copy(exercises = d.exercises.mapIndexed { ei, e ->
            if (ei != 0) e else e.copy(sets = e.sets.mapIndexed { i, s -> if (i == 0) s.copy(id = "set-one-squat-1") else s }) }) }) }
        // The package carries only the new week (the saved one is kept), so the reuse is only visible against the library.
        val withReuse = firstWeekOnly.copy(blocks = listOf(full.blocks[0].copy(weeks = listOf(reused))))
        val plan = ProgramMerge.plan(firstWeekOnly, withReuse)
        assertEquals(MergeKind.CONFLICT, plan.kind)
        assertTrue(plan.conflicts.single().contains("reutilizan identificadores"))
    }

    @Test fun `concurrent re-imports save once`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = ProgramRepository(store(folder.newFolder().resolve("programs-v1.json"), scope)); repo.restore()
        repo.importProgram(firstWeekOnly)
        val results = coroutineScope { (1..10).map { async { repo.importProgram(full) } }.awaitAll() }
        assertEquals(1, results.count { it == ImportResult.MERGED })
        assertEquals(9, results.count { it == ImportResult.ALREADY_IMPORTED })
        assertEquals(full, repo.library.value.programs.single())
        scope.cancel(); scope.coroutineContext[Job]!!.join()
    }
}
