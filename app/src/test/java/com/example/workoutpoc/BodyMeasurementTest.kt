package com.example.workoutpoc

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.example.workoutpoc.body.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

/** ANA-03: measurements keep date, unit and method; trends never merge different methods or units. */
class BodyMeasurementTest {
    @get:Rule val folder = TemporaryFolder()
    private val day = 86_400_000L
    private fun m(id: String, d: Int, metric: BodyMetric, value: Double, unit: MeasureUnit, method: MeasureMethod) =
        BodyMeasurement(id, 1_800_000_000_000 + d * day, metric, value, unit, method)

    @Test fun `different methods and units become separate series with a warning flag`() {
        val log = MeasurementLog(measurements = listOf(
            m("a", 3, BodyMetric.BODY_FAT, 18.0, MeasureUnit.PERCENT, MeasureMethod.BIA),
            m("b", 1, BodyMetric.BODY_FAT, 20.0, MeasureUnit.PERCENT, MeasureMethod.BIA),
            m("c", 2, BodyMetric.BODY_FAT, 15.5, MeasureUnit.PERCENT, MeasureMethod.DEXA),
            m("d", 1, BodyMetric.BODY_MASS, 80.0, MeasureUnit.KG, MeasureMethod.SCALE),
            m("e", 2, BodyMetric.BODY_MASS, 176.0, MeasureUnit.LB, MeasureMethod.SCALE),
            m("f", 4, BodyMetric.BODY_MASS, 79.5, MeasureUnit.KG, MeasureMethod.SCALE)))
        val t = trends(log)
        val fat = t.single { it.metric == BodyMetric.BODY_FAT }
        assertTrue(fat.mixedMethods); assertFalse(fat.mixedUnits)
        assertEquals(2, fat.series.size)
        val bia = fat.series.single { it.method == MeasureMethod.BIA }
        assertEquals(listOf(20.0, 18.0), bia.points.map { it.value })          // oldest first, BIA only
        assertEquals(listOf(15.5), fat.series.single { it.method == MeasureMethod.DEXA }.points.map { it.value })
        val mass = t.single { it.metric == BodyMetric.BODY_MASS }
        assertFalse(mass.mixedMethods); assertTrue(mass.mixedUnits)
        // Units are never converted: kg and lb stay separate series with their original values.
        assertEquals(listOf(80.0, 79.5), mass.series.single { it.unit == MeasureUnit.KG }.points.map { it.value })
        assertEquals(listOf(176.0), mass.series.single { it.unit == MeasureUnit.LB }.points.map { it.value })
    }

    @Test fun `validation rejects incoherent measurements`() {
        listOf(
            m("x", 1, BodyMetric.BODY_FAT, 18.0, MeasureUnit.KG, MeasureMethod.BIA),           // wrong unit for metric
            m("x", 1, BodyMetric.BODY_FAT, 120.0, MeasureUnit.PERCENT, MeasureMethod.BIA),     // > 100 %
            m("x", 1, BodyMetric.BODY_MASS, -1.0, MeasureUnit.KG, MeasureMethod.SCALE),
            m("x", 1, BodyMetric.BODY_MASS, Double.NaN, MeasureUnit.KG, MeasureMethod.SCALE),
            m("x", 1, BodyMetric.OTHER, 30.0, MeasureUnit.CM, MeasureMethod.TAPE),             // OTHER needs a name
            m("bad id", 1, BodyMetric.BODY_MASS, 80.0, MeasureUnit.KG, MeasureMethod.SCALE),
            m("x", 1, BodyMetric.BODY_MASS, 80.0, MeasureUnit.KG, MeasureMethod.SCALE).copy(notes = " ")
        ).forEach { bad -> try { MeasurementCodec.validate(bad); fail("accepted $bad") } catch (_: MeasurementException) { } }
        MeasurementCodec.validate(m("x", 1, BodyMetric.OTHER, 30.0, MeasureUnit.CM, MeasureMethod.TAPE).copy(label = "Brazo"))
    }

    @Test fun `repository is idempotent, durable and never resets a corrupt file`() = runTest {
        val file = folder.newFolder().resolve("measurements-v1.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM, object : OkioSerializer<MeasurementLog> {
            override val defaultValue = MeasurementLogSerializer.defaultValue
            override suspend fun readFrom(source: BufferedSource) = MeasurementLogSerializer.readFrom(source.inputStream())
            override suspend fun writeTo(t: MeasurementLog, sink: BufferedSink) { MeasurementLogSerializer.writeTo(t, sink.outputStream()) }
        }) { file.absolutePath.toPath() }, scope = scope)
        val repo = MeasurementRepository(store)
        repo.restore()
        val first = m("a", 1, BodyMetric.BODY_MASS, 80.0, MeasureUnit.KG, MeasureMethod.SCALE)
        repo.add(first); repo.add(first)
        assertEquals(1, repo.log.value.measurements.size)
        try { repo.add(first.copy(value = 81.0)); fail() } catch (_: MeasurementException) { }
        repo.add(m("b", 2, BodyMetric.BODY_MASS, 79.0, MeasureUnit.KG, MeasureMethod.SCALE))
        repo.remove("a")
        assertEquals(listOf("b"), repo.log.value.measurements.map { it.id })
        scope.cancel(); scope.coroutineContext[Job]!!.join()
        assertEquals(repo.log.value, MeasurementLogSerializer.readFrom(ByteArrayInputStream(file.readBytes())))
        val corrupt = """{"schemaVersion":1,"measurements":[{"id":"a","measuredAtMillis":1,"metric":"BODY_FAT","value":10,"unit":"KG","method":"BIA"}]}"""
        try { MeasurementLogSerializer.readFrom(ByteArrayInputStream(corrupt.toByteArray())); fail() } catch (_: CorruptionException) { }
    }
}
