package com.example.workoutpoc.sync

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.example.workoutpoc.exchange.ExchangeCodec
import com.example.workoutpoc.exchange.ExchangePackage
import com.example.workoutpoc.exchange.ExchangeType
import com.example.workoutpoc.planning.ProgramValidationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signed delivery of an interchange v1 package from an authorized remote party (e.g. the MCP server). The payload is the
 * unchanged v1 JSON; the MAC covers every field, so nothing can be altered, replayed or redirected without detection.
 */
@Serializable data class SyncEnvelope(
    val syncVersion: Int, val keyId: String, val nonce: String, val issuedAt: String,
    val payloadType: String, val payload: String, val mac: String
)

/** A pairing key shared with one remote party. The secret never leaves the device except through the user's copy. */
@Serializable data class PairedKey(val keyId: String, val secret: String, val createdAtMillis: Long, val revoked: Boolean = false)
@Serializable data class SeenNonce(val keyId: String, val nonce: String, val issuedAtMillis: Long)
@Serializable data class SyncState(val schemaVersion: Int = 1, val keys: List<PairedKey> = emptyList(), val seen: List<SeenNonce> = emptyList())

sealed interface Verification {
    data class Accepted(val envelope: SyncEnvelope, val pkg: ExchangePackage) : Verification
    data class Rejected(val reason: String) : Verification
}

object SignedSync {
    const val VERSION = 1
    /** Only proposals may arrive from outside; they still need explicit approval in the app. */
    val ALLOWED_TYPES = setOf(ExchangeType.WeekProposal.name)
    const val MAX_AGE_MILLIS = 7 * 86_400_000L
    const val MAX_SKEW_MILLIS = 5 * 60_000L
    private val json = Json { ignoreUnknownKeys = false }
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

    fun isEnvelope(text: String): Boolean = runCatching { Json.parseToJsonElement(text).jsonObject.containsKey("syncVersion") }.getOrDefault(false)

    fun signingInput(e: SyncEnvelope) = listOf("auto-gym-sync-v1", e.keyId, e.nonce, e.issuedAt, e.payloadType, e.payload).joinToString("\n")

