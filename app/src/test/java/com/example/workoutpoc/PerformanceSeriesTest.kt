package com.example.workoutpoc

import androidx.datastore.core.CorruptionException
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

/** ANA-02: charts distinguish deload weeks and never plot an omitted set as zero. */
class PerformanceSeriesTest {
    @get:Rule val folder = TemporaryFolder()
    private var time = ClockSnapshot(1_000, 1_800_000_000_000, 5)
    private var ids = 0
    private val program = ProgramCodec.decode(javaClass.classLoader!!.getResourceAsStream("examples/variable-program-v1.json")!!
        .bufferedReader().use { it.readText() })

    private class MemoryStorage(var saved: WorkoutState = WorkoutState()) : WorkoutStorage {
        override suspend fun read() = saved
        override suspend fun write(state: WorkoutState) { saved = state }
    }
    private suspend fun WorkoutRepository.go(e: WorkoutEvent) = dispatch(state.value.command(e))

    /** Three week-one sessions for squat (kg total): normal, all omitted, and lighter (to be marked deload). */
    private suspend fun sessions(): List<WorkoutState> {
        val repo = WorkoutRepository(MemoryStorage(), { time }, { "s-${++ids}" })
        repo.restore()
        val day = program.findDay("week-one", "day-one-a")!!
        suspend fun run(steps: suspend WorkoutRepository.() -> Unit) {
            time = time.copy(wallMillis = time.wallMillis + 86_400_000)
            repo.load(day.day.toPlannedSets(), day.source); repo.go(WorkoutEvent.StartWorkout); repo.steps(); repo.go(WorkoutEvent.FinishWorkout)
        }
        run { completeSet(); go(WorkoutEvent.SkipRest); completeSet() }
        run { go(WorkoutEvent.SkipSet); go(WorkoutEvent.SkipSet) }
        run { repeat(2) { go(WorkoutEvent.DecrementWeight) }; completeSet() }
        repo.go(WorkoutEvent.ResetDemo)
        return repo.archived.value.sessions
    }

    @Test fun `omitted sessions are gaps, never zero, and deload weeks are flagged`() = runTest {
        val group = exposures("squat", sessions()).single()
        val plain = chartSeries(group, ChartMetric.TOP_LOAD, WeekAnnotations())
        assertEquals(listOf(22.5, null, 15.0), plain.map { it.value })
        assertTrue(plain.none { it.deload })
        ChartMetric.entries.forEach { m -> assertNull("$m plots an omitted session", chartSeries(group, m, WeekAnnotations())[1].value) }
        assertEquals(listOf(8.0 + 6.0, null, 8.0), chartSeries(group, ChartMetric.TOTAL_REPS, WeekAnnotations()).map { it.value })
        assertEquals(listOf(20.0 * 8 + 22.5 * 6, null, 15.0 * 8), chartSeries(group, ChartMetric.VOLUME, WeekAnnotations()).map { it.value })

        // Marking week-one as deload flags every exposure from that week, without changing any value.
        val deload = WeekAnnotations(deloads = listOf(WeekRef(program.id, "week-one")))
        val marked = chartSeries(group, ChartMetric.TOP_LOAD, deload)
        assertTrue(marked.all { it.deload })
        assertEquals(plain.map { it.value }, marked.map { it.value })
        // Time order is preserved (oldest first).
        assertEquals(marked.map { it.startedAtMillis }.sorted(), marked.map { it.startedAtMillis })
    }

    @Test fun `deload marks are explicit, durable and validated`() = runTest {
        val file = folder.newFolder().resolve("week-annotations-v1.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM, object : OkioSerializer<WeekAnnotations> {
            override val defaultValue = WeekAnnotationsSerializer.defaultValue
            override suspend fun readFrom(source: BufferedSource) = WeekAnnotationsSerializer.readFrom(source.inputStream())
            override suspend fun writeTo(t: WeekAnnotations, sink: BufferedSink) { WeekAnnotationsSerializer.writeTo(t, sink.outputStream()) }
        }) { file.absolutePath.toPath() }, scope = scope)
        val repo = WeekAnnotationRepository(store)
        repo.restore()
        assertFalse(repo.annotations.value.isDeload(program.id, "week-two"))
        repo.setDeload(program.id, "week-two", true); repo.setDeload(program.id, "week-two", true)
        assertEquals(1, repo.annotations.value.deloads.size)
        assertTrue(repo.annotations.value.isDeload(program.id, "week-two"))
        assertFalse(repo.annotations.value.isDeload("other", "week-two"))
        assertFalse(repo.annotations.value.isDeload(null, null))
        scope.cancel(); scope.coroutineContext[Job]!!.join()
        val text = file.readText()
        assertEquals(1, WeekAnnotationsSerializer.readFrom(ByteArrayInputStream(text.toByteArray())).deloads.size)
        val duplicated = """{"schemaVersion":1,"deloads":[{"programId":"p","weekId":"w"},{"programId":"p","weekId":"w"}]}"""
        try { WeekAnnotationsSerializer.readFrom(ByteArrayInputStream(duplicated.toByteArray())); fail() } catch (_: CorruptionException) { }
    }
}
