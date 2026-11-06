package com.example.workoutpoc.exchange

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.workoutpoc.planning.ProgramValidationException
import com.example.workoutpoc.workoutApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.util.UUID

data class ExchangeUiState(val ready: Boolean = false, val busy: Boolean = false,
    val preview: ExchangePackage? = null, val message: String? = null,
    /** Key ID of a verified signed envelope being previewed (INT-02), with its original text. */
    val signedBy: String? = null, val envelope: String? = null)
class ExchangeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.workoutApp
    private val repository = app.exchanges
    val archive = repository.archive
    private val mutableUi = MutableStateFlow(ExchangeUiState())
    val ui = mutableUi.asStateFlow()
    private var pendingExport: String? = null
    init { restore() }
    fun restore() = operation { repository.restore(); mutableUi.value = mutableUi.value.copy(ready = true) }
    val syncState = app.sync.state
    fun previewText(text: String) = operation { prepare(text) }

    /** Plain packages are the user's own import; signed envelopes must verify against a paired key first (INT-02). */
    private suspend fun prepare(text: String) {
        if (com.example.workoutpoc.sync.SignedSync.isEnvelope(text)) {
            app.sync.restore()
            when (val v = app.sync.check(text)) {
                is com.example.workoutpoc.sync.Verification.Accepted ->
                    mutableUi.value = mutableUi.value.copy(preview = v.pkg, signedBy = v.envelope.keyId, envelope = text)
                is com.example.workoutpoc.sync.Verification.Rejected -> throw ProgramValidationException("Envío firmado rechazado: ${v.reason} No se guardó nada.")
            }
        } else {
            val data = withContext(Dispatchers.Default) { ExchangeCodec.decode(text) }
            mutableUi.value = mutableUi.value.copy(preview = data, signedBy = null, envelope = null)
        }
    }

    fun pair() = operation {
        app.sync.restore()
        val key = app.sync.pair()
        mutableUi.value = mutableUi.value.copy(message = "Clave ${key.keyId} creada. Cópiala en la configuración del servidor (--sign-key).")
    }
    fun revoke(keyId: String) = operation {
        app.sync.revoke(keyId)
        mutableUi.value = mutableUi.value.copy(message = "Clave $keyId revocada: sus envíos se rechazarán.")
    }
    fun copyKey(text: String) {
        val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Clave Auto-Gym", text).apply {
            description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        })
        mutableUi.value = mutableUi.value.copy(message = "Clave copiada al portapapeles (marcada como sensible).")
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
        prepare(text)
    }
    fun select(data: ExchangePackage) { if (!ui.value.busy) mutableUi.value = mutableUi.value.copy(preview = data, message = null, signedBy = null, envelope = null) }
    fun cancel() { if (!ui.value.busy) mutableUi.value = mutableUi.value.copy(preview = null, message = null, signedBy = null, envelope = null) }
    fun save() = operation {
        ui.value.envelope?.let { envelope ->
            // Re-verify and record the nonce atomically; a replay or revoked key is rejected even after preview.
            when (val v = app.sync.accept(envelope) { repository.save(it) }) {
                is com.example.workoutpoc.sync.Verification.Accepted -> mutableUi.value = mutableUi.value.copy(preview = null, signedBy = null, envelope = null,
                    message = "Envío firmado por ${v.envelope.keyId} guardado. La propuesta queda pendiente de tu aprobación.")
                is com.example.workoutpoc.sync.Verification.Rejected -> throw ProgramValidationException("Envío firmado rechazado: ${v.reason} No se guardó nada.")
            }
            return@operation
        }
        val data = ui.value.preview ?: return@operation
        val added = repository.save(data)
        mutableUi.value = mutableUi.value.copy(preview = null, message = if (added)
            "Intercambio guardado. Ninguna propuesta se ha aprobado o activado; la sesión actual se conserva."
            else "Este intercambio ya estaba guardado. No se crearon duplicados.")
    }
    fun generate(type: ExchangeType) = operation {
        app.programs.restore(); app.repository.restore()
        val state = app.repository.state.value
        // Archived and current planned sessions are typed v1 results; the demo keeps its frozen legacy snapshot.
        val sessions = (app.repository.archived.value.sessions + state).distinctBy { it.sessionId }.mapNotNull { it.toSessionResult() }
        val results = TrainingResults(sessions, state.toDemoV1()?.let { DemoSnapshot(it) })
        val data = ExchangePackage(1, type, UUID.randomUUID().toString(), Instant.now().toString(),
            context = if (type == ExchangeType.TrainingContext) planningContext().let { (phase, goals, rules) ->
                TrainingContext(app.programs.library.value.programs, phase, results, rules, goals) } else null,
            results = if (type == ExchangeType.TrainingResults) results else null)
        ExchangeCodec.encode(data)
        mutableUi.value = mutableUi.value.copy(preview = data, message = "Revisa el JSON antes de compartir. El contexto local no incluye mediciones corporales. La demo se identifica como tal.")
    }
    /** Phase, goals and rules come only from the user's answers to the planning questions (AG-01); never invented. */
    private suspend fun planningContext(): Triple<String?, List<String>, List<String>> {
        app.catalog.restore(); app.planningAnswers.restore()
        return com.example.workoutpoc.questions.PlanningQuestions.context(
            com.example.workoutpoc.questions.PlanningQuestions.questions(app.catalog.catalog.value), app.planningAnswers.answers.value)
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
