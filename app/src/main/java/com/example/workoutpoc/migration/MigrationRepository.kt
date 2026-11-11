package com.example.workoutpoc.migration

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream

object MigrationArchiveSerializer : Serializer<MigrationArchive> {
    override val defaultValue = MigrationArchive()
    override suspend fun readFrom(input: InputStream): MigrationArchive = try {
        MigrationCodec.json.decodeFromString<MigrationArchive>(input.readBytes().decodeToString(throwOnInvalidSequence = true))
            .also(MigrationCodec::validate)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("No se pudo recuperar el archivo de migraciones. Se conserva sin reemplazar.", e)
    } catch (e: java.nio.charset.CharacterCodingException) {
        throw CorruptionException("El archivo de migraciones no es UTF-8 válido. Se conserva sin reemplazar.", e)
    }
    override suspend fun writeTo(t: MigrationArchive, output: OutputStream) {
        MigrationCodec.validate(t)
        output.write(MigrationCodec.json.encodeToString(t).toByteArray(Charsets.UTF_8))
    }
}

class MigrationRepository(private val store: DataStore<MigrationArchive>) {
    private val mutex = Mutex()
    private val mutableArchive = MutableStateFlow(MigrationArchive())
    val archive = mutableArchive.asStateFlow()
    suspend fun restore() = mutex.withLock { mutableArchive.value = store.data.first() }
    /** Replaces the whole store with a restored backup (see docs/FULL_BACKUP.md). The serializer validates it. */
    suspend fun replaceAll(archive: MigrationArchive) = mutex.withLock { replaceAllLocked(archive) }
    private suspend fun replaceAllLocked(archive: MigrationArchive) { MigrationCodec.validate(archive); mutableArchive.value = store.updateData { archive } }
    internal val backupStore get() = com.example.workoutpoc.backup.BackupStore(mutex,
        { it.copy(migrations = store.data.first()) }, { replaceAllLocked(it.migrations) })
    suspend fun save(document: MigrationDocument): Boolean = mutex.withLock {
        MigrationCodec.validate(document)
        var added = false
        val saved = store.updateData { current ->
            MigrationCodec.validate(current)
            val existing = current.documents.find { it.id == document.id }
            if (existing == null) current.copy(documents = current.documents + document).also { added = true }
            else {
                if (existing.original != document.original || existing.parserVersion != document.parserVersion)
                    throw MigrationException("Identidad en conflicto. Se conserva la migración existente.")
                current // Existing source and review are authoritative, even when a new picker filename differs.
            }
        }
        mutableArchive.value = saved
        added
    }
    suspend fun acknowledge(documentId: String, issueId: String, acknowledged: Boolean) = mutex.withLock {
        val saved = store.updateData { current ->
            MigrationCodec.validate(current)
            val document = current.documents.find { it.id == documentId } ?: throw MigrationException("Migración no encontrada.")
            if (document.issues.none { it.id == issueId }) throw MigrationException("Advertencia no encontrada.")
            current.copy(documents = current.documents.map { d ->
                if (d.id != documentId) d else d.copy(issues = d.issues.map { if (it.id == issueId) it.copy(acknowledged = acknowledged) else it })
            })
        }
        mutableArchive.value = saved
    }
}
