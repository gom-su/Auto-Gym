package com.example.workoutpoc.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The API key encrypted with AES-256-GCM under a key that never leaves the Android Keystore. The ciphertext is one
 * file in the no-backup directory: version byte, IV length, IV, ciphertext. Uninstalling the app removes both.
 * [name] exists so device tests use their own file and Keystore entry and never touch a real key.
 */
class KeystoreApiKeyVault(context: Context, name: String = "claude-api-key") : ApiKeyVault {
    private val file = File(context.noBackupFilesDir, "$name.bin")
    private val alias = "auto-gym-$name"

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun secret(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    override fun read(): String? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        require(bytes.size > 2 && bytes[0] == VERSION) { "Formato de llave no compatible." }
        val ivSize = bytes[1].toInt()
        require(ivSize in 12..16 && bytes.size > 2 + ivSize) { "Llave guardada incompleta." }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes, 2, ivSize))
        }
        return cipher.doFinal(bytes, 2 + ivSize, bytes.size - 2 - ivSize).decodeToString()
    }

    override fun write(key: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secret()) }
        val body = cipher.doFinal(key.encodeToByteArray())
        val iv = cipher.iv
        // Written whole to a sibling and renamed, so a crash never leaves half a key behind.
        val pending = File(file.parentFile, file.name + ".tmp")
        pending.writeBytes(byteArrayOf(VERSION, iv.size.toByte()) + iv + body)
        if (!pending.renameTo(file)) { pending.delete(); error("No se pudo reemplazar la llave guardada.") }
    }

    override fun clear() {
        file.delete()
        keyStore().run { if (containsAlias(alias)) deleteEntry(alias) }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
    }
}
