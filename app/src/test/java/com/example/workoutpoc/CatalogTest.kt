package com.example.workoutpoc

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.example.workoutpoc.catalog.*
import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
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
import java.io.ByteArrayInputStream
import java.io.File

/** ENT-08: stable exercise identity; renaming never breaks history; non-equivalent variants stay distinct. */
class CatalogTest {
    @get:Rule val folder = TemporaryFolder()
    private val time = ClockSnapshot(1_000, 1_800_000_000_000, 5)
    private var ids = 0
    private val program = ProgramCodec.decode(javaClass.classLoader!!.getResourceAsStream("examples/variable-program-v1.json")!!
        .bufferedReader().use { it.readText() })

    private fun store(file: File, scope: CoroutineScope): DataStore<ExerciseCatalog> =
        DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM, object : OkioSerializer<ExerciseCatalog> {
            override val defaultValue = ExerciseCatalogSerializer.defaultValue
            override suspend fun readFrom(source: BufferedSource) = ExerciseCatalogSerializer.readFrom(source.inputStream())
            override suspend fun writeTo(t: ExerciseCatalog, sink: BufferedSink) { ExerciseCatalogSerializer.writeTo(t, sink.outputStream()) }
        }) { file.absolutePath.toPath() }, scope = scope)

    private class MemoryStorage(var saved: WorkoutState = WorkoutState()) : WorkoutStorage {
        override suspend fun read() = saved
        override suspend fun write(state: WorkoutState) { saved = state }
    }

    /** Records one squat set in each of two sessions, then returns all archived sessions. */
    private suspend fun recordedSessions(): List<WorkoutState> {
        val repo = WorkoutRepository(MemoryStorage(), { time }, { "session-${++ids}" })
        repo.restore()
        listOf("week-one" to "day-one-a", "week-two" to "day-two-c").forEach { (w, d) ->
            val day = program.findDay(w, d)!!
            repo.load(day.day.toPlannedSets(), day.source)
            repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
            repo.completeSet()
            repo.dispatch(repo.state.value.command(WorkoutEvent.FinishWorkout))
        }
        repo.dispatch(repo.state.value.command(WorkoutEvent.ResetDemo))
        return repo.archived.value.sessions
    }

    @Test fun `sync registers referenced ids once without inferring attributes`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = CatalogRepository(store(folder.newFolder().resolve("exercises-v1.json"), scope))
        repo.restore()
        val refs = exerciseReferences(listOf(program), emptyList())
        assertEquals(4, repo.sync(refs))
        assertEquals(0, repo.sync(refs))
        val squat = repo.catalog.value.exercises.single { it.id == "squat" }
        assertEquals("Sentadilla ficticia", squat.name)
        assertNull(squat.movement); assertNull(squat.equipment); assertNull(squat.loadBasis); assertTrue(squat.aliases.isEmpty())
        // A reference under another name never renames the entry; it is kept as an alias.
        repo.sync(listOf(ExerciseReference("squat", "Back squat")))
        assertEquals("Sentadilla ficticia", repo.catalog.value.exercises.single { it.id == "squat" }.name)
        assertEquals(listOf("Back squat"), repo.catalog.value.exercises.single { it.id == "squat" }.aliases)
        scope.cancel(); scope.coroutineContext[Job]!!.join()
    }

    @Test fun `renaming keeps history by id and records the old name`() = runTest {
        val sessions = recordedSessions()
        val before = exerciseHistory("squat", sessions)
        assertEquals(2, before.size)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = folder.newFolder().resolve("exercises-v1.json")
        val repo = CatalogRepository(store(file, scope))
        repo.restore(); repo.sync(exerciseReferences(listOf(program), sessions))
        val squat = repo.catalog.value.exercises.single { it.id == "squat" }
        repo.update(squat.copy(name = "Sentadilla trasera", equipment = Equipment.BARBELL, loadBasis = LoadBasis.TOTAL, setup = "Pines en 7"))
        val renamed = repo.catalog.value.exercises.single { it.id == "squat" }
        assertEquals("Sentadilla trasera", renamed.name); assertEquals(listOf("Sentadilla ficticia"), renamed.aliases)
        assertEquals("Pines en 7", renamed.setup)
        // History is identical: matched by ID, still showing the name each set was recorded under.
        assertEquals(before, exerciseHistory("squat", sessions))
        assertTrue(exerciseHistory("squat", sessions).all { it.recordedName == "Sentadilla ficticia" })
        // Renaming back moves the label out of the aliases instead of duplicating it.
        repo.update(renamed.copy(name = "Sentadilla ficticia"))
        assertEquals(listOf("Sentadilla trasera"), repo.catalog.value.exercises.single { it.id == "squat" }.aliases)
        scope.cancel(); scope.coroutineContext[Job]!!.join()
        // Durable and restorable from the file.
        assertEquals(repo.catalog.value, ExerciseCatalogSerializer.readFrom(file.inputStream().use { ByteArrayInputStream(it.readBytes()) }))
    }

    @Test fun `variants are distinct exercises and indistinguishable labels are rejected`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = CatalogRepository(store(folder.newFolder().resolve("exercises-v1.json"), scope))
        repo.restore()
        repo.sync(listOf(ExerciseReference("bench-barbell", "Press banca"), ExerciseReference("bench-dumbbell", "Press banca")))
        assertEquals(setOf("bench-barbell", "bench-dumbbell"), CatalogCodec.ambiguous(repo.catalog.value))
        val barbell = repo.catalog.value.exercises.single { it.id == "bench-barbell" }
        val dumbbell = repo.catalog.value.exercises.single { it.id == "bench-dumbbell" }
        repo.update(barbell.copy(equipment = Equipment.BARBELL, movement = "press horizontal"))
        repo.update(dumbbell.copy(equipment = Equipment.DUMBBELL, movement = "press horizontal", loadBasis = LoadBasis.PER_HAND))
        assertTrue(CatalogCodec.ambiguous(repo.catalog.value).isEmpty())
        assertEquals(2, repo.catalog.value.exercises.count { it.movement == "press horizontal" })
        // Making them indistinguishable again is refused, leaving the catalog unchanged.
        val snapshot = repo.catalog.value
        try { repo.update(snapshot.exercises.single { it.id == "bench-dumbbell" }.copy(equipment = Equipment.BARBELL)); fail() }
        catch (_: CatalogException) { }
        assertEquals(snapshot, repo.catalog.value)
        scope.cancel(); scope.coroutineContext[Job]!!.join()
    }

    @Test fun `history never attributes another id or the demo to an exercise`() = runTest {
        val sessions = recordedSessions()
        assertTrue(exerciseHistory("dumbbell-row", sessions).isEmpty())
        val demo = WorkoutRepository(MemoryStorage(), { time }, { "demo" }).also {
            it.restore(); it.dispatch(it.state.value.command(WorkoutEvent.StartWorkout)); it.completeSet()
        }.state.value
        assertTrue(exerciseHistory("squat", listOf(demo)).isEmpty())
        assertTrue(exerciseReferences(emptyList(), listOf(demo)).isEmpty())
    }

    @Test fun `invalid entries and corrupt catalog files are rejected without reset`() {
        listOf(CatalogExercise("bad id", "X"), CatalogExercise("x", " "), CatalogExercise("x", "A", aliases = listOf("A")),
            CatalogExercise("x", "A", equipment = Equipment.BODYWEIGHT, loadBasis = LoadBasis.PER_HAND),
            CatalogExercise("x", "A", setup = "y".repeat(1001))).forEach { bad ->
            try { CatalogCodec.validate(bad); fail("accepted $bad") } catch (_: CatalogException) { }
        }
        val duplicate = """{"schemaVersion":1,"exercises":[{"id":"a","name":"A"},{"id":"a","name":"B"}]}"""
        try { kotlinx.coroutines.runBlocking { ExerciseCatalogSerializer.readFrom(ByteArrayInputStream(duplicate.toByteArray())) }; fail() }
        catch (_: CorruptionException) { }
    }
}
