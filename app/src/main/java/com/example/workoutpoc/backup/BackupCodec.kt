package com.example.workoutpoc.backup

import androidx.datastore.core.Serializer
import com.example.workoutpoc.body.MeasurementLog
import com.example.workoutpoc.body.MeasurementLogSerializer
import com.example.workoutpoc.catalog.ExerciseCatalog
import com.example.workoutpoc.catalog.ExerciseCatalogSerializer
import com.example.workoutpoc.data.WorkoutHistory
import com.example.workoutpoc.data.WorkoutHistorySerializer
import com.example.workoutpoc.data.WorkoutSerializer
import com.example.workoutpoc.domain.Phase
import com.example.workoutpoc.domain.SessionCommentLog
import com.example.workoutpoc.domain.SessionCommentLogSerializer
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.domain.inProgress
import com.example.workoutpoc.exchange.ExchangeArchive
import com.example.workoutpoc.exchange.ExchangeArchiveSerializer
import com.example.workoutpoc.migration.MigrationArchive
import com.example.workoutpoc.migration.MigrationArchiveSerializer
import com.example.workoutpoc.planning.PlanOutlineStore
import com.example.workoutpoc.planning.PlanOutlineStoreSerializer
import com.example.workoutpoc.planning.ProgramLibrary
import com.example.workoutpoc.planning.ProgramLibrarySerializer
import com.example.workoutpoc.planning.WeekAnnotations
import com.example.workoutpoc.planning.WeekAnnotationsSerializer
import com.example.workoutpoc.proposal.ProposalState
import com.example.workoutpoc.proposal.ProposalStateSerializer
import com.example.workoutpoc.questions.PlanningAnswers
import com.example.workoutpoc.questions.PlanningAnswersSerializer
import com.example.workoutpoc.settings.AppSettings
import com.example.workoutpoc.settings.AppSettingsSerializer
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant

/**
 * Everything the app keeps on the device, one typed snapshot per store. The pairing keys of signed deliveries
 * (`sync-v1.json`) and the Keystore-backed Claude API key are deliberately absent: secrets never leave the device in this plain file.
 */
data class BackupData(
    val workout: WorkoutState = WorkoutState(), val history: WorkoutHistory = WorkoutHistory(),
    val programs: ProgramLibrary = ProgramLibrarySerializer.defaultValue, val planOutlines: PlanOutlineStore = PlanOutlineStore(),
    val exchanges: ExchangeArchive = ExchangeArchiveSerializer.defaultValue, val proposals: ProposalState = ProposalState(),
    val measurements: MeasurementLog = MeasurementLog(), val exercises: ExerciseCatalog = ExerciseCatalog(),
    val planningAnswers: PlanningAnswers = PlanningAnswers(), val weekAnnotations: WeekAnnotations = WeekAnnotations(),
    val migrations: MigrationArchive = MigrationArchive(), val settings: AppSettings = AppSettings(),
    val comments: SessionCommentLog = SessionCommentLogSerializer.defaultValue
) {
    /** A set is being recorded right now; such a session is never replaced by a restore. */
    val sessionInProgress: Boolean get() = workout.phase.inProgress

    /** False for a fresh install: nothing a restore could destroy. Preferences alone do not count. */
    val hasUserData: Boolean get() = copy(settings = AppSettings()) != BackupData()
}

@Serializable data class BackupApp(val versionName: String, val versionCode: Int)

/** A decoded backup file: when and by which app version it was written, and its content. */
data class Backup(val createdAt: Instant, val app: BackupApp, val data: BackupData)

/** A file that is not a usable backup, or a backup that could not be written or restored. The message is shown as is. */
class BackupException(message: String, cause: Throwable? = null, val rollbackFailures: List<Exception> = emptyList()) : IllegalArgumentException(message, cause)

/**
 * Full backup format v1 (docs/FULL_BACKUP.md). It is the app's own format and independent of interchange v1: each
 * entry of `stores` is exactly the document that store persists, written and read back by the store's own serializer,
 * so the backup accepts precisely what the app itself could have saved, no more.
 */
object BackupCodec {
    const val FORMAT = "auto-gym-backup"
    const val VERSION = 1
    /** Sanity limit for a picked file; a real backup is a few MiB. */
    const val MAX_BYTES = 64 * 1024 * 1024

