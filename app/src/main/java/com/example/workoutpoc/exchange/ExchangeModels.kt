package com.example.workoutpoc.exchange

import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.planning.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable enum class ExchangeType { TrainingContext, WeekProposal, TrainingResults }
@Serializable data class ExchangePackage(
    val schemaVersion: Int, val type: ExchangeType, val importId: String, val createdAt: String,
    val context: TrainingContext? = null, val proposal: WeekProposal? = null, val results: TrainingResults? = null
)
@Serializable data class TrainingContext(
    val programs: List<TrainingProgram>, val phase: String?, val history: TrainingResults,
    val rules: List<String>, val goals: List<String>, val measurements: List<BodyMeasurement> = emptyList()
)
@Serializable data class BodyMeasurement(val id: String, val measuredAt: String, val metric: String, val value: Double, val unit: String)
@Serializable data class WeekProposal(
    val id: String, val version: Int, val programId: String, val blockId: String,
    val source: String, val week: WeekPlan, val differences: List<String>, val reasons: List<String>
)
@Serializable data class TrainingResults(val sessions: List<SessionResult>, val demo: DemoSnapshot? = null)
/** Legacy demo values are integer tenths of kg. Its load basis was never captured. */
@Serializable data class DemoSnapshot(val state: WorkoutState, val loadUnit: String = "KG", val loadBasis: String = "UNSPECIFIED", val loadScale: Int = 10)
@Serializable enum class PerformanceStatus { COMPLETED, PARTIAL, NOT_PERFORMED, OMITTED }
@Serializable data class SessionResult(
    val id: String, val sessionPlanId: String?, val status: PerformanceStatus,
    val startedAt: String?, val endedAt: String?, val notes: String?,
    val sets: List<SetResult>, val cardio: List<CardioResult>
)
@Serializable data class SetResult(
    val id: String, val prescriptionId: String?, val exerciseId: String,
    val status: PerformanceStatus, val reps: Int?, val rir: Int?, val load: PrescribedLoad?,
    val completedAt: String?, val notes: String?
)
@Serializable data class CardioResult(
    val id: String, val status: PerformanceStatus, val durationSeconds: Int?,
    val distance: Double?, val distanceUnit: String?, val notes: String?
)

