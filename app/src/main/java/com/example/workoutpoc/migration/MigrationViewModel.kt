package com.example.workoutpoc.migration

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.workoutpoc.workoutApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

data class MigrationUiState(
    val ready: Boolean = false, val busy: Boolean = false, val preview: MigrationDocument? = null,
    val selectedId: String? = null, val message: String? = null
)

class MigrationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = application.workoutApp.migrations
    val archive = repository.archive
    private val mutableUi = MutableStateFlow(MigrationUiState())
    val ui = mutableUi.asStateFlow()
    init { restore() }

    fun restore() = operation {
        repository.restore()
        mutableUi.value = ui.value.copy(ready = true)
    }
    fun previewText(text: String) = operation { prepare(text, "Texto pegado") }
    fun previewFile(uri: Uri) = operation {
        val (text, name) = withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }?.take(1000)?.takeIf { it.isNotBlank() } ?: "Archivo Markdown"
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= MigrationCodec.MAX_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MigrationCodec.MAX_BYTES + 1 - output.size()))
                    if (count == -1) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
                ?: throw MigrationException("No se pudo abrir el archivo seleccionado.")
            if (bytes.size > MigrationCodec.MAX_BYTES) throw MigrationException("El archivo supera 1 MiB.")
            val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
                catch (_: java.nio.charset.CharacterCodingException) { throw MigrationException("El archivo no es UTF-8 válido.") }
            text to name
        }
        prepare(text, name)
    }
    private suspend fun prepare(text: String, source: String) {
        // Invalidate earlier review before parsing a replacement; an error cannot save stale content.
        mutableUi.value = ui.value.copy(preview = null, selectedId = null)
        val document = withContext(Dispatchers.Default) { ObsidianParser.parse(text, source) }
        mutableUi.value = ui.value.copy(preview = document)
    }
    fun select(id: String?) {
        if (!ui.value.busy) mutableUi.value = ui.value.copy(selectedId = id, preview = null, message = null)
    }
    fun cancel() { if (!ui.value.busy) mutableUi.value = ui.value.copy(preview = null, selectedId = null, message = null) }
    fun save() = operation {
        val document = ui.value.preview ?: return@operation
        val added = repository.save(document)
        mutableUi.value = ui.value.copy(preview = null, selectedId = document.id,
            message = if (added) "Migración guardada en este dispositivo." else "Esta migración ya estaba guardada. Se conserva su revisión sin duplicados.")
    }
    fun acknowledge(issueId: String, value: Boolean) = operation {
        val preview = ui.value.preview
        if (preview != null) mutableUi.value = ui.value.copy(preview = preview.copy(
            issues = preview.issues.map { if (it.id == issueId) it.copy(acknowledged = value) else it }))
        else ui.value.selectedId?.let { repository.acknowledge(it, issueId, value) }
    }
    private fun operation(block: suspend () -> Unit) {
        if (ui.value.busy) return
        mutableUi.value = ui.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { withContext(Dispatchers.Default) { block() } }
            catch (e: CancellationException) { throw e }
            catch (e: MigrationException) { mutableUi.value = ui.value.copy(message = e.message) }
            catch (_: Exception) { mutableUi.value = ui.value.copy(message = "No se pudo leer o guardar la migración. Tus datos se conservan. Reintenta.") }
            finally { mutableUi.value = ui.value.copy(busy = false) }
        }
    }
}
