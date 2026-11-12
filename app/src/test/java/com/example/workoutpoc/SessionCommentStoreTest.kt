package com.example.workoutpoc

import com.example.workoutpoc.backup.*
import com.example.workoutpoc.domain.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Contract §7: comment storage, its backup section, and a backup from before the section existed. */
class SessionCommentStoreTest {
    private val date = Instant.parse("2026-11-11T12:00:00Z")
    private val app = BackupApp("1.0.0", 1)

    private fun comment(id: String, exerciseId: String? = "row", resolved: Long? = null) = SessionComment(
        id = id, text = "En este remo prefiero el agarre neutro", createdAtMillis = 1_000, programId = "prog",
        weekId = "week-one", sessionId = "s1", exerciseId = exerciseId, resolvedAtMillis = resolved)

    @Test fun `validation rejects duplicate identities and blank text`() {
        val duplicated = SessionCommentLog(comments = listOf(comment("a"), comment("a")))
        assertTrue(runCatching { SessionCommentLogSerializer.validate(duplicated) }.isFailure)
        val blank = SessionCommentLog(comments = listOf(comment("b").copy(text = "   ")))
        assertTrue(runCatching { SessionCommentLogSerializer.validate(blank) }.isFailure)
        SessionCommentLogSerializer.validate(SessionCommentLog(comments = listOf(comment("ok"))))
    }

    @Test fun `a backup with comments round trips and keeps resolution`() = runTest {
        val data = BackupData(comments = SessionCommentLog(comments = listOf(comment("a"), comment("b", resolved = 5_000))))
        val text = BackupCodec.encode(data, date, app)
        assertEquals(data.comments, BackupCodec.decode(text).data.comments)
    }

    @Test fun `a backup written before the comments store restores with an empty log`() = runTest {
        val stores = JsonObject(BackupCodec.sections.filter { it.key != "comments" }.associate { section ->
            section.key to section.encode(BackupData())
        })
        val envelope = buildJsonObject {
            put("format", BackupCodec.FORMAT)
            put("backupVersion", 1)
            put("createdAt", date.toString())
            putJsonObject("app") { put("versionName", app.versionName); put("versionCode", app.versionCode) }
            put("sha256", BackupCodec.digest(stores))
            put("stores", stores)
        }.toString()
        val restored = BackupCodec.decode(envelope).data
        assertEquals(SessionCommentLog(), restored.comments)
    }
}

class SessionCommentRepositoryTest {
    @get:org.junit.Rule val folder = org.junit.rules.TemporaryFolder()

    @Test fun `added comments survive restore and resolution removes them from the agent context`() = kotlinx.coroutines.test.runTest {
        val file = folder.newFolder().resolve("session-comments-v1.json")
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val store = androidx.datastore.core.DataStoreFactory.create(storage = androidx.datastore.core.okio.OkioStorage(
            okio.FileSystem.SYSTEM, object : androidx.datastore.core.okio.OkioSerializer<SessionCommentLog> {
                override val defaultValue = SessionCommentLogSerializer.defaultValue
                override suspend fun readFrom(source: okio.BufferedSource) = SessionCommentLogSerializer.readFrom(source.inputStream())
                override suspend fun writeTo(t: SessionCommentLog, sink: okio.BufferedSink) = SessionCommentLogSerializer.writeTo(t, sink.outputStream())
            }) { file.absolutePath.toPath() }, scope = scope)
        val repo = com.example.workoutpoc.data.SessionCommentRepository(store)
        repo.restore()
        repo.add(SessionComment("c1", "En este remo prefiero el agarre neutro", 10, "prog", "week-one", "s1", "row"))
        repo.add(SessionComment("c2", "Calentamiento largo", 20, "prog", "week-one", "s1", null))
        assertEquals(2, repo.log.value.comments.size)

        repo.resolve("c2", 30)
        repo.restore()
        val inForce = com.example.workoutpoc.domain.SessionComments.forGeneration(repo.log.value.comments, "prog", setOf("row"))
        assertEquals(listOf("c1"), inForce.map { it.id })
        scope.cancel()
    }
}