    private val strict = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Serializable private data class Envelope(
        val format: String, val backupVersion: Int, val createdAt: String, val app: BackupApp, val sha256: String, val stores: JsonObject)

    /** One store's place in the file: its key in `stores`, the file it lives in on the device and how it is read. */
    internal class Section<T>(val key: String, val file: String, val label: String, private val serializer: Serializer<T>,
                              private val get: (BackupData) -> T, private val set: (BackupData, T) -> BackupData,
                              /** Stores added after version 1 may be absent from older backups; they restore as the default. */
                              private val optional: Boolean = false) {
        suspend fun encode(data: BackupData): JsonElement = try {
            val bytes = ByteArrayOutputStream().also { serializer.writeTo(get(data), it) }.toByteArray()
            Json.parseToJsonElement(bytes.decodeToString())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw BackupException("No se pudo preparar el respaldo: $label no supera su propia validación. Tus datos se conservan.", e) }

        suspend fun decode(into: BackupData, stores: JsonObject): BackupData {
            val element = stores[key] ?: return if (optional) set(into, serializer.defaultValue)
                else throw BackupException("Respaldo incompleto: falta $label. No se reemplazó nada.")
            val value = try { serializer.readFrom(ByteArrayInputStream(Json.encodeToString(JsonElement.serializer(), element).encodeToByteArray())) }
                catch (e: CancellationException) { throw e }
                // CorruptionException (an IOException) or a validation error: the store itself would not open this document.
                catch (e: Exception) { throw BackupException("El respaldo no es válido: esta versión de la aplicación no puede leer $label. No se reemplazó nada.", e) }
            // Some legacy serializers tolerate unknown keys. Backups must never silently drop future data.
            val normalized = ByteArrayOutputStream().also { serializer.writeTo(value, it) }.toByteArray()
            rejectUnknownFields(element, Json.parseToJsonElement(normalized.decodeToString()))
            return set(into, value)
        }
    }

    internal val sections: List<Section<*>> = listOf(
        Section("session", "workout.json", "la sesión actual", WorkoutSerializer, { it.workout }, { d, v -> d.copy(workout = v) }),
        Section("history", "history-v1.json", "el historial de sesiones", WorkoutHistorySerializer, { it.history }, { d, v -> d.copy(history = v) }),
        Section("programs", "programs-v1.json", "los programas", ProgramLibrarySerializer, { it.programs }, { d, v -> d.copy(programs = v) }),
        Section("planOutlines", "plan-outlines-v1.json", "los planes de periodización", PlanOutlineStoreSerializer, { it.planOutlines }, { d, v -> d.copy(planOutlines = v) }),
        Section("exchanges", "exchanges-v1.json", "los intercambios archivados", ExchangeArchiveSerializer, { it.exchanges }, { d, v -> d.copy(exchanges = v) }),
        Section("proposals", "proposals-v1.json", "las decisiones sobre propuestas", ProposalStateSerializer, { it.proposals }, { d, v -> d.copy(proposals = v) }),
        Section("measurements", "measurements-v1.json", "las mediciones corporales", MeasurementLogSerializer, { it.measurements }, { d, v -> d.copy(measurements = v) }),
        Section("exercises", "exercises-v1.json", "el catálogo de ejercicios", ExerciseCatalogSerializer, { it.exercises }, { d, v -> d.copy(exercises = v) }),
        Section("planningAnswers", "planning-answers-v1.json", "las respuestas de planificación", PlanningAnswersSerializer, { it.planningAnswers }, { d, v -> d.copy(planningAnswers = v) }),
        Section("weekAnnotations", "week-annotations-v1.json", "las anotaciones de semanas y metas", WeekAnnotationsSerializer, { it.weekAnnotations }, { d, v -> d.copy(weekAnnotations = v) }),
        Section("migrations", "migrations-v1.json", "las migraciones de Obsidian", MigrationArchiveSerializer, { it.migrations }, { d, v -> d.copy(migrations = v) }),
        Section("settings", "app-settings-v1.json", "los ajustes", AppSettingsSerializer, { it.settings }, { d, v -> d.copy(settings = v) }),
        Section("comments", "session-comments-v1.json", "los comentarios de entrenamiento", SessionCommentLogSerializer,
            { it.comments }, { d, v -> d.copy(comments = v) }, optional = true)
    )

