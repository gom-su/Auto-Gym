package com.example.workoutpoc.planning

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

@Serializable data class WeekRef(val programId: String, val weekId: String)

/**
 * Local, user-declared context for saved weeks. Program v1 has no deload field, and deloads are never inferred
 * from names or loads: a week is a deload only when the user marks it.
 */
@Serializable data class WeekAnnotations(val schemaVersion: Int = 1, val deloads: List<WeekRef> = emptyList()) {
    fun isDeload(programId: String?, weekId: String?) = programId != null && weekId != null && WeekRef(programId, weekId) in deloads
}

object WeekAnnotationsSerializer : Serializer<WeekAnnotations> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    override val defaultValue = WeekAnnotations()
    fun validate(a: WeekAnnotations) {
        require(a.schemaVersion == 1) { "Versión no compatible." }
        require(a.deloads.distinct().size == a.deloads.size) { "Semanas duplicadas." }
        val id = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        require(a.deloads.all { it.programId.matches(id) && it.weekId.matches(id) }) { "Identificador no válido." }
    }
    override suspend fun readFrom(input: InputStream): WeekAnnotations = try {
        json.decodeFromString<WeekAnnotations>(input.readBytes().decodeToString()).also(::validate)
    } catch (e: SerializationException) { throw CorruptionException("Anotaciones ilegibles. Se conservan sin reemplazar.", e) }
      catch (e: IllegalArgumentException) { throw CorruptionException("Anotaciones inválidas. Se conservan sin reemplazar.", e) }
    override suspend fun writeTo(t: WeekAnnotations, output: OutputStream) { validate(t); output.write(json.encodeToString(t).encodeToByteArray()) }
}

class WeekAnnotationRepository(private val store: DataStore<WeekAnnotations>) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(WeekAnnotations())
    val annotations = mutable.asStateFlow()
    suspend fun restore() = mutex.withLock { mutable.value = store.data.first() }
    suspend fun setDeload(programId: String, weekId: String, deload: Boolean) = mutex.withLock {
        val ref = WeekRef(programId, weekId)
        mutable.value = store.updateData { a ->
            if (deload == (ref in a.deloads)) a
            else a.copy(deloads = if (deload) a.deloads + ref else a.deloads - ref)
        }
    }
}
