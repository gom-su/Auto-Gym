package com.example.workoutpoc.data

import androidx.datastore.core.DataStore
import com.example.workoutpoc.backup.BackupStore
import com.example.workoutpoc.domain.SessionComment
import com.example.workoutpoc.domain.SessionCommentLog
import com.example.workoutpoc.domain.SessionCommentLogSerializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Saved comments (contract §7). Comments are stored apart from weeks, so replacing a week never removes them. */
class SessionCommentRepository(private val store: DataStore<SessionCommentLog>) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(SessionCommentLog())
    val log = mutable.asStateFlow()

    suspend fun restore() = mutex.withLock { mutable.value = store.data.first() }

    suspend fun add(comment: SessionComment) = mutex.withLock {
        mutable.value = store.updateData { l -> l.copy(comments = l.comments + comment).also(SessionCommentLogSerializer::validate) }
    }

    suspend fun removeProgram(programId: String): Int = mutex.withLock {
        val current = store.data.first()
        val kept = current.comments.filterNot { it.programId == programId }
        val removed = current.comments.size - kept.size
        if (removed > 0) replaceAllLocked(current.copy(comments = kept))
        removed
    }

    /** Marks a comment as no longer in force. Resolving a resolved comment changes nothing. */
    suspend fun resolve(id: String, atMillis: Long) = mutex.withLock {
        mutable.value = store.updateData { l ->
            require(l.comments.any { it.id == id }) { "Comentario desconocido." }
            l.copy(comments = l.comments.map { if (it.id == id && it.resolvedAtMillis == null) it.copy(resolvedAtMillis = atMillis) else it })
        }
    }

    /** Replaces the whole log with a restored backup. The serializer validates it first. */
    suspend fun replaceAll(log: SessionCommentLog) = mutex.withLock { replaceAllLocked(log) }
    private suspend fun replaceAllLocked(log: SessionCommentLog) {
        SessionCommentLogSerializer.validate(log)
        mutable.value = store.updateData { log }
    }

    internal val backupStore get() = BackupStore(mutex,
        { it.copy(comments = store.data.first()) }, { replaceAllLocked(it.comments) })
}
