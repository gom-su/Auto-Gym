package com.example.workoutpoc.migration

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.json.Json

@Serializable data class SourceLine(val line: Int, val text: String, val kind: String)
@Serializable data class NumberRange(val min: Double, val max: Double = min)
@Serializable data class MigrationLoad(val raw: String, val magnitude: NumberRange?, val unit: String?, val basis: String?)
@Serializable data class MigrationPrescription(
    val raw: String, val sets: Int?, val reps: NumberRange?, val rir: NumberRange?, val perSide: Boolean
)
@Serializable data class MigrationValues(val raw: String, val values: List<Double?>)
@Serializable data class MigrationComment(val line: Int, val text: String, val author: String?)
@Serializable data class MigrationExercise(
    val id: String, val line: Int, val name: String, val section: String,
    val columns: Map<String, String>, val prescription: MigrationPrescription,
    val load: MigrationLoad, val reps: MigrationValues, val rir: MigrationValues,
    val comments: List<MigrationComment>
)
@Serializable data class MigrationCardio(val line: Int, val field: String, val raw: String, val durationMinutes: NumberRange?)
@Serializable data class MigrationDay(
    val id: String, val line: Int, val title: String, val exercises: List<MigrationExercise>,
    val cardio: List<MigrationCardio>, val notes: List<MigrationComment>, val status: String?
)
@Serializable data class MigrationIssue(
    val id: String, val code: String, val line: Int, val message: String, val acknowledged: Boolean = false
)
@Serializable data class MigrationDocument(
    val schemaVersion: Int, val parserVersion: Int, val id: String, val source: String,
    val original: String, val title: String?, val metadata: Map<String, String>,
    val days: List<MigrationDay>, val notes: List<MigrationComment>,
    val lines: List<SourceLine>, val issues: List<MigrationIssue>
)
@Serializable data class MigrationArchive(@Required val schemaVersion: Int = 1, @Required val documents: List<MigrationDocument> = emptyList())

class MigrationException(message: String) : IllegalArgumentException(message)

object MigrationCodec {
    const val MAX_BYTES = 1_048_576
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun validate(document: MigrationDocument) {
        if (document.schemaVersion != 1 || document.parserVersion != ObsidianParser.VERSION)
            throw MigrationException("Versión de migración no compatible. El archivo se conserva.")
        if (document.source.isBlank() || document.source.length > 1000)
            throw MigrationException("Origen de migración no válido.")
        val expected = ObsidianParser.parse(document.original, document.source)
        if (document.copy(issues = document.issues.map { it.copy(acknowledged = false) }) != expected)
            throw MigrationException("La migración no coincide con su original. El archivo se conserva.")
    }

    fun validate(archive: MigrationArchive) {
        if (archive.schemaVersion != 1 || archive.documents.map { it.id }.distinct().size != archive.documents.size)
            throw MigrationException("Archivo de migraciones incompatible o con identidades duplicadas.")
        archive.documents.forEach(::validate)
    }
}
