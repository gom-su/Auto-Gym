package com.example.workoutpoc.agent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A failure of the in-app agent, already worded for the user. */
class AgentException(message: String) : Exception(message)

/** Where the secret lives. On the device it is encrypted with a Keystore key ([KeystoreApiKeyVault]). */
interface ApiKeyVault {
    fun read(): String?
    fun write(key: String)
    fun clear()
}

/** What the screens may know about the key: whether there is one, and its last characters. Never the key itself. */
data class ApiKeyStatus(val present: Boolean = false, val hint: String? = null)

object ApiKeys {
    /** The key as it will be stored, or the reason it cannot be one. Only obvious mistakes are refused. */
    fun clean(raw: String): String {
        val key = raw.trim()
        fun refuse(message: String): Nothing = throw AgentException(message)
        if (key.isEmpty()) refuse("Pega tu llave API de Claude.")
        if (key.startsWith("agk1.")) refuse("Esa es la clave de emparejamiento del servidor MCP, no una llave API de Claude. No se guardó.")
        if (key.startsWith("sk-ant-admin")) refuse("Esa es una llave de administración. Usa una llave API normal. No se guardó.")
        if (key.length !in 20..512 || key.any { it.code !in 33..126 })
            refuse("La llave no es válida: cópiala completa, sin espacios ni saltos de línea.")
        return key
    }

    fun hint(key: String) = "…" + key.takeLast(4)
}

/**
 * The user's Claude API key. It stays on the device, encrypted, outside every export and backup, and is read only to
 * call the API when the user asks for a week.
 */
class ApiKeyRepository(private val vault: ApiKeyVault, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(ApiKeyStatus())
    val status = mutable.asStateFlow()

    suspend fun restore() = mutex.withLock {
        mutable.value = ApiKeyStatus()
        mutable.value = load()?.let { ApiKeyStatus(true, ApiKeys.hint(it)) } ?: ApiKeyStatus()
    }

    suspend fun key(): String? = mutex.withLock { load() }

    suspend fun save(raw: String) = mutex.withLock {
        val key = ApiKeys.clean(raw)
        try { withContext(io) { vault.write(key) } }
        catch (e: Exception) { throw AgentException("No se pudo guardar la llave en este dispositivo.") }
        mutable.value = ApiKeyStatus(true, ApiKeys.hint(key))
    }

    suspend fun clear() = mutex.withLock {
        withContext(io) { vault.clear() }
        mutable.value = ApiKeyStatus()
    }

    private suspend fun load(): String? = try { withContext(io) { vault.read() } }
        catch (e: Exception) { throw AgentException("La llave guardada no se pudo leer. Vuelve a pegarla.") }
}
