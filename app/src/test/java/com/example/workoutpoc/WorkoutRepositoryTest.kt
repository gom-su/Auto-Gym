package com.example.workoutpoc

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.core.okio.OkioSerializer
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.BufferedSink
import okio.BufferedSource
import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.File

class WorkoutRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private var time = ClockSnapshot(1_000, 100_000, 5)
    // File.renameTo cannot replace an existing file on Windows (Android issue 203087070).
    // Use DataStore's supported Okio backend for host JVM tests. Instrumented tests use
    // the production Android FileStorage backend without this adapter.
    private fun fileStore(file: File, scope: CoroutineScope): DataStore<WorkoutState> =
        DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM, object : OkioSerializer<WorkoutState> {
            override val defaultValue: WorkoutState = WorkoutSerializer.defaultValue
            override suspend fun readFrom(source: BufferedSource): WorkoutState = WorkoutSerializer.readFrom(source.inputStream())
            override suspend fun writeTo(t: WorkoutState, sink: BufferedSink) { WorkoutSerializer.writeTo(t, sink.outputStream()) }
        }) { file.absolutePath.toPath() }, scope = scope)
    private class MemoryStorage(var saved: WorkoutState = WorkoutState()) : WorkoutStorage {
        var fail: Boolean = false
        override suspend fun read(): WorkoutState = saved
        override suspend fun write(state: WorkoutState) { if (fail) throw IOException("disk full"); saved = state }
    }

    @Test fun `concurrent complete is serialized and durable`() = runTest {
        val storage = MemoryStorage()
        val repo = WorkoutRepository(storage, { time }, { "session" })
        repo.restore()
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        val command = repo.state.value.command(WorkoutEvent.CompleteSet)
        coroutineScope { repeat(30) { launch { repo.dispatch(command) } } }
        assertEquals(1, repo.state.value.records.size)
        assertEquals(repo.state.value, storage.saved)
    }

    @Test fun `failed write never publishes unsaved state`() = runTest {
        val storage = MemoryStorage()
        val repo = WorkoutRepository(storage, { time })
        repo.restore()
        val before = repo.state.value
        storage.fail = true
        try { repo.dispatch(before.command(WorkoutEvent.StartWorkout)); fail("Expected IO error") }
        catch (_: IOException) { }
        assertEquals(before, repo.state.value)
        storage.fail = false
        repo.dispatch(before.command(WorkoutEvent.StartWorkout))
        assertEquals(Phase.ActiveSet, repo.state.value.phase)
    }

    @Test fun `real DataStore file restores edits records and deadline across instances`() = runTest {
        val file = folder.newFolder().resolve("workout.json")
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store1 = fileStore(file, scope1)
        val repo = WorkoutRepository(DataStoreWorkoutStorage(store1), { time }, { "session" })
        repo.restore()
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        repo.dispatch(repo.state.value.command(WorkoutEvent.IncrementWeight))
        repo.dispatch(repo.state.value.command(WorkoutEvent.SelectRir, 1))
        val active = repo.state.value
        scope1.cancel(); scope1.coroutineContext[Job]!!.join()

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store2 = fileStore(file, scope2)
        val restored = WorkoutRepository(DataStoreWorkoutStorage(store2), { time })
        restored.restore()
        assertEquals(active, restored.state.value)
        restored.dispatch(restored.state.value.command(WorkoutEvent.CompleteSet))
        val rest = restored.state.value
        scope2.cancel(); scope2.coroutineContext[Job]!!.join()

        val scope3 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store3 = fileStore(file, scope3)
        val restoredRest = WorkoutRepository(DataStoreWorkoutStorage(store3), { time })
        restoredRest.restore()
        assertEquals(rest, restoredRest.state.value)
        assertEquals(1, restoredRest.state.value.records.size)
        time = time.copy(elapsedMillis = 12_000)
        restoredRest.reconcileRest()
        assertEquals(Phase.ActiveSet, restoredRest.state.value.phase)
        assertEquals(1, restoredRest.state.value.index)
        assertEquals(325, restoredRest.state.value.values.weightTenths)
        scope3.cancel(); scope3.coroutineContext[Job]!!.join()
    }

    @Test fun `expired persisted rest is reconciled during restore`() = runTest {
        val storage = MemoryStorage()
        val repo = WorkoutRepository(storage, { time }, { "session" })
        repo.restore()
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        repo.dispatch(repo.state.value.command(WorkoutEvent.CompleteSet))
        time = time.copy(elapsedMillis = 11_001)
        val recreated = WorkoutRepository(storage, { time })
        recreated.restore()
        assertEquals(Phase.ActiveSet, recreated.state.value.phase)
        assertEquals(1, recreated.state.value.index)
        assertEquals(1, recreated.state.value.records.size)
    }
}
