package com.example.workoutpoc.planning

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.workoutpoc.workoutApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class ProgramUiState(
    val ready: Boolean = false, val busy: Boolean = false,
    val preview: TrainingProgram? = null, val message: String? = null,
    /** Outcome of saving the preview against the library, computed before the user decides. */
    val merge: MergePlan? = null
)

class ProgramViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = application.workoutApp.programs
    val library = repository.library
    val annotations = application.workoutApp.weekAnnotations.annotations
    private val mutableUi = MutableStateFlow(ProgramUiState())
    val ui = mutableUi.asStateFlow()
    init { restore() }

    fun restore() = operation {
        repository.restore()
        getApplication<Application>().workoutApp.weekAnnotations.restore()
        mutableUi.value = mutableUi.value.copy(ready = true, message = null)
    }
    fun previewText(text: String) = operation { prepare(text) }
    fun previewFile(uri: Uri) = operation {
        val text = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= ProgramCodec.MAX_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, ProgramCodec.MAX_BYTES + 1 - output.size()))
                    if (count == -1) break
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                if (bytes.size > ProgramCodec.MAX_BYTES) throw ProgramValidationException("El archivo supera 1 MiB.")
                bytes.toString(Charsets.UTF_8)
            } ?: throw ProgramValidationException("No se pudo abrir el archivo seleccionado.")
        }
        prepare(text)
    }
    private suspend fun prepare(text: String) {
        val program = withContext(Dispatchers.Default) { ProgramCodec.decode(text) }
        repository.restore()
        mutableUi.value = mutableUi.value.copy(preview = program, merge = repository.preview(program), message = null)
    }
    /** User-declared deload context for a saved week; never inferred and never changes the prescription. */
    fun setDeload(programId: String, weekId: String, deload: Boolean) = operation {
        getApplication<Application>().workoutApp.weekAnnotations.setDeload(programId, weekId, deload)
    }
    fun showMessage(text: String) { mutableUi.value = mutableUi.value.copy(message = text) }
    fun cancelPreview() { if (!ui.value.busy) mutableUi.value = mutableUi.value.copy(preview = null, merge = null, message = null) }
    fun save() = operation {
        val program = ui.value.preview ?: return@operation
        val result = repository.importProgram(program)
        val merge = ui.value.merge
        mutableUi.value = mutableUi.value.copy(preview = null, merge = null, message = when (result) {
            ImportResult.SAVED -> "Programa guardado en este dispositivo. No se ha activado ninguna semana."
            ImportResult.ALREADY_IMPORTED -> "Este programa ya estaba importado. No se crearon duplicados."
            ImportResult.MERGED -> "Semanas nuevas añadidas al programa guardado: ${merge?.addedWeeks?.joinToString(", ").orEmpty()}. " +
                "Las semanas existentes no se duplicaron ni cambiaron. No se ha activado ninguna semana."
        })
    }
    private fun operation(block: suspend () -> Unit) {
        if (ui.value.busy) return
        mutableUi.value = mutableUi.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: ProgramValidationException) { mutableUi.value = mutableUi.value.copy(message = e.message) }
            catch (_: Exception) { mutableUi.value = mutableUi.value.copy(message = "No se pudo leer o guardar el programa. Tus datos se conservan. Reintenta.") }
            finally { mutableUi.value = mutableUi.value.copy(busy = false) }
        }
    }
}
