package com.example.workoutpoc.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.util.UUID

internal class BackupExporter(private val context: Context, private val backups: BackupRepository) {
    private val automaticLock = Mutex()
    private val mutableAutomaticError = MutableStateFlow<String?>(null)
    val automaticError = mutableAutomaticError.asStateFlow()

    suspend fun encode(createdAt: Instant = Instant.now()): String {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val app = BackupApp(info.versionName ?: "desconocida", PackageInfoCompat.getLongVersionCode(info).toInt())
        return BackupCodec.encode(backups.snapshot(), createdAt, app)
    }

    fun writeDocument(uri: Uri, text: String) {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")
        stream.use { it.write(text.encodeToByteArray()) }
    }

    suspend fun exportAutomatically() = withContext(Dispatchers.IO) {
        automaticLock.withLock {
            try {
                val bytes = encode().encodeToByteArray()
                mutableAutomaticError.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    replaceInDownloads(bytes)
                } else {
                    replaceInAppDownloads(bytes)
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AutoGymBackup", "No se pudo exportar el respaldo automático", e)
                mutableAutomaticError.value = "Falló el último respaldo automático. La sesión quedó guardada. Puedes exportar una copia desde aquí."
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun replaceInDownloads(bytes: ByteArray): String? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val path = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"
        val previous = resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                "(${MediaStore.MediaColumns.DISPLAY_NAME} = ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)",
            arrayOf(path, context.packageName, FILE_NAME, "$STAGING_PREFIX%.json"), null)?.use { rows ->
                buildList<Uri> { while (rows.moveToNext()) add(ContentUris.withAppendedId(collection, rows.getLong(0))) }
            } ?: throw IOException("No se pudo consultar la carpeta de respaldos")
        val metadata = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$STAGING_PREFIX${UUID.randomUUID()}.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, metadata)
            ?: throw IOException("No se pudo crear el archivo en Descargas")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw IOException("No se pudo escribir el respaldo")
            val written = resolver.openInputStream(uri) ?: throw IOException("No se pudo releer el respaldo")
            verifyContents(written, bytes)
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, published, null, null) != 1) throw IOException("No se pudo publicar el respaldo")
        } catch (failure: Exception) {
            try { resolver.delete(uri, null, null) }
            catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
        // MediaStore cannot atomically replace a document. A verified, published copy must exist before removing its predecessor.
        return try {
            for (old in previous) resolver.delete(old, null, null)
            val finalName = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME) }
            if (resolver.update(uri, finalName, null, null) != 1) throw IOException("No se pudo renombrar el respaldo")
            null
        } catch (failure: Exception) {
            Log.w("AutoGymBackup", "El respaldo nuevo es válido, pero quedó con su nombre provisional", failure)
            "El respaldo nuevo se guardó en Descargas → Auto-Gym con un nombre provisional."
        }
    }

    private fun replaceInAppDownloads(bytes: ByteArray) {
        val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("El almacenamiento externo de la app no está disponible")
        val directory = File(downloads, FOLDER)
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("No se pudo crear la carpeta de respaldos")
        val staged = File.createTempFile(".respaldo-", ".tmp", directory)
        try {
            staged.outputStream().use {
                it.write(bytes)
                it.fd.sync()
            }
            verifyContents(staged.inputStream(), bytes)
            Os.rename(staged.absolutePath, File(directory, FILE_NAME).absolutePath)
        } finally {
            staged.delete()
        }
    }

    private fun verifyContents(input: InputStream, expected: ByteArray) {
        input.use {
            val buffer = ByteArray(8192)
            var offset = 0
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                if (count > expected.size - offset || (0 until count).any { buffer[it] != expected[offset + it] }) {
                    throw IOException("El respaldo guardado no coincide con el JSON exportado")
                }
                offset += count
            }
            if (offset != expected.size) throw IOException("El respaldo guardado está incompleto")
        }
    }

    private companion object {
        const val FOLDER = "Auto-Gym"
        const val FILE_NAME = "auto-gym-respaldo-automatico.json"
        const val STAGING_PREFIX = "auto-gym-respaldo-automatico-"
    }
}
