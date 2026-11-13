package com.example.workoutpoc.domain

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** Every comment the user saved, kept outside weeks and their records (contract §7). */
@Serializable data class SessionCommentLog(val schemaVersion: Int = 1, val comments: List<SessionComment> = emptyList())

object SessionCommentLogSerializer : Serializer<SessionCommentLog> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val identity = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    override val defaultValue = SessionCommentLog()

    fun validate(log: SessionCommentLog) {
        require(log.schemaVersion == 1) { "Versión de comentarios no compatible." }
        require(log.comments.map { it.id }.distinct().size == log.comments.size) { "Comentarios duplicados." }
        require(log.comments.all { c ->
            c.id.matches(identity) && c.programId.matches(identity) &&
                (c.weekId == null || c.weekId.matches(identity)) && (c.sessionId == null || c.sessionId.matches(identity)) &&
                (c.exerciseId == null || c.exerciseId.matches(identity)) &&
                (c.dayId == null || c.dayId.matches(identity)) && (c.setIndex == null || c.setIndex in 0..10_000) &&
                c.text.isNotBlank() && c.text.length <= MAX_NOTE_LENGTH && c.createdAtMillis >= 0 &&
                (c.setIndex == null || c.setIndex >= 0)
        }) { "Identificador o texto de comentario no válido." }
    }

    override suspend fun readFrom(input: InputStream): SessionCommentLog = try {
        json.decodeFromString<SessionCommentLog>(input.readBytes().decodeToString()).also(::validate)
    } catch (e: SerializationException) { throw CorruptionException("Comentarios ilegibles. Se conservan sin reemplazar.", e) }
      catch (e: IllegalArgumentException) { throw CorruptionException("Comentarios inválidos. Se conservan sin reemplazar.", e) }

    override suspend fun writeTo(t: SessionCommentLog, output: OutputStream) {
        validate(t)
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}
