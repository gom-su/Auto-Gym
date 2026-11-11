package com.example.workoutpoc.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.workoutpoc.workoutApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate

/** The selected backup and the current device are immutable until a new review is requested. */
data class BackupReview(val backup: Backup, val current: BackupData)
data class BackupUiState(val busy: Boolean = false, val message: String? = null, val review: BackupReview? = null,
                         val restoreGeneration: Int = 0)

class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.workoutApp
    private val mutableUi = MutableStateFlow(BackupUiState())
    val ui = mutableUi.asStateFlow()
    private var pendingExport: String? = null

    private fun runOperation(block: suspend () -> Unit) {
        if (ui.value.busy) return
        mutableUi.value = mutableUi.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutableUi.value = mutableUi.value.copy(message = (e as? BackupException)?.message
                    ?: "No se pudo leer o escribir el respaldo. Tus datos locales se conservan. Reintenta.")
            } finally { mutableUi.value = mutableUi.value.copy(busy = false) }
        }
    }

    fun prepareExport(onReady: (String) -> Unit) = runOperation {
        pendingExport = null
        val text = withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            BackupCodec.encode(app.backups.snapshot(), Instant.now(), BackupApp(info.versionName ?: "desconocida", androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info).toInt()))
        }
        pendingExport = text
        onReady("auto-gym-respaldo-${LocalDate.now()}.json")
    }

    fun writeExport(uri: Uri?) {
        val text = pendingExport
        pendingExport = null
        if (uri == null) return
        runOperation {
            if (text == null) throw BackupException("No se pudo recuperar el respaldo preparado. Vuelve a pulsar Exportar respaldo completo.")
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.encodeToByteArray()) }
                    ?: throw java.io.IOException("No output stream")
            }
            mutableUi.value = mutableUi.value.copy(message = "Respaldo completo exportado correctamente.")
        }
    }

    fun preview(uri: Uri) {
        if (ui.value.busy) return
        mutableUi.value = mutableUi.value.copy(review = null)
        runOperation {
            val review = withContext(Dispatchers.IO) {
                val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size().toLong() + count > BackupCodec.MAX_BYTES)
                            throw BackupException("El archivo supera 64 MiB. No se reemplazó nada.")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: throw java.io.IOException("No input stream")
                BackupReview(BackupCodec.decodeBytes(bytes), app.backups.snapshot())
            }
            mutableUi.value = mutableUi.value.copy(review = review)
        }
    }

    fun cancelReview() { if (!ui.value.busy) mutableUi.value = mutableUi.value.copy(review = null, message = null) }

    fun confirmRestore() {
        val review = ui.value.review ?: return
        runOperation {
            withContext(Dispatchers.IO) { app.backups.restore(review.backup.data, review.current) }
            mutableUi.value = mutableUi.value.copy(review = null, message = "Respaldo restaurado correctamente.",
                restoreGeneration = ui.value.restoreGeneration + 1)
        }
    }
}
