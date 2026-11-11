package com.example.workoutpoc.body

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

@Serializable enum class BodyMetric(val label: String, val units: List<MeasureUnit>) {
    BODY_MASS("Peso corporal", listOf(MeasureUnit.KG, MeasureUnit.LB)),
    BODY_FAT("Grasa corporal", listOf(MeasureUnit.PERCENT)),
    LEAN_MASS("Masa magra", listOf(MeasureUnit.KG, MeasureUnit.LB)),
    MUSCLE_MASS("Masa muscular", listOf(MeasureUnit.KG, MeasureUnit.LB, MeasureUnit.PERCENT)),
    WAIST("Cintura", listOf(MeasureUnit.CM, MeasureUnit.IN)),
    OTHER("Otra medida", MeasureUnit.entries)
}
@Serializable enum class MeasureUnit(val label: String) { KG("kg"), LB("lb"), PERCENT("%"), CM("cm"), IN("in") }
@Serializable enum class MeasureMethod(val label: String) {
    SCALE("Báscula"), BIA("Bioimpedancia"), DEXA("DEXA"), CALIPER("Plicómetro"), TAPE("Cinta métrica"), OTHER("Otro")
}

/**
 * One measurement exactly as taken. Values are never converted between units, and results from different methods
 * are never treated as one series. Optional context helps judge comparability; it is never filled in by default.
 */
@Serializable data class BodyMeasurement(
    val id: String, val measuredAtMillis: Long, val metric: BodyMetric, val value: Double, val unit: MeasureUnit,
    val method: MeasureMethod, val label: String? = null, val fasted: Boolean? = null, val timeOfDay: String? = null,
    val device: String? = null, val notes: String? = null
)

@Serializable data class MeasurementLog(val schemaVersion: Int = 1, val measurements: List<BodyMeasurement> = emptyList())

class MeasurementException(message: String) : IllegalArgumentException(message)

object MeasurementCodec {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private fun check(ok: Boolean, message: String) { if (!ok) throw MeasurementException(message) }
    private fun text(v: String?, max: Int, what: String) = check(v == null || (v.isNotBlank() && v == v.trim() && v.length <= max), "$what vacío o demasiado largo.")

    fun validate(m: BodyMeasurement) {
        check(m.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")), "Identificador no válido.")
        check(m.measuredAtMillis > 0, "Fecha no válida.")
        check(m.value.isFinite() && m.value >= 0, "El valor debe ser un número no negativo.")
        check(m.unit in m.metric.units, "Unidad ${m.unit.label} no válida para ${m.metric.label}.")
        check(m.unit != MeasureUnit.PERCENT || m.value <= 100, "Un porcentaje no puede superar 100.")
        check(m.value <= 10_000, "Valor fuera de rango.")
        check(m.metric != BodyMetric.OTHER || m.label != null, "Indica el nombre de la medida.")
        text(m.label, 100, "Nombre de la medida"); text(m.timeOfDay, 50, "Momento del día"); text(m.device, 100, "Dispositivo"); text(m.notes, 1000, "Nota")
    }

    fun validate(log: MeasurementLog) {
        check(log.schemaVersion == 1, "Versión no compatible.")
        check(log.measurements.map { it.id }.distinct().size == log.measurements.size, "Mediciones duplicadas.")
        check(log.measurements.size <= 20_000, "Demasiadas mediciones.")
        log.measurements.forEach(::validate)
    }
}

object MeasurementLogSerializer : Serializer<MeasurementLog> {
    override val defaultValue = MeasurementLog()
    override suspend fun readFrom(input: InputStream): MeasurementLog = try {
        MeasurementCodec.json.decodeFromString<MeasurementLog>(input.readBytes().decodeToString()).also(MeasurementCodec::validate)
    } catch (e: SerializationException) { throw CorruptionException("Mediciones ilegibles. Se conservan sin reemplazar.", e) }
      catch (e: IllegalArgumentException) { throw CorruptionException("Mediciones inválidas. Se conservan sin reemplazar.", e) }
    override suspend fun writeTo(t: MeasurementLog, output: OutputStream) {
        MeasurementCodec.validate(t); output.write(MeasurementCodec.json.encodeToString(t).encodeToByteArray())
    }
}

class MeasurementRepository(private val store: DataStore<MeasurementLog>) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(MeasurementLog())
    val log = mutable.asStateFlow()
    suspend fun restore() = mutex.withLock { mutable.value = store.data.first() }
    /** Replaces the whole store with a restored backup (see docs/FULL_BACKUP.md). The serializer validates it. */
    suspend fun replaceAll(log: MeasurementLog) = mutex.withLock { replaceAllLocked(log) }
    private suspend fun replaceAllLocked(log: MeasurementLog) { MeasurementCodec.validate(log); mutable.value = store.updateData { log } }
    internal val backupStore get() = com.example.workoutpoc.backup.BackupStore(mutex,
        { it.copy(measurements = store.data.first()) }, { replaceAllLocked(it.measurements) })
    /** Adds one measurement; re-adding the same ID with the same content is a no-op, different content is rejected. */
    suspend fun add(m: BodyMeasurement) = mutex.withLock {
        MeasurementCodec.validate(m)
        mutable.value = store.updateData { log ->
            val existing = log.measurements.find { it.id == m.id }
            when {
                existing == m -> log
                existing != null -> throw MeasurementException("Ya existe una medición con ese identificador.")
                else -> log.copy(measurements = log.measurements + m)
            }
        }
    }
    suspend fun remove(id: String) = mutex.withLock {
        mutable.value = store.updateData { log -> log.copy(measurements = log.measurements.filterNot { it.id == id }) }
    }
}

/** A trend series: one metric (and label for OTHER), one unit, one method. Only these are plotted as a line. */
data class MeasurementSeries(val metric: BodyMetric, val label: String?, val unit: MeasureUnit, val method: MeasureMethod,
                             val points: List<BodyMeasurement>)

/** Trends for one metric. [methods] > 1 means results exist from different methods and must not be read as one trend. */
data class MetricTrends(val metric: BodyMetric, val label: String?, val series: List<MeasurementSeries>) {
    val methods get() = series.map { it.method }.distinct()
    val units get() = series.map { it.unit }.distinct()
    val mixedMethods get() = methods.size > 1
    val mixedUnits get() = units.size > 1
}

/** Groups measurements into comparable series (metric + label + unit + method), oldest first within each series. */
fun trends(log: MeasurementLog): List<MetricTrends> =
    log.measurements.groupBy { it.metric to it.label }.map { (key, list) ->
        MetricTrends(key.first, key.second, list.groupBy { it.unit to it.method }.map { (k, items) ->
            MeasurementSeries(key.first, key.second, k.first, k.second, items.sortedBy { it.measuredAtMillis })
        }.sortedByDescending { s -> s.points.size })
    }.sortedBy { it.metric.ordinal }
