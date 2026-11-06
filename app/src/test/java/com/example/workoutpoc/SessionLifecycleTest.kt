package com.example.workoutpoc

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.exchange.*
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

/** ENT-06: warm-ups, pauses, cardio and partial sessions; interruption resumes exactly where it stopped. */
class SessionLifecycleTest {
    @get:Rule val folder = TemporaryFolder()
    private var time = ClockSnapshot(1_000, 1_800_000_000_000, 5)
    private var ids = 0
    private val program = ProgramCodec.decode(javaClass.classLoader!!.getResourceAsStream("examples/variable-program-v1.json")!!
        .bufferedReader().use { it.readText() })

    private class MemoryStorage(var saved: WorkoutState = WorkoutState()) : WorkoutStorage {
        override suspend fun read() = saved
        override suspend fun write(state: WorkoutState) { saved = state }
    }
    private fun file(file: File, scope: CoroutineScope): DataStore<WorkoutState> =
        DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM, object : OkioSerializer<WorkoutState> {
            override val defaultValue = WorkoutSerializer.defaultValue
            override suspend fun readFrom(source: BufferedSource) = WorkoutSerializer.readFrom(source.inputStream())
            override suspend fun writeTo(t: WorkoutState, sink: BufferedSink) { WorkoutSerializer.writeTo(t, sink.outputStream()) }
        }) { file.absolutePath.toPath() }, scope = scope)

    private suspend fun repo(storage: WorkoutStorage, week: String = "week-one", day: String = "day-one-a"): WorkoutRepository {
        val repo = WorkoutRepository(storage, { time }, { "session-${++ids}" })
        repo.restore()
        val selection = program.findDay(week, day)!!
        repo.load(selection.day.toPlannedSets(), selection.source)
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        return repo
    }
    private suspend fun WorkoutRepository.go(e: WorkoutEvent) = dispatch(state.value.command(e))
    private fun cardio(id: String, minutes: Int? = 20) = CardioEntry(id, " Bicicleta ", minutes?.let { it * 60 }, 85, DistanceUnit.KM, " suave ", time.wallMillis)

    @Test fun `interrupted paused session resumes exactly with cardio previous sets and remaining rest`() = runTest {
        val path = folder.newFolder().resolve("workout.json")
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = repo(DataStoreWorkoutStorage(file(path, scope1)))
        repo.go(WorkoutEvent.CompleteSet) // 90 s rest begins
        val s0 = repo.state.value
        assertTrue(repo.addCardio(s0.sessionId, s0.revision, cardio("cardio-1")))
        time = time.copy(elapsedMillis = time.elapsedMillis + 30_000, wallMillis = time.wallMillis + 30_000)
        repo.go(WorkoutEvent.PauseSession)
        val paused = repo.state.value
        scope1.cancel(); scope1.coroutineContext[Job]!!.join()

        // Process death during a long pause: restore is exact and the rest timer does not run out meanwhile.
        time = time.copy(elapsedMillis = time.elapsedMillis + 600_000, wallMillis = time.wallMillis + 600_000)
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val restored = WorkoutRepository(DataStoreWorkoutStorage(file(path, scope2)), { time })
        restored.restore()
        assertEquals(paused, restored.state.value)
        assertEquals(Phase.Resting, restored.state.value.phase)
        assertEquals("Bicicleta", restored.state.value.cardio.single().modality)
        assertEquals("suave", restored.state.value.cardio.single().notes)
        // Stale taps while paused are inert.
        restored.go(WorkoutEvent.SkipRest)
        assertEquals(paused, restored.state.value)
        restored.go(WorkoutEvent.ResumeSession)
        val resumed = restored.state.value
        assertNull(resumed.paused); assertEquals(600_000L, resumed.pausedMillis)
        assertEquals(60_000L, resumed.rest!!.remainingMillis(time))
        assertEquals(30_000L, resumed.activeMillis(time))
        assertEquals(s0.records, resumed.records)
        scope2.cancel(); scope2.coroutineContext[Job]!!.join()
    }

    @Test fun `pause survives a reboot using wall time`() = runTest {
        val repo = repo(MemoryStorage())
        repo.go(WorkoutEvent.PauseSession)
        time = ClockSnapshot(500, time.wallMillis + 120_000, 6)
        repo.go(WorkoutEvent.ResumeSession)
        assertEquals(120_000L, repo.state.value.pausedMillis)
    }

    @Test fun `warm-up precedes the work set, is unprescribed and never carries its load`() = runTest {
        val repo = repo(MemoryStorage())
        repo.go(WorkoutEvent.AddWarmupSet)
        var s = repo.state.value
        assertTrue(s.plan[0].warmup); assertNull(s.plan[0].prescriptionId); assertEquals(3, s.plan.size)
        repeat(4) { repo.go(WorkoutEvent.DecrementWeight) }
        repo.go(WorkoutEvent.CompleteSet)
        repo.go(WorkoutEvent.SkipRest)
        s = repo.state.value
        assertEquals("set-one-squat-1", s.plan[s.index].prescriptionId)
        assertEquals(SetValues(200, 8, 3), s.values)
        assertEquals(100, s.records[0].values.weightTenths)
        // During rest a warm-up is inserted before the next set too.
        repo.go(WorkoutEvent.CompleteSet)
        repo.go(WorkoutEvent.AddWarmupSet)
        s = repo.state.value
        assertEquals(Phase.Resting, s.phase); assertTrue(s.plan[2].warmup); assertEquals(2, s.plan[2].number)
        repo.go(WorkoutEvent.SkipRest)
        assertTrue(repo.state.value.plan[repo.state.value.index].warmup)
        repo.go(WorkoutEvent.RemoveExtraSet)
        assertEquals("set-one-squat-2", repo.state.value.plan[repo.state.value.index].prescriptionId)
    }

    @Test fun `cardio validation rejects invented or out of range data and removal is stale safe`() = runTest {
        val repo = repo(MemoryStorage())
        val s = repo.state.value
        listOf(cardio("x").copy(modality = " "), cardio("x").copy(distanceUnit = null),
            cardio("x", null).copy(distanceTenths = null, distanceUnit = null, notes = null),
            cardio("x").copy(durationSeconds = -1), cardio("bad id")).forEach { bad ->
            try { repo.addCardio(s.sessionId, s.revision, bad); fail("accepted $bad") } catch (_: WorkoutEditException) { }
        }
        assertEquals(s, repo.state.value)
        assertTrue(repo.addCardio(s.sessionId, s.revision, cardio("c1")))
        assertFalse(repo.addCardio(s.sessionId, s.revision, cardio("c2"))) // stale revision
        val s1 = repo.state.value
        assertFalse(repo.removeCardio(s1.sessionId, s1.revision - 1, "c1"))
        assertTrue(repo.removeCardio(s1.sessionId, s1.revision, "c1"))
        assertTrue(repo.state.value.cardio.isEmpty())
    }

    @Test fun `demo cannot pause, warm up or log cardio`() = runTest {
        val repo = WorkoutRepository(MemoryStorage(), { time }, { "demo-${++ids}" })
        repo.restore()
        repo.go(WorkoutEvent.StartWorkout)
        val s = repo.state.value
        repo.go(WorkoutEvent.PauseSession); repo.go(WorkoutEvent.AddWarmupSet)
        assertFalse(repo.addCardio(s.sessionId, s.revision, cardio("c")))
        assertEquals(s, repo.state.value)
    }

    @Test fun `finishing or closing while paused accounts for the pause and keeps everything`() = runTest {
        val repo = repo(MemoryStorage())
        repo.go(WorkoutEvent.CompleteSet)
        val s = repo.state.value
        repo.addCardio(s.sessionId, s.revision, cardio("c1"))
        repo.go(WorkoutEvent.PauseSession)
        time = time.copy(elapsedMillis = time.elapsedMillis + 45_000, wallMillis = time.wallMillis + 45_000)
        repo.go(WorkoutEvent.ResetDemo)
        val archived = repo.archived.value.sessions.single()
        assertNull(archived.paused); assertEquals(45_000L, archived.pausedMillis)
        assertEquals(1, archived.records.size); assertEquals(1, archived.cardio.size)
        assertTrue(archived.endedEarly)

        val partial = repo(MemoryStorage())
        partial.go(WorkoutEvent.PauseSession)
        time = time.copy(elapsedMillis = time.elapsedMillis + 5_000, wallMillis = time.wallMillis + 5_000)
        partial.go(WorkoutEvent.FinishWorkout)
        assertEquals(Phase.Completed, partial.state.value.phase); assertTrue(partial.state.value.endedEarly)
        assertEquals(5_000L, partial.state.value.pausedMillis)
    }

    @Test fun `cardio warm-ups and pauses export as valid v1 results`() = runTest {
        val repo = repo(MemoryStorage())
        repo.go(WorkoutEvent.AddWarmupSet)
        repo.go(WorkoutEvent.CompleteSet)
        val s = repo.state.value
        repo.addCardio(s.sessionId, s.revision, cardio("cardio-1"))
        repo.go(WorkoutEvent.PauseSession)
        time = time.copy(elapsedMillis = time.elapsedMillis + 10_000, wallMillis = time.wallMillis + 10_000)
        repo.go(WorkoutEvent.FinishWorkout)
        val result = repo.state.value.toSessionResult()!!
        assertEquals(PerformanceStatus.PARTIAL, result.status)
        assertTrue(result.sets[0].notes!!.contains("aproximación")); assertNull(result.sets[0].prescriptionId)
        val c = result.cardio.single()
        assertEquals("cardio-1", c.id); assertEquals(1200, c.durationSeconds); assertEquals(8.5, c.distance!!, 0.0); assertEquals("KM", c.distanceUnit)
        assertTrue(c.notes!!.contains("Bicicleta") && c.notes!!.contains("suave"))
        assertTrue(result.notes!!.contains("Pausas: 10 s"))
        val pkg = ExchangePackage(1, ExchangeType.TrainingResults, "results", "2026-11-06T12:00:00Z", results = TrainingResults(listOf(result)))
        assertEquals(pkg, ExchangeCodec.decode(ExchangeCodec.encode(pkg)))
    }
}