object ExchangeCodec {
    const val MAX_BYTES = 1_048_576
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; prettyPrint = true }
    fun decode(text: String): ExchangePackage {
        requireValid(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES, "El archivo supera 1 MiB.")
        val result = try { json.decodeFromString<ExchangePackage>(text) }
        catch (_: SerializationException) { throw ProgramValidationException("JSON incompatible: revisa campos, tipos y formato de intercambio v1.") }
        catch (_: IllegalArgumentException) { throw ProgramValidationException("JSON de intercambio no válido.") }
        validate(result)
        return result
    }
    fun encode(data: ExchangePackage): String {
        validate(data)
        return json.encodeToString(data).also {
            requireValid(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES, "El intercambio supera 1 MiB; reduce su contenido.")
        }
    }
    fun validate(data: ExchangePackage) {
        requireValid(data.schemaVersion == 1, "Versión de intercambio no compatible. Se requiere 1.")
        id(data.importId); date(data.createdAt)
        requireValid(listOfNotNull(data.context, data.proposal, data.results).size == 1, "Incluye exactamente un contenido: contexto, propuesta o resultados.")
        when (data.type) {
            ExchangeType.TrainingContext -> {
                val c = data.context ?: invalid("Falta el contexto.")
                ProgramCodec.validateLibrary(ProgramLibrary(1, c.programs))
                c.phase?.let(::note); c.rules.forEach(::note); c.goals.forEach(::note)
                results(c.history)
                unique(c.measurements.map { it.id })
                c.measurements.forEach { m -> id(m.id); date(m.measuredAt); note(m.metric); note(m.unit); number(m.value) }
            }
            ExchangeType.WeekProposal -> {
                val p = data.proposal ?: invalid("Falta la propuesta.")
                id(p.id); id(p.programId); id(p.blockId); note(p.source)
                requireValid(p.version > 0, "La versión de propuesta debe ser positiva.")
                p.differences.forEach(::note); p.reasons.forEach(::note)
                ProgramCodec.validate(TrainingProgram(p.programId, "Propuesta", listOf(TrainingBlock(p.blockId, "Bloque", listOf(p.week)))))
            }
            ExchangeType.TrainingResults -> results(data.results ?: invalid("Faltan los resultados."))
        }
    }
    private fun results(r: TrainingResults) {
        unique(r.sessions.map { it.id })
        r.sessions.forEach { s ->
            id(s.id); s.sessionPlanId?.let(::id); s.startedAt?.let(::date); s.endedAt?.let(::date); s.notes?.let(::note)
            if (s.startedAt != null && s.endedAt != null) requireValid(Instant.parse(s.startedAt) <= Instant.parse(s.endedAt), "Fechas de sesión invertidas.")
            unique(s.sets.map { it.id } + s.cardio.map { it.id })
            s.sets.forEach { set ->
                id(set.id); id(set.exerciseId); set.prescriptionId?.let(::id); set.completedAt?.let(::date); set.notes?.let(::note)
                // Reuse prescription ranges and unit/basis invariants without mixing performed and prescribed data.
                ProgramCodec.validate(TrainingProgram("validation-program", "Resultado", listOf(TrainingBlock("validation-block", "Bloque", listOf(
                    WeekPlan("validation-week", "Semana", 1, listOf(SessionPlan("validation-day", "Día", listOf(
                        ExercisePrescription("validation-exercise", "exercise", "Ejercicio", listOf(SetPrescription("validation-set", set.reps?.takeIf { it != 0 }, set.rir, set.load)))
                    ))))
                )))))
                requireValid(set.reps == null || set.reps in 0..1000, "Repeticiones de resultado fuera de rango.")
            }
            s.cardio.forEach { c ->
                id(c.id); c.notes?.let(::note)
                requireValid(c.durationSeconds == null || c.durationSeconds in 0..604800, "Duración de cardio fuera de rango.")
                requireValid((c.distance == null) == (c.distanceUnit == null), "La distancia necesita valor y unidad.")
                c.distance?.let(::number)
                requireValid(c.distanceUnit == null || c.distanceUnit in listOf("M", "KM", "MI"), "Unidad de distancia no compatible.")
            }
        }
        r.demo?.let { d ->
            requireValid(d.loadUnit == "KG" && d.loadBasis == "UNSPECIFIED" && d.loadScale == 10, "Unidades de demo incompatibles.")
            val s = d.state
            requireValid(s.index in 0..7 && s.revision >= 0, "Estado de demo fuera de rango.")
            fun values(v: com.example.workoutpoc.domain.SetValues) {
                requireValid(v.weightTenths in 0..20000 && v.reps in 0..1000 && v.rir in 0..4, "Valores de demo fuera de rango.")
            }
            values(s.values)
            unique(s.records.map { it.index.toString() })
            s.records.forEach { record ->
                requireValid(record.index in 0..6 && record.number in 1..7 && record.completedAtMillis >= 0, "Registro de demo fuera de rango.")
                note(record.exercise); values(record.values)
            }
        }
    }
    private fun id(value: String) = requireValid(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")), "ID de intercambio no válido.")
    private fun unique(ids: List<String>) = requireValid(ids.distinct().size == ids.size, "IDs duplicados en el intercambio.")
    private fun note(value: String) = requireValid(value.isNotBlank() && value.length <= 10000, "Texto vacío o demasiado largo.")
    private fun number(value: Double) = requireValid(value.isFinite() && value >= 0 && value <= 1e9, "Valor numérico fuera de rango.")
    private fun date(value: String) { try { Instant.parse(value) } catch (_: Exception) { invalid("Fecha no válida: usa ISO-8601 con zona horaria.") } }
    private fun requireValid(ok: Boolean, message: String) { if (!ok) invalid(message) }
    private fun invalid(message: String): Nothing = throw ProgramValidationException(message)
}

/** Redaction is a different package; stable derived identity makes repeated exports idempotent. */
object ExchangePrivacy {
    fun forExport(data: ExchangePackage, includeMeasurements: Boolean): ExchangePackage {
        val context = data.context ?: return data
        if (includeMeasurements || context.measurements.isEmpty()) return data
        val redacted = data.copy(context = context.copy(measurements = emptyList()))
        return redacted.copy(importId = java.util.UUID.nameUUIDFromBytes(
            ExchangeCodec.json.encodeToString(redacted).toByteArray(Charsets.UTF_8)).toString())
    }
}
