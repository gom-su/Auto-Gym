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

enum class ImportResult { SAVED, ALREADY_IMPORTED, MERGED }

class ProgramRepository(private val store: DataStore<ProgramLibrary>) {
    private val mutex = Mutex()
    private val mutableLibrary = MutableStateFlow(ProgramLibrary(1, emptyList()))
    val library = mutableLibrary.asStateFlow()

    suspend fun restore() = mutex.withLock { mutableLibrary.value = store.data.first() }

    /** Replaces the whole store with a restored backup (see docs/FULL_BACKUP.md). The serializer validates it. */
    suspend fun replaceAll(library: ProgramLibrary) = mutex.withLock { replaceAllLocked(library) }
    private suspend fun replaceAllLocked(library: ProgramLibrary) { ProgramCodec.validateLibrary(library); mutableLibrary.value = store.updateData { library } }
    internal val backupStore get() = com.example.workoutpoc.backup.BackupStore(mutex,
        { it.copy(programs = store.data.first()) }, { replaceAllLocked(it.programs) })

    /**
     * Replaces the saved content of one program with [program]. Only an explicit approval of a week replacement
     * (contract §2) uses it. Returns the library as it was, so the caller can restore it if a later step fails.
     */
    suspend fun replaceProgram(program: TrainingProgram): ProgramLibrary = mutex.withLock {
        ProgramCodec.validate(program)
        val before = store.data.first()
        val programs = if (before.programs.any { it.id == program.id }) before.programs.map { if (it.id == program.id) program else it }
            else before.programs + program
        replaceAllLocked(before.copy(programs = programs))
        before
    }

    /** What saving [program] would do against the current library, without writing anything. */
    fun preview(program: TrainingProgram): MergePlan = ProgramMerge.plan(library.value.programs.find { it.id == program.id }, program)

    /**
     * Idempotent import: identical content is a no-op, weeks with new IDs are appended, and nothing saved is ever
     * replaced or deleted. Any difference in an existing element is a conflict listed by path; nothing is written.
     * No imported content is activated.
     */
    suspend fun importProgram(program: TrainingProgram): ImportResult = mutex.withLock {
        ProgramCodec.validate(program)
        var result = ImportResult.SAVED
        val saved = store.updateData { current ->
            val plan = ProgramMerge.plan(current.programs.find { it.id == program.id }, program)
            when (plan.kind) {
                MergeKind.NEW -> current.copy(programs = current.programs + program)
                MergeKind.IDENTICAL -> { result = ImportResult.ALREADY_IMPORTED; current }
                MergeKind.ADDS_WEEKS -> { result = ImportResult.MERGED
                    current.copy(programs = current.programs.map { if (it.id == program.id) plan.program else it }) }
                MergeKind.CONFLICT -> throw ProgramValidationException(plan.summary())
            }
        }
        mutableLibrary.value = saved
        result
    }
}
