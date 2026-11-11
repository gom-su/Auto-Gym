package com.example.workoutpoc.settings

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

/** Where sets are logged while another app is in front. A session can still switch from its own screen. */
@Serializable enum class LoggingMode(val label: String) { NOTIFICATION("Notificación"), OVERLAY("Panel flotante") }

@Serializable data class AppSettings(val schemaVersion: Int = 1, val loggingMode: LoggingMode = LoggingMode.NOTIFICATION)

object AppSettingsSerializer : Serializer<AppSettings> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    override val defaultValue = AppSettings()
    override suspend fun readFrom(input: InputStream): AppSettings = try {
        json.decodeFromString<AppSettings>(input.readBytes().decodeToString()).also { require(it.schemaVersion == 1) { "Versión no compatible." } }
    } catch (e: SerializationException) { throw CorruptionException("Ajustes ilegibles. Se conservan sin reemplazar.", e) }
      catch (e: IllegalArgumentException) { throw CorruptionException("Ajustes inválidos. Se conservan sin reemplazar.", e) }
    override suspend fun writeTo(t: AppSettings, output: OutputStream) { output.write(json.encodeToString(t).encodeToByteArray()) }
}

class AppSettingsRepository(private val store: DataStore<AppSettings>) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(AppSettings())
    val settings = mutable.asStateFlow()
    suspend fun restore() = mutex.withLock { mutable.value = store.data.first() }
    /** Replaces the whole store with a restored backup (see docs/FULL_BACKUP.md). The serializer validates it. */
    suspend fun replaceAll(settings: AppSettings) = mutex.withLock { replaceAllLocked(settings) }
    private suspend fun replaceAllLocked(settings: AppSettings) { require(settings.schemaVersion == 1) { "Versión no compatible." }; mutable.value = store.updateData { settings } }
    internal val backupStore get() = com.example.workoutpoc.backup.BackupStore(mutex,
        { it.copy(settings = store.data.first()) }, { replaceAllLocked(it.settings) })
    suspend fun setLoggingMode(mode: LoggingMode) = mutex.withLock { mutable.value = store.updateData { it.copy(loggingMode = mode) } }
}
