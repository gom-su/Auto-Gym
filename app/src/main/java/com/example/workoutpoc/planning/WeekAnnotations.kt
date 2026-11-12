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
@Serializable data class DayRef(val programId: String, val weekId: String, val dayId: String)

/** Configurable weekly targets; null means no goal set. Never defaulted to a value the user did not choose. */
@Serializable data class Goals(val sessionsPerWeek: Int? = null, val cardioMinutesPerWeek: Int? = null) {
    fun validate() {
        require(sessionsPerWeek == null || sessionsPerWeek in 1..14) { "Sesiones por semana entre 1 y 14." }
        require(cardioMinutesPerWeek == null || cardioMinutesPerWeek in 1..3000) { "Minutos de cardio entre 1 y 3000." }
    }
}

/**
 * Local, user-declared context for saved weeks. Program v1 has no deload field, and deloads are never inferred
 * from names or loads: a week is a deload only when the user marks it.
 */
@Serializable data class WeekAnnotations(
    val schemaVersion: Int = 1, val deloads: List<WeekRef> = emptyList(),
    /** Planned days the user explicitly declared as not performed (distinct from simply having no record). */
    val notPerformed: List<DayRef> = emptyList(),
    val goals: Goals = Goals()
) {
    fun isDeload(programId: String?, weekId: String?) = programId != null && weekId != null && WeekRef(programId, weekId) in deloads
    fun isNotPerformed(programId: String, weekId: String, dayId: String) = DayRef(programId, weekId, dayId) in notPerformed
}

object WeekAnnotationsSerializer : Serializer<WeekAnnotations> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    override val defaultValue = WeekAnnotations()
    fun validate(a: WeekAnnotations) {
        require(a.schemaVersion == 1) { "Versión no compatible." }
        require(a.deloads.distinct().size == a.deloads.size) { "Semanas duplicadas." }
        val id = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        require(a.deloads.all { it.programId.matches(id) && it.weekId.matches(id) }) { "Identificador no válido." }
        require(a.notPerformed.distinct().size == a.notPerformed.size) { "Días duplicados." }
        require(a.notPerformed.all { it.programId.matches(id) && it.weekId.matches(id) && it.dayId.matches(id) }) { "Identificador no válido." }
        a.goals.validate()
    }
    override suspend fun readFrom(input: InputStream): WeekAnnotations = try {
        json.decodeFromString<WeekAnnotations>(input.readBytes().decodeToString()).also(::validate)
    } catch (e: SerializationException) { throw CorruptionException("Anotaciones ilegibles. Se conservan sin reemplazar.", e) }
      catch (e: IllegalArgumentException) { throw CorruptionException("Anotaciones inválidas. Se conservan sin reemplazar.", e) }
    override suspend fun writeTo(t: WeekAnnotations, output: OutputStream) { validate(t); output.write(json.encodeToString(t).encodeToByteArray()) }
}

class WeekAnnotationRepository(private val store: DataStore<WeekAnnotations>) {
    suspend fun setNotPerformed(day: DayRef, value: Boolean) = mutex.withLock {
        mutable.value = store.updateData { a ->
            if (value == (day in a.notPerformed)) a else a.copy(notPerformed = if (value) a.notPerformed + day else a.notPerformed - day)
        }
    }
    suspend fun setGoals(goals: Goals) = mutex.withLock {
        goals.validate()
        mutable.value = store.updateData { it.copy(goals = goals) }
    }
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(WeekAnnotations())
    val annotations = mutable.asStateFlow()
    suspend fun restore() = mutex.withLock { mutable.value = store.data.first() }
    /** Replaces the whole store with a restored backup (see docs/FULL_BACKUP.md). The serializer validates it. */
    suspend fun replaceAll(annotations: WeekAnnotations) = mutex.withLock { replaceAllLocked(annotations) }
    private suspend fun replaceAllLocked(annotations: WeekAnnotations) { WeekAnnotationsSerializer.validate(annotations); mutable.value = store.updateData { annotations } }
    internal val backupStore get() = com.example.workoutpoc.backup.BackupStore(mutex,
        { it.copy(weekAnnotations = store.data.first()) }, { replaceAllLocked(it.weekAnnotations) })
    /** Returns the total of removed deload and not-performed annotations. */
    suspend fun removeProgram(programId: String): Int = mutex.withLock {
        val current = store.data.first()
        val deloads = current.deloads.filterNot { it.programId == programId }
        val notPerformed = current.notPerformed.filterNot { it.programId == programId }
        val removed = current.deloads.size - deloads.size + current.notPerformed.size - notPerformed.size
        if (removed > 0) replaceAllLocked(current.copy(deloads = deloads, notPerformed = notPerformed))
        removed
    }

    suspend fun setDeload(programId: String, weekId: String, deload: Boolean) = mutex.withLock {
        val ref = WeekRef(programId, weekId)
        mutable.value = store.updateData { a ->
            if (deload == (ref in a.deloads)) a
            else a.copy(deloads = if (deload) a.deloads + ref else a.deloads - ref)
        }
    }
}