    /**
     * The backup file for [data]. Before the text is handed out it is decoded again and compared with [data]: a backup
     * that would not restore to the same data is refused here, while the data is still safe on the device.
     */
    suspend fun encode(data: BackupData, createdAt: Instant, app: BackupApp): String {
        val stores = JsonObject(sections.associate { it.key to it.encode(data) })
        val text = strict.encodeToString(Envelope(FORMAT, VERSION, createdAt.toString(), app, digest(stores), stores))
        if (text.encodeToByteArray().size > MAX_BYTES) throw BackupException("No se pudo preparar el respaldo: supera ${MAX_BYTES / 1024 / 1024} MiB.")
        val back = try { decode(text) } catch (e: BackupException) { throw BackupException("No se pudo preparar el respaldo: no se relee como se escribió. Tus datos se conservan.", e) }
        if (back.data != data) throw BackupException("No se pudo preparar el respaldo: no se relee como se escribió. Tus datos se conservan.")
        return text
    }

    /** Strict UTF-8 only; see [decode]. */
    suspend fun decodeBytes(bytes: ByteArray): Backup {
        if (bytes.size > MAX_BYTES) throw BackupException("El archivo supera ${MAX_BYTES / 1024 / 1024} MiB: no es un respaldo de Auto-Gym. No se reemplazó nada.")
        val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
            catch (e: Exception) { throw BackupException("El archivo no es texto UTF-8 válido: no es un respaldo utilizable. No se reemplazó nada.", e) }
        return decode(text)
    }

