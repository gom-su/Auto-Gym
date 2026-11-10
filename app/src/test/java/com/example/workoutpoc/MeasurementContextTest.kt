package com.example.workoutpoc

import com.example.workoutpoc.body.BodyMetric
import com.example.workoutpoc.body.MeasureMethod
import com.example.workoutpoc.body.MeasureUnit
import com.example.workoutpoc.body.MeasurementCodec
import com.example.workoutpoc.exchange.*
import org.junit.Assert.*
import org.junit.Test

class MeasurementContextTest {
    @Test fun `context preserves all measurement details and distinguishes muscle from lean mass`() {
        val local = com.example.workoutpoc.body.BodyMeasurement("muscle", 1_799_020_800_000L, BodyMetric.MUSCLE_MASS,
            35.0, MeasureUnit.KG, MeasureMethod.BIA, fasted = true, timeOfDay = "mañana", device = "Báscula A", notes = "Tras despertar")
        MeasurementCodec.validate(local)
        MeasurementCodec.validate(local.copy(id = "percent", unit = MeasureUnit.PERCENT))
        val muscle = local.toContextMeasurement()
        assertEquals("MUSCLE_MASS", muscle.metric)
        assertEquals("BIA", muscle.method)
        assertEquals(local.fasted, muscle.fasted)
        assertEquals(local.timeOfDay, muscle.timeOfDay)
        assertEquals(local.device, muscle.device)
        assertEquals(local.notes, muscle.notes)
        assertEquals(java.time.Instant.ofEpochMilli(local.measuredAtMillis).toString(), muscle.measuredAt)
        val lean = local.copy(id = "lean", metric = BodyMetric.LEAN_MASS, method = MeasureMethod.DEXA).toContextMeasurement()
        assertNotEquals(muscle.metric, lean.metric)
        assertNotEquals(muscle.method, lean.method)
        val context = TrainingContext(emptyList(), null, TrainingResults(emptyList()), emptyList(), emptyList(), listOf(muscle, lean))
        val pkg = ExchangePackage(1, ExchangeType.TrainingContext, "context", "2026-11-10T12:00:00Z", context = context)
        assertEquals(pkg, ExchangeCodec.decode(ExchangeCodec.encode(pkg)))
        assertEquals(pkg, ExchangePrivacy.forExport(pkg, true))
        assertTrue(ExchangePrivacy.forExport(pkg, false).context!!.measurements.isEmpty())
        assertEquals(2, pkg.context!!.measurements.size)
    }

    @Test fun `older context without methods or previous plans still decodes`() {
        val json = """{"schemaVersion":1,"type":"TrainingContext","importId":"old","createdAt":"2026-11-10T12:00:00Z","context":{"programs":[],"phase":null,"history":{"sessions":[]},"rules":[],"goals":[],"measurements":[{"id":"weight","measuredAt":"2026-11-10T12:00:00Z","metric":"mass","value":80,"unit":"KG"}]}}"""
        val context = ExchangeCodec.decode(json).context!!
        assertNull(context.measurements.single().method)
        assertTrue(context.previousOutlines.isEmpty())
    }

    @Test fun `prior cycle structures are retained with their versions in context`() {
        val old = ExchangeCodec.decode(java.io.File("src/main/assets/examples/plan-outline-v1.json").readText()).outline!!
        val active = old.copy(id = "new-plan", programId = "new-program")
        val context = TrainingContext(emptyList(), null, TrainingResults(emptyList()), emptyList(), emptyList(),
            outlines = listOf(active), previousOutlines = listOf(old))
        val pkg = ExchangePackage(1, ExchangeType.TrainingContext, "context", "2026-11-10T12:00:00Z", context = context)
        val decoded = ExchangeCodec.decode(ExchangeCodec.encode(pkg)).context!!
        assertEquals(listOf(old), decoded.previousOutlines)
        assertEquals(listOf(active), decoded.outlines)
    }
}
