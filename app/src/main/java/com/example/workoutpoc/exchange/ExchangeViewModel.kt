package com.example.workoutpoc.exchange

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.workoutpoc.domain.Phase
import com.example.workoutpoc.planning.ProgramValidationException
import com.example.workoutpoc.workoutApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.util.UUID

data class ExchangeUiState(val ready: Boolean = false, val busy: Boolean = false,
    val preview: ExchangePackage? = null, val message: String? = null)
class ExchangeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.workoutApp
    private val repository = app.exchanges
    val archive = repository.archive
    private val mutableUi = MutableStateFlow(ExchangeUiState())
    val ui = mutableUi.asStateFlow()
    private var pendingExport: String? = null
    init { restore() }
    fun restore() = operation { repository.restore(); mutableUi.value = mutableUi.value.copy(ready = true) }
    fun previewText(text: String) = operation {
        val data = withContext(Dispatchers.Default) { ExchangeCodec.decode(text) }
        mutableUi.value = mutableUi.value.copy(preview = data)
    }
    fun previewFile(uri: Uri) = operation {
        val text = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use {
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= ExchangeCodec.MAX_BYTES) {
                    val count = it.read(buffer, 0, minOf(buffer.size, ExchangeCodec.MAX_BYTES + 1 - output.size()))
                    if (count == -1) break
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                if (bytes.size > ExchangeCodec.MAX_BYTES) throw ProgramValidationException("El archivo supera 1 MiB.")
                try { bytes.decodeToString(throwOnInvalidSequence = true) }
                catch (_: Exception) { throw ProgramValidationException("El archivo debe estar codificado en UTF-8.") }
            } ?: throw ProgramValidationException("No se pudo abrir el archivo.")
        }
        mutableUi.value = mutableUi.value.copy(preview = withContext(Dispatchers.Default) { ExchangeCodec.decode(text) })
    }
    fun select(data: ExchangePackage) { if (!ui.value.busy) mutableUi.value = mutableUi.value.copy(preview = data, message = null) }
    fun cancel() { if (!ui.value.busy) mutableUi.value = mutableUi.value.copy(preview = null, message = null) }
    fun save() = operation {
        val data = ui.value.preview ?: return@operation
        val added = repository.save(data)
        mutableUi.value = mutableUi.value.copy(preview = null, message = if (added)
            "Intercambio guardado. Ninguna propuesta se ha aprobado o activado; la sesión actual se conserva."
            else "Este intercambio ya estaba guardado. No se crearon duplicados.")
    }
    fun generate(type: ExchangeType) = operation {
        app.programs.restore(); app.repository.restore()
        val state = app.repository.state.value
        val results = TrainingResults(emptyList(), if (state.phase == Phase.Idle) null else DemoSnapshot(state))
        val data = ExchangePackage(1, type, UUID.randomUUID().toString(), Instant.now().toString(),
            context = if (type == ExchangeType.TrainingContext) TrainingContext(app.programs.library.value.programs,
                null, results, emptyList(), emptyList()) else null,
            results = if (type == ExchangeType.TrainingResults) results else null)
        ExchangeCodec.encode(data)
        mutableUi.value = mutableUi.value.copy(preview = data, message = "Revisa el JSON antes de compartir. El contexto local no incluye mediciones corporales. La demo se identifica como tal.")
    }
    fun stageExport(includeMeasurements: Boolean): Boolean {
        val data = ui.value.preview ?: return false
        return try { pendingExport = exportText(data, includeMeasurements); true }
        catch (e: ProgramValidationException) { mutableUi.value = mutableUi.value.copy(message = e.message); false }
    }
    fun copy(includeMeasurements: Boolean) = operation {
        val data = ui.value.preview ?: return@operation
        val text = withContext(Dispatchers.Default) { exportText(data, includeMeasurements) }
        app.getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText("Auto-Gym JSON", text))
        mutableUi.value = mutableUi.value.copy(message = "JSON copiado al portapapeles del sistema.")
    }
    private fun exportText(data: ExchangePackage, includeMeasurements: Boolean) = ExchangeCodec.encode(
        ExchangePrivacy.forExport(data, includeMeasurements))
    fun write(uri: Uri?) {
        val text = pendingExport
        pendingExport = null
        if (uri == null) return
        operation {
            if (text == null) throw ProgramValidationException("La exportación se interrumpió. Selecciona el intercambio y vuelve a exportar.")
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: throw ProgramValidationException("No se pudo abrir el destino.")
            }
            mutableUi.value = mutableUi.value.copy(message = "JSON exportado correctamente.")
        }
    }
    private fun operation(block: suspend () -> Unit) {
        if (ui.value.busy) return
        mutableUi.value = mutableUi.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: ProgramValidationException) { mutableUi.value = mutableUi.value.copy(message = e.message) }
            catch (_: Exception) { mutableUi.value = mutableUi.value.copy(message = "No se pudo completar el intercambio. Reintenta. Si falló una exportación, el archivo de destino puede estar incompleto.") }
            finally { mutableUi.value = mutableUi.value.copy(busy = false) }
        }
    }
}
