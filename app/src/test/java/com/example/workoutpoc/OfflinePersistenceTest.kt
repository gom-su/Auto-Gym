package com.example.workoutpoc

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
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

/** ENT-07: offline by construction, and confirmed data is never replaced by a reset or an unread file. */
class OfflinePersistenceTest {
    @get:Rule val folder = TemporaryFolder()
    private val time = ClockSnapshot(1_000, 1_800_000_000_000, 5)

    private fun <T> store(file: File, scope: CoroutineScope, serializer: androidx.datastore.core.Serializer<T>): DataStore<T> =
        DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM, object : OkioSerializer<T> {
            override val defaultValue: T = serializer.defaultValue
            override suspend fun readFrom(source: BufferedSource): T = serializer.readFrom(source.inputStream())
            override suspend fun writeTo(t: T, sink: BufferedSink) { serializer.writeTo(t, sink.outputStream()) }
        }) { file.absolutePath.toPath() }, scope = scope)

    @Test fun `app declares no network permission so recording cannot depend on connectivity`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val permissions = Regex("uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertTrue(permissions.isNotEmpty())
        listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.ACCESS_WIFI_STATE")
            .forEach { assertFalse("$it must not be requested", it in permissions) }
        assertTrue("Cloud backup must stay disabled for local-only data", manifest.contains("android:allowBackup=\"false\""))
    }

    @Test fun `a corrupt session file is reported and never overwritten or reset`() = runTest {
        val file = folder.newFolder().resolve("workout.json")
        val corrupt = "{\"sessionId\":\"s\",\"phase\":\"ActiveSet\",\"index\":99"
        file.writeText(corrupt)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = WorkoutRepository(DataStoreWorkoutStorage(store(file, scope, WorkoutSerializer)), { time })
        try { repo.restore(); fail("Expected corruption") } catch (_: CorruptionException) { }
        try { repo.dispatch(WorkoutState().command(WorkoutEvent.StartWorkout)); fail("Expected corruption") } catch (_: CorruptionException) { }
        assertFalse(repo.ready.value)
        assertEquals(corrupt, file.readText())
        scope.cancel(); scope.coroutineContext[Job]!!.join()
    }

    @Test fun `a corrupt history file blocks archiving instead of replacing it`() = runTest {
        val dir = folder.newFolder()
        val historyFile = dir.resolve("history-v1.json").apply { writeText("{\"schemaVersion\":2,\"sessions\":[]}") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = WorkoutRepository(DataStoreWorkoutStorage(store(dir.resolve("workout.json"), scope, WorkoutSerializer)), { time },
            history = DataStoreSessionHistory(store(historyFile, scope, WorkoutHistorySerializer)))
        try { repo.restore(); fail("Expected corruption") } catch (_: CorruptionException) { }
        assertEquals("{\"schemaVersion\":2,\"sessions\":[]}", historyFile.readText())
        scope.cancel(); scope.coroutineContext[Job]!!.join()
    }

    @Test fun `every confirmed set is on disk when it is published`() = runTest {
        val file = folder.newFolder().resolve("workout.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = WorkoutRepository(DataStoreWorkoutStorage(store(file, scope, WorkoutSerializer)), { time }, { "s" })
        repo.restore()
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        repeat(3) {
            repo.dispatch(repo.state.value.command(WorkoutEvent.CompleteSet))
            // Read the file independently of the repository, as a fresh process would.
            val onDisk = file.inputStream().use { WorkoutSerializer.readFrom(it) }
            assertEquals(repo.state.value, onDisk)
            repo.dispatch(repo.state.value.command(WorkoutEvent.SkipRest))
        }
        scope.cancel(); scope.coroutineContext[Job]!!.join()
    }
}
