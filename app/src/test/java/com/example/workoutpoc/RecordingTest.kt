package com.example.workoutpoc

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/** ENT-04: planned days become executable sessions recorded in one tap and persisted immediately. */
class RecordingTest {
    @get:Rule val folder = TemporaryFolder()
    private var time = ClockSnapshot(1_000, 1_800_000_000_000, 5)
    private var ids = 0
    private val nextId = { "session-${++ids}" }

    private fun program() = ProgramCodec.decode(javaClass.classLoader!!.getResourceAsStream("examples/variable-program-v1.json")!!
        .bufferedReader().use { it.readText() })
    private fun day(week: String, day: String) = program().findDay(week, day)!!

    private class MemoryStorage(var saved: WorkoutState = WorkoutState()) : WorkoutStorage {
        override suspend fun read() = saved
        override suspend fun write(state: WorkoutState) { saved = state }
    }

    private fun <T> okio(file: File, scope: CoroutineScope, serializer: androidx.datastore.core.Serializer<T>): DataStore<T> =
        DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM, object : OkioSerializer<T> {
            override val defaultValue: T = serializer.defaultValue
            override suspend fun readFrom(source: BufferedSource): T = serializer.readFrom(source.inputStream())
            override suspend fun writeTo(t: T, sink: BufferedSink) { serializer.writeTo(t, sink.outputStream()) }
        }) { file.absolutePath.toPath() }, scope = scope)

    @Test fun `conversion keeps order identity units and leaves unstated prescriptions unstated`() {
        val a = day("week-one", "day-one-a").day.toPlannedSets()
        assertEquals(listOf("set-one-squat-1", "set-one-squat-2"), a.map { it.prescriptionId })
        assertEquals(listOf(200, 225), a.map { it.weightTenths })
        assertEquals(listOf(90, 120), a.map { it.restSeconds })
        assertEquals("squat", a[0].exerciseId); assertEquals("TOTAL", a[0].basis); assertEquals(1 to 2, a[0].number to a[0].total)

        val row = day("week-one", "day-one-b").day.toPlannedSets().single()
        assertEquals(WeightUnit.LB, row.unit); assertEquals("PER_HAND", row.basis); assertEquals(0, row.restSeconds); assertEquals(50, row.weightStepTenths)

        val c = day("week-two", "day-two-c").day.toPlannedSets()
        assertEquals(4, c[0].rir)
        val machine = c[1]
        assertEquals(WeightUnit.MACHINE_SETTING, machine.unit); assertEquals(30, machine.weightTenths); assertNull(machine.rir)
        assertEquals(DEFAULT_REST_SECONDS, machine.restSeconds)
        val noLoad = c[2]
        assertFalse(noLoad.loadPrescribed); assertNull(noLoad.basis); assertEquals(0, noLoad.weightTenths)
        val empty = c[3]
        assertEquals(0, empty.reps); assertNull(empty.rir)

        val pull = day("week-three", "day-three-a").day.toPlannedSets().single()
        assertEquals(WeightUnit.BODYWEIGHT, pull.unit); assertEquals(0, pull.weightTenths)
    }

    @Test fun `one tap records the prefilled set with its prescription and survives a new process`() = runTest {
        val file = folder.newFolder().resolve("workout.json")
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = WorkoutRepository(DataStoreWorkoutStorage(okio(file, scope1, WorkoutSerializer)), { time }, nextId)
        repo.restore()
        val selection = day("week-one", "day-one-a")
        assertEquals(LoadResult.LOADED, repo.load(selection.day.toPlannedSets(), selection.source))
        assertEquals(Phase.Idle, repo.state.value.phase)
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        repo.dispatch(repo.state.value.command(WorkoutEvent.CompleteSet)) // the single overlay/notification tap
        val saved = repo.state.value
        val record = saved.records.single()
        assertEquals(SetValues(200, 8, null), record.values)
        assertEquals("set-one-squat-1", record.prescriptionId)
        assertEquals(90_000L, saved.rest!!.remainingMillis(time))
        scope1.cancel(); scope1.coroutineContext[Job]!!.join()

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val restored = WorkoutRepository(DataStoreWorkoutStorage(okio(file, scope2, WorkoutSerializer)), { time }, nextId)
        restored.restore()
        assertEquals(saved, restored.state.value)
        assertEquals("day-one-a", restored.state.value.source!!.dayId)
        // The prescription is kept separately from what was recorded.
        assertEquals(200, restored.state.value.plan[0].weightTenths)
        restored.dispatch(restored.state.value.command(WorkoutEvent.SkipRest))
        // A different prescribed load is prefilled as prescribed, not overridden by the previous set.
        assertEquals(SetValues(225, 6, null), restored.state.value.values)
        scope2.cancel(); scope2.coroutineContext[Job]!!.join()
    }

    @Test fun `unstated reps must be entered and unstated rir stays unrecorded`() = runTest {
        val repo = WorkoutRepository(MemoryStorage(), { time }, nextId)
        repo.restore()
        val selection = day("week-two", "day-two-c")
        repo.load(selection.day.toPlannedSets(), selection.source)
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        repeat(3) {
            repo.dispatch(repo.state.value.command(WorkoutEvent.CompleteSet))
            repo.dispatch(repo.state.value.command(WorkoutEvent.SkipRest))
        }
        assertEquals(3, repo.state.value.index)
        assertNull(repo.state.value.records[1].values.rir)
        val blocked = repo.state.value
        repo.dispatch(blocked.command(WorkoutEvent.CompleteSet))
        assertEquals(blocked, repo.state.value)
        repo.dispatch(repo.state.value.command(WorkoutEvent.IncrementReps))
        repo.dispatch(repo.state.value.command(WorkoutEvent.CompleteSet))
        assertEquals(Phase.Completed, repo.state.value.phase)
        assertEquals(SetValues(0, 1, null), repo.state.value.records.last().values)
    }

    @Test fun `a repeated deviation carries within the same prescribed load only`() {
        val reducer = WorkoutReducer(DemoWorkout.sets)
        val base = PlannedSet("Remo", 1, 2, 300, 8, 2, "p1", "row", restSeconds = 0)
        var s = reducer.load(WorkoutState(), listOf(base, base.copy(number = 2, prescriptionId = "p2")),
            day("week-one", "day-one-a").source, "s")
        fun step(e: WorkoutEvent) { s = reducer.reduce(s, s.command(e), time, "s") }
        step(WorkoutEvent.StartWorkout); step(WorkoutEvent.IncrementWeight); step(WorkoutEvent.CompleteSet)
        assertEquals(325, s.values.weightTenths)
    }

    @Test fun `zero rest advances directly and units use their own step`() = runTest {
        val repo = WorkoutRepository(MemoryStorage(), { time }, nextId)
        repo.restore()
        val selection = day("week-one", "day-one-b")
        repo.load(selection.day.toPlannedSets(), selection.source)
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        repo.dispatch(repo.state.value.command(WorkoutEvent.IncrementWeight))
        assertEquals(150, repo.state.value.values.weightTenths)
        repo.dispatch(repo.state.value.command(WorkoutEvent.CompleteSet))
        assertEquals(Phase.Completed, repo.state.value.phase)
    }

    @Test fun `in progress session is never replaced and finished sessions are archived once`() = runTest {
        val repo = WorkoutRepository(MemoryStorage(), { time }, nextId)
        repo.restore()
        val a = day("week-one", "day-one-a"); val b = day("week-one", "day-one-b")
        repo.load(a.day.toPlannedSets(), a.source)
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        repo.dispatch(repo.state.value.command(WorkoutEvent.CompleteSet))
        val inProgress = repo.state.value
        assertEquals(LoadResult.SESSION_IN_PROGRESS, repo.load(b.day.toPlannedSets(), b.source))
        assertEquals(inProgress, repo.state.value)

        repo.dispatch(repo.state.value.command(WorkoutEvent.FinishWorkout))
        val finished = repo.state.value
        assertEquals(LoadResult.LOADED, repo.load(b.day.toPlannedSets(), b.source))
        val archived = repo.archived.value.sessions.single()
        assertEquals(finished.sessionId, archived.sessionId)
        assertEquals(finished.records, archived.records)
        assertTrue(archived.endedEarly)

        // Closing a partially recorded session archives it as ended early instead of discarding it.
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        repo.dispatch(repo.state.value.command(WorkoutEvent.IncrementReps))
        repo.dispatch(repo.state.value.command(WorkoutEvent.CompleteSet))
        repo.dispatch(repo.state.value.command(WorkoutEvent.ResetDemo))
        assertEquals(2, repo.archived.value.sessions.size)
        assertEquals(Phase.Completed, repo.archived.value.sessions[1].phase)
        assertNull(repo.state.value.source)
    }

    @Test fun `history file is idempotent durable and rejects tampering`() = runTest {
        val file = folder.newFolder().resolve("history-v1.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val history = DataStoreSessionHistory(okio(file, scope, WorkoutHistorySerializer))
        val repo = WorkoutRepository(MemoryStorage(), { time }, nextId, history)
        repo.restore()
        val a = day("week-one", "day-one-a")
        repo.load(a.day.toPlannedSets(), a.source)
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        repo.dispatch(repo.state.value.command(WorkoutEvent.CompleteSet))
        repo.dispatch(repo.state.value.command(WorkoutEvent.FinishWorkout))
        val finished = repo.state.value
        repo.load(a.day.toPlannedSets(), a.source)
        assertFalse(history.archive(finished.copy(endedAtMillis = finished.endedAtMillis)))
        assertEquals(1, history.read().sessions.size)
        scope.cancel(); scope.coroutineContext[Job]!!.join()

        val text = file.readText()
        assertEquals(1, WorkoutHistorySerializer.readFrom(ByteArrayInputStream(text.toByteArray())).sessions.size)
        val tampered = text.replace("\"weightTenths\":200", "\"weightTenths\":999999")
        assertNotEquals(text, tampered)
        try { WorkoutHistorySerializer.readFrom(ByteArrayInputStream(tampered.toByteArray())); fail("Expected corruption") }
        catch (_: CorruptionException) { }
    }

    @Test fun `pre ENT-04 demo snapshot still restores`() = runTest {
        val legacy = """{"sessionId":"s","phase":"ActiveSet","index":1,"revision":4,"values":{"weightTenths":325,"reps":7,"rir":1},
            "records":[{"index":0,"exercise":"Press con mancuernas","number":1,"values":{"weightTenths":300,"reps":8,"rir":2},"completedAtMillis":5}],
            "rest":null,"endedEarly":false}"""
        val state = WorkoutSerializer.readFrom(ByteArrayInputStream(legacy.toByteArray()))
        assertTrue(state.plan.isEmpty()); assertNull(state.source)
        assertEquals("Press con mancuernas", state.activePlan()[state.index].exercise)
        val bad = legacy.replace("\"reps\":7", "\"reps\":31")
        try { WorkoutSerializer.readFrom(ByteArrayInputStream(bad.toByteArray())); fail("Expected corruption") }
        catch (_: CorruptionException) { }
    }

    @Test fun `planned sessions export as valid v1 results without invented load bases`() = runTest {
        val repo = WorkoutRepository(MemoryStorage(), { time }, nextId)
        repo.restore()
        val c = day("week-two", "day-two-c")
        repo.load(c.day.toPlannedSets(), c.source)
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        repo.dispatch(repo.state.value.command(WorkoutEvent.SelectRir, 4))
        repeat(3) {
            repo.dispatch(repo.state.value.command(WorkoutEvent.CompleteSet))
            repo.dispatch(repo.state.value.command(WorkoutEvent.SkipRest))
        }
        val result = repo.state.value.toSessionResult()!!
        assertEquals(PerformanceStatus.PARTIAL, result.status); assertNull(result.endedAt)
        assertEquals("day-two-c", result.sessionPlanId)
        assertEquals(repo.state.value.source!!.programId, result.programId)
        assertEquals(listOf("set-two-squat-1", "set-two-press-1", "set-two-press-2"), result.sets.map { it.prescriptionId })
        assertEquals(PrescribedLoad(0.0, LoadUnit.KG, LoadBasis.PER_SIDE), result.sets[0].load)
        assertTrue(result.sets[0].notes!!.contains("4 o más"))
        assertEquals(PrescribedLoad(3.0, LoadUnit.MACHINE_SETTING, LoadBasis.MACHINE_SETTING), result.sets[1].load)
        assertNull(result.sets[1].rir)
        assertNull(result.sets[2].load); assertTrue(result.sets[2].notes!!.contains("base no indicada"))
        val pkg = ExchangePackage(1, ExchangeType.TrainingResults, "results", "2026-11-06T12:00:00Z",
            results = TrainingResults(listOf(result), repo.state.value.toDemoV1()?.let { DemoSnapshot(it) }))
        assertEquals(pkg, ExchangeCodec.decode(ExchangeCodec.encode(pkg)))
        assertNull(pkg.results!!.demo)
    }

    @Test fun `legacy demo snapshot keeps the exact v1 field set`() = runTest {
        val repo = WorkoutRepository(MemoryStorage(), { time }, nextId)
        repo.restore()
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        repo.dispatch(repo.state.value.command(WorkoutEvent.CompleteSet))
        val demo = repo.state.value.toDemoV1()!!
        val json = Json.parseToJsonElement(ExchangeCodec.json.encodeToString(DemoSnapshot(demo))).jsonObject
        val state = json["state"]!!.jsonObject
        assertEquals(setOf("sessionId", "phase", "index", "revision", "values", "records", "rest", "endedEarly"), state.keys)
        assertEquals(setOf("index", "exercise", "number", "values", "completedAtMillis"), (state["records"] as kotlinx.serialization.json.JsonArray)[0].jsonObject.keys)
        assertNull(repo.state.value.toSessionResult())
        assertTrue(json is JsonObject)
    }
}