    fun mac(secret: ByteArray, input: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256")); doFinal(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** "agk1.<keyId>.<base64url secret>": the single string the user copies into the remote party's configuration. */
    fun shareable(k: PairedKey) = "agk1.${k.keyId}.${k.secret}"

    fun newKey(nowMillis: Long, random: SecureRandom = SecureRandom()): PairedKey {
        val secret = ByteArray(32).also(random::nextBytes)
        val id = ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        return PairedKey("k-$id", Base64.getUrlEncoder().withoutPadding().encodeToString(secret), nowMillis)
    }

    /**
     * Verifies an envelope without changing any state. Rejects: malformed or unknown versions, unknown or revoked keys,
     * any payload type other than a week proposal, invalid v1 content, a bad MAC, stale or future timestamps, and
     * nonces already used with that key.
     */
    fun verify(text: String, state: SyncState, nowMillis: Long): Verification {
        val e = try { json.decodeFromString<SyncEnvelope>(text) }
            catch (_: SerializationException) { return Verification.Rejected("Envío firmado mal formado.") }
            catch (_: IllegalArgumentException) { return Verification.Rejected("Envío firmado mal formado.") }
        if (e.syncVersion != VERSION) return Verification.Rejected("Versión de envío no compatible: ${e.syncVersion}.")
        if (!e.keyId.matches(idPattern) || !e.nonce.matches(Regex("[0-9a-f]{16,64}"))) return Verification.Rejected("Identificadores del envío no válidos.")
        val key = state.keys.find { it.keyId == e.keyId } ?: return Verification.Rejected("Clave ${e.keyId} no emparejada en este dispositivo: escritura no autorizada.")
        if (key.revoked) return Verification.Rejected("La clave ${e.keyId} fue revocada: escritura no autorizada.")
        val secret = runCatching { Base64.getUrlDecoder().decode(key.secret) }.getOrNull() ?: return Verification.Rejected("Clave local dañada.")
        val expected = mac(secret, signingInput(e))
        if (!MessageDigest.isEqual(expected.toByteArray(), e.mac.lowercase().toByteArray()))
            return Verification.Rejected("Firma no válida: el contenido fue alterado o no procede de la clave emparejada.")
        if (e.payloadType !in ALLOWED_TYPES) return Verification.Rejected("Tipo ${e.payloadType} no permitido desde fuera: solo se aceptan propuestas de semana.")
        val issued = runCatching { Instant.parse(e.issuedAt).toEpochMilli() }.getOrNull() ?: return Verification.Rejected("Fecha de emisión no válida.")
        if (issued > nowMillis + MAX_SKEW_MILLIS) return Verification.Rejected("Fecha de emisión en el futuro.")
        if (issued < nowMillis - MAX_AGE_MILLIS) return Verification.Rejected("Envío caducado (más de 7 días).")
        if (state.seen.any { it.keyId == e.keyId && it.nonce == e.nonce }) return Verification.Rejected("Envío repetido: ya se procesó este envío.")
        val pkg = try { ExchangeCodec.decode(e.payload) } catch (ex: ProgramValidationException) { return Verification.Rejected(ex.message ?: "Contenido no válido.") }
        if (pkg.type.name != e.payloadType) return Verification.Rejected("El tipo declarado no coincide con el contenido.")
        return Verification.Accepted(e, pkg)
    }
}

object SyncStateSerializer : Serializer<SyncState> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    override val defaultValue = SyncState()
    fun validate(s: SyncState) {
        require(s.schemaVersion == 1)
        require(s.keys.map { it.keyId }.distinct().size == s.keys.size)
        require(s.seen.distinctBy { it.keyId to it.nonce }.size == s.seen.size)
    }
    override suspend fun readFrom(input: InputStream): SyncState = try {
        json.decodeFromString<SyncState>(input.readBytes().decodeToString()).also(::validate)
    } catch (e: SerializationException) { throw CorruptionException("Estado de sincronización ilegible. Se conserva sin reemplazar.", e) }
      catch (e: IllegalArgumentException) { throw CorruptionException("Estado de sincronización inválido. Se conserva sin reemplazar.", e) }
    override suspend fun writeTo(t: SyncState, output: OutputStream) { validate(t); output.write(json.encodeToString(t).encodeToByteArray()) }
}

class SyncRepository(private val store: DataStore<SyncState>, private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(SyncState())
    val state = mutable.asStateFlow()
    suspend fun restore() = mutex.withLock { mutable.value = store.data.first() }
    suspend fun pair(): PairedKey = mutex.withLock {
        val key = SignedSync.newKey(now())
        mutable.value = store.updateData { it.copy(keys = it.keys + key) }
        key
    }
    suspend fun revoke(keyId: String) = mutex.withLock {
        mutable.value = store.updateData { s -> s.copy(keys = s.keys.map { if (it.keyId == keyId) it.copy(revoked = true) else it }) }
    }
    fun check(text: String): Verification = SignedSync.verify(text, mutable.value, now())

    /**
     * Re-verifies and records the nonce atomically, then hands the package to [save]. The nonce is recorded first: a crash
     * after it leaves the delivery rejected as a replay, so the remote party re-sends with a new nonce — the package itself
     * is idempotent by importId, so nothing is duplicated or lost.
     */
    suspend fun accept(text: String, save: suspend (ExchangePackage) -> Boolean): Verification = mutex.withLock {
        var outcome: Verification = Verification.Rejected("Sin procesar.")
        mutable.value = store.updateData { s ->
            val v = SignedSync.verify(text, s, now())
            outcome = v
            if (v !is Verification.Accepted) s
            else s.copy(seen = (s.seen + SeenNonce(v.envelope.keyId, v.envelope.nonce, Instant.parse(v.envelope.issuedAt).toEpochMilli()))
                .filter { it.issuedAtMillis >= now() - SignedSync.MAX_AGE_MILLIS - SignedSync.MAX_SKEW_MILLIS })
        }
        (outcome as? Verification.Accepted)?.let { save(it.pkg) }
        outcome
    }
}