    /**
     * Reads and fully validates a backup without changing anything. Rejects: anything that is not complete JSON, another
     * format, a future or unknown version, unknown or missing fields and stores, content that does not match its
     * checksum, and any store document its own serializer would refuse (including a future store schema version).
     */
    suspend fun decode(text: String): Backup {
        if (text.length > MAX_BYTES || text.encodeToByteArray().size > MAX_BYTES)
            throw BackupException("El archivo supera 64 MiB. No se reemplazó nada.")
        val root = try { Json.parseToJsonElement(text) as? JsonObject }
            catch (e: SerializationException) { throw BackupException("El archivo no es JSON completo: está dañado o truncado. No se reemplazó nada.", e) }
            ?: throw BackupException(NOT_A_BACKUP)
        if ((root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content != FORMAT) throw BackupException(NOT_A_BACKUP)
        val version = (root["backupVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw BackupException("Respaldo sin versión de formato válida. No se reemplazó nada.")
        if (version > VERSION) throw BackupException(
            "Este respaldo usa la versión $version del formato y esta aplicación lee hasta la $VERSION. Actualiza la aplicación para restaurarlo. No se reemplazó nada.")
        if (version < 1) throw BackupException("Versión de respaldo $version no válida. No se reemplazó nada.")
        val envelope = try { strict.decodeFromJsonElement<Envelope>(root) }
            catch (e: SerializationException) { throw BackupException("Respaldo con campos desconocidos o incompletos. No se reemplazó nada.", e) }
        val createdAt = try { Instant.parse(envelope.createdAt) }
            catch (e: Exception) { throw BackupException("Fecha del respaldo no válida. No se reemplazó nada.", e) }
        if (envelope.app.versionName.isBlank() || envelope.app.versionName.length > 100 || envelope.app.versionCode < 1)
            throw BackupException("Versión de la aplicación no válida en el respaldo. No se reemplazó nada.")
        val known = sections.map { it.key }.toSet()
        (envelope.stores.keys - known).firstOrNull()?.let {
            throw BackupException("El respaldo contiene un almacén desconocido (\"${it.take(40)}\"). No se reemplazó nada.")
        }
        // Checked before any store is read: a damaged or edited file is reported as such, not as an invalid store.
        if (!MessageDigest.isEqual(digest(envelope.stores).encodeToByteArray(), envelope.sha256.encodeToByteArray()))
            throw BackupException("El contenido no coincide con su suma de comprobación: el archivo está dañado o fue modificado. No se reemplazó nada.")
        var data = BackupData()
        for (section in sections) data = section.decode(data, envelope.stores)
        return Backup(createdAt, envelope.app, data)
    }

    private const val NOT_A_BACKUP = "El archivo no es un respaldo completo de Auto-Gym. Los archivos de intercambio, programas y semanas de " +
        "Obsidian se abren con Importar. No se reemplazó nada."

    private fun rejectUnknownFields(input: JsonElement, normalized: JsonElement) {
        if (input is JsonObject && normalized is JsonObject) {
            if ((input.keys - normalized.keys).isNotEmpty())
                throw BackupException("El respaldo contiene campos de datos desconocidos. Actualiza la aplicación. No se reemplazó nada.")
            input.forEach { (key, value) -> normalized[key]?.let { rejectUnknownFields(value, it) } }
        } else if (input is JsonArray && normalized is JsonArray) {
            input.zip(normalized).forEach { (a, b) -> rejectUnknownFields(a, b) }
        }
    }

    /**
     * Lowercase hex SHA-256 over a canonical walk of `stores`: independent of whitespace and of key order, and it reads
     * numbers as their literal text, so it never depends on how a platform prints a floating-point value.
     */
    internal fun digest(stores: JsonObject): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun mark(s: String) = md.update(s.encodeToByteArray())
        fun text(tag: Char, s: String) { val bytes = s.encodeToByteArray(); mark("$tag${bytes.size}:"); md.update(bytes) }
        fun visit(e: JsonElement) {
            when (e) {
                is JsonNull -> mark("n")
                is JsonPrimitive -> text(if (e.isString) 's' else 'l', e.content)
                is JsonArray -> { mark("[${e.size}:"); e.forEach(::visit) }
                is JsonObject -> { mark("{${e.size}:"); e.entries.sortedBy { it.key }.forEach { (k, v) -> text('k', k); visit(v) } }
            }
        }
        visit(stores)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** What a backup (or this device) holds, as the lines the restore review compares side by side. */
data class BackupCounts(
    val sessions: Int, val sets: Int, val setEdits: Int, val cardio: Int, val programs: Int, val weeks: Int, val plans: Int,
    val exchanges: Int, val decisions: Int, val measurements: Int, val exercises: Int, val aliases: Int, val answers: Int,
    val annotations: Int, val migrations: Int, val session: String
) {
    fun rows(): List<Pair<String, String>> = listOf(
        "Sesiones registradas" to "$sessions",
        "Series registradas" to "$sets" + if (setEdits > 0) " ($setEdits versiones anteriores)" else "",
        "Entradas de cardio" to "$cardio",
        "Sesión actual" to session,
        "Programas" to "$programs" + if (weeks > 0) " ($weeks semanas)" else "",
        "Planes de periodización" to "$plans",
        "Intercambios archivados" to "$exchanges",
        "Decisiones sobre propuestas" to "$decisions",
        "Mediciones corporales" to "$measurements",
        "Ejercicios del catálogo" to "$exercises" + if (aliases > 0) " ($aliases alias)" else "",
        "Respuestas de planificación" to "$answers",
        "Descargas, días no realizados y metas" to "$annotations",
        "Migraciones de Obsidian" to "$migrations"
    )
}

fun BackupData.counts(): BackupCounts {
    val recorded = (history.sessions + workout).distinctBy { it.sessionId }.filter { it.records.isNotEmpty() || it.cardio.isNotEmpty() }
    return BackupCounts(
        sessions = recorded.size, sets = recorded.sumOf { it.records.size }, setEdits = recorded.sumOf { s -> s.records.sumOf { it.revisions.size } },
        cardio = recorded.sumOf { it.cardio.size }, programs = programs.programs.size,
        weeks = programs.programs.sumOf { p -> p.blocks.sumOf { it.weeks.size } },
        plans = planOutlines.active.size + planOutlines.inactive.size + planOutlines.replaced.size,
        exchanges = exchanges.packages.size, decisions = proposals.decisions.size, measurements = measurements.measurements.size,
        exercises = exercises.exercises.size, aliases = exercises.exercises.sumOf { it.aliases.size }, answers = planningAnswers.answers.size,
        annotations = weekAnnotations.deloads.size + weekAnnotations.notPerformed.size +
            listOfNotNull(weekAnnotations.goals.sessionsPerWeek, weekAnnotations.goals.cardioMinutesPerWeek).size,
        migrations = migrations.documents.size,
        session = when {
            sessionInProgress -> "En curso: ${workout.source?.dayName ?: "demo"}"
            workout.phase == Phase.Completed -> "Terminada: ${workout.source?.dayName ?: "demo"}"
            workout.source != null -> "Cargada sin empezar: ${workout.source.dayName}"
            else -> "Ninguna"
        })
}
