package com.example.workoutpoc.planning

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

object ProgramLibrarySerializer : Serializer<ProgramLibrary> {
    override val defaultValue = ProgramLibrary(1, emptyList())
    override suspend fun readFrom(input: InputStream): ProgramLibrary = try {
        ProgramCodec.json.decodeFromString<ProgramLibrary>(input.readBytes().decodeToString()).also(ProgramCodec::validateLibrary)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("La biblioteca de programas no es válida. No se ha reemplazado.", e)
    }
    override suspend fun writeTo(t: ProgramLibrary, output: OutputStream) {
        ProgramCodec.validateLibrary(t)
        output.write(ProgramCodec.json.encodeToString(t).toByteArray(Charsets.UTF_8))
    }
}

enum class ImportResult { SAVED, ALREADY_IMPORTED }

class ProgramRepository(private val store: DataStore<ProgramLibrary>) {
    private val mutex = Mutex()
    private val mutableLibrary = MutableStateFlow(ProgramLibrary(1, emptyList()))
    val library = mutableLibrary.asStateFlow()

    suspend fun restore() = mutex.withLock { mutableLibrary.value = store.data.first() }

    /** No replacement of an existing prescription and no activation of imported content. */
    suspend fun importProgram(program: TrainingProgram): ImportResult = mutex.withLock {
        ProgramCodec.validate(program)
        var result = ImportResult.SAVED
        val saved = store.updateData { current ->
            val existing = current.programs.find { it.id == program.id }
            when {
                existing == program -> { result = ImportResult.ALREADY_IMPORTED; current }
                existing != null -> throw ProgramValidationException("Ya existe un programa con ID '${program.id}' y contenido diferente. No se ha reemplazado.")
                else -> current.copy(programs = current.programs + program)
            }
        }
        mutableLibrary.value = saved
        result
    }
}
