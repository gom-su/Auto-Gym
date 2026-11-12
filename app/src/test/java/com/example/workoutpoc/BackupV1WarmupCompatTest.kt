package com.example.workoutpoc

import com.example.workoutpoc.backup.*
import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant

/**
 * A backup written before the shared training model (contract §3, §2, §6, §5) has no setType, recordedUnit,
 * plannedCardioId, cardioPlan or restSegments. It is built here from the current encoding with exactly those keys
 * removed, which is the v1 shape, and it must restore with the documented defaults and keep its values.
 */
class BackupV1WarmupCompatTest {
    private val source = SessionSource("prog", "Programa", "block", "week-one", "Semana 1", 1, "day-a", "Día A")
    private val newKeys = setOf("setType", "recordedUnit", "plannedCardioId", "cardioPlan", "restSegments")

    private fun JsonElement.withoutNewKeys(): JsonElement = when (this) {
        is JsonObject -> JsonObject(filterKeys { it !in newKeys }.mapValues { it.value.withoutNewKeys() })
        is JsonArray -> JsonArray(map { it.withoutNewKeys() })
        else -> this
    }

    private suspend fun v1WorkoutDocument(state: WorkoutState): JsonElement {
        val out = ByteArrayOutputStream().also { WorkoutSerializer.writeTo(state, it) }
        return Json.parseToJsonElement(out.toByteArray().decodeToString()).withoutNewKeys()
    }

    private fun envelope(stores: JsonObject): String = buildJsonObject {
        put("format", BackupCodec.FORMAT)
        put("backupVersion", 1)
        put("createdAt", "2026-11-11T12:00:00Z")
        putJsonObject("app") { put("versionName", "1.0.0"); put("versionCode", 1) }
        put("sha256", BackupCodec.digest(stores))
        put("stores", stores)
    }.toString()

    @Test fun `a v1 backup with warm-up sets restores as APPROACH and keeps its values`() = runTest {
        val plan = listOf(
            PlannedSet(exercise = "Remo", number = 1, total = 3, weightTenths = 300, reps = 10, rir = 3,
                exerciseId = "remo", unit = WeightUnit.KG, warmup = true, restSeconds = 60, extra = true, loadPrescribed = false),
            PlannedSet(exercise = "Remo", number = 2, total = 3, weightTenths = 400, reps = 8, rir = 2,
                exerciseId = "remo", unit = WeightUnit.KG, restSeconds = 90)
        )
        val state = WorkoutState(sessionId = "v1-session", plan = plan, source = source)
        val stores = JsonObject(BackupCodec.sections.associate { section ->
            section.key to if (section.key == "session") v1WorkoutDocument(state) else section.encode(BackupData())
        })

        val restored = BackupCodec.decode(envelope(stores)).data.workout.plan

        assertEquals(2, restored.size)
        assertTrue(restored[0].warmup)
        assertEquals(SetType.APPROACH, restored[0].setType)
        assertTrue(restored[0].extra)
        assertEquals(300, restored[0].weightTenths)
        assertEquals(WeightUnit.KG, restored[0].unit)
        assertEquals(60, restored[0].restSeconds)
        assertEquals(SetType.WORK, restored[1].setType)
        assertEquals(400, restored[1].weightTenths)
        assertEquals(90, restored[1].restSeconds)
    }

    @Test fun `a v1 backup restores without the new keys and rewrites them in the current format`() = runTest {
        val state = WorkoutState(sessionId = "v1-session", source = source, plan = listOf(
            PlannedSet(exercise = "Remo", number = 1, total = 1, weightTenths = 300, reps = 10, rir = 3,
                exerciseId = "remo", unit = WeightUnit.KG, restSeconds = 60)))
        val stores = JsonObject(BackupCodec.sections.associate { section ->
            section.key to if (section.key == "session") v1WorkoutDocument(state) else section.encode(BackupData())
        })
        val restored = BackupCodec.decode(envelope(stores)).data

        val rewritten = BackupCodec.encode(restored, Instant.parse("2026-11-11T12:00:00Z"), BackupApp("1.0.0", 1))
        val again = BackupCodec.decode(rewritten).data

        assertEquals(restored, again)
        assertEquals(SetType.WORK, again.workout.plan.single().setType)
        assertTrue(again.workout.restSegments.isEmpty())
        assertTrue(again.workout.cardioPlan.isEmpty())
        assertTrue(rewritten.contains("\"setType\""))
    }
}
