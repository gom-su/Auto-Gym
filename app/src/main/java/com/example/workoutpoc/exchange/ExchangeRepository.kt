package com.example.workoutpoc.exchange

import androidx.datastore.core.*
import com.example.workoutpoc.planning.ProgramValidationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

@Serializable data class ExchangeArchive(val schemaVersion: Int, val packages: List<ExchangePackage>)
object ExchangeArchiveSerializer : Serializer<ExchangeArchive> {
    override val defaultValue = ExchangeArchive(1, emptyList())
    fun validate(value: ExchangeArchive) {
        if (value.schemaVersion != 1 || value.packages.map { it.importId }.distinct().size != value.packages.size)
            throw ProgramValidationException("Archivo de intercambios incompatible o con IDs duplicados.")
        value.packages.forEach { ExchangeCodec.encode(it) }
    }
    override suspend fun readFrom(input: InputStream): ExchangeArchive = try {
        ExchangeCodec.json.decodeFromString<ExchangeArchive>(input.readBytes().decodeToString(throwOnInvalidSequence = true)).also(::validate)
    } catch (e: IllegalArgumentException) { throw CorruptionException("No se pudo recuperar el archivo de intercambios. Se conserva sin reemplazar.", e) }
    override suspend fun writeTo(t: ExchangeArchive, output: OutputStream) {
        validate(t)
        output.write(ExchangeCodec.json.encodeToString(t).toByteArray(Charsets.UTF_8))
    }
}
class ExchangeRepository(private val store: DataStore<ExchangeArchive>) {
    private val mutex = Mutex()
    private val mutableArchive = MutableStateFlow(ExchangeArchiveSerializer.defaultValue)
    val archive = mutableArchive.asStateFlow()
    suspend fun restore() = mutex.withLock { mutableArchive.value = store.data.first() }
    /** A single atomic write; imports are archive entries, never approvals or executable state. */
    suspend fun save(data: ExchangePackage): Boolean = mutex.withLock {
        ExchangeCodec.encode(data)
        var added = false
        val saved = store.updateData { current ->
            ExchangeArchiveSerializer.validate(current)
            val existing = current.packages.find { it.importId == data.importId }
            when {
                existing == data -> current
                existing != null -> throw ProgramValidationException("El importId ya existe con contenido diferente. No se ha reemplazado.")
                else -> current.copy(packages = current.packages + data).also { added = true }
            }
        }
        mutableArchive.value = saved
        added
    }
}
