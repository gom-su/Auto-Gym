package com.example.workoutpoc

import androidx.datastore.core.*
import androidx.datastore.core.okio.*
import com.example.workoutpoc.backup.*
import com.example.workoutpoc.body.*
import com.example.workoutpoc.catalog.*
import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.migration.*
import com.example.workoutpoc.planning.*
import com.example.workoutpoc.proposal.*
import com.example.workoutpoc.questions.*
import com.example.workoutpoc.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okio.*
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Instant

class BackupTest {
    @get:Rule val folder = TemporaryFolder()
    private val time = ClockSnapshot(1_000, 1_800_000_000_000, 5)
    private val date = Instant.parse("2026-11-11T12:00:00Z")
    private val app = BackupApp("1.0.1", 2)
    private fun fixture(name: String) = File("src/main/assets/examples/$name").readText()
    private class MemoryStorage(var saved: WorkoutState = WorkoutState()) : WorkoutStorage {
        override suspend fun read() = saved
        override suspend fun write(state: WorkoutState) { saved = state }
    }

    private suspend fun rich(): BackupData {
        val program = ProgramCodec.decode(fixture("variable-program-v1.json"))
        val selection = program.findDay("week-one", "day-one-a")!!
        val repo = WorkoutRepository(MemoryStorage(), { time }, { "live-session" })
        repo.load(selection.day.toPlannedSets(), selection.source)
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        repo.dispatch(repo.state.value.command(WorkoutEvent.CompleteSet))
        val first = repo.state.value.records.first()
        repo.edit(repo.state.value.sessionId, repo.state.value.revision, 0, SetStatus.COMPLETED,
            first.values.copy(reps = first.values.reps + 1), "Corrección ficticia")
        repo.addCardio(repo.state.value.sessionId, repo.state.value.revision,
            CardioEntry("cardio", "Bicicleta", 1200, 50, DistanceUnit.KM, "Cardio ficticio", time.wallMillis))
        repo.dispatch(repo.state.value.command(WorkoutEvent.FinishWorkout))
        val outline = ExchangeCodec.decode(fixture("plan-outline-v1.json")).outline!!
        val pkg = ExchangeCodec.decode(fixture("week-proposal-v1.json"))
        val proposal = pkg.proposal!!
        val decision = ProposalDecision(pkg.importId, proposal.id, proposal.version, DecisionStatus.APPROVED, time.wallMillis,
            proposal.programId, proposal.blockId, proposal.week.id, listOf("Aviso"), listOf("Cambio"), listOf("Declarado"), listOf("Motivo"))
        return BackupData(workout = repo.state.value, history = WorkoutHistory(sessions = listOf(repo.state.value.copy(sessionId = "archived-session"))),
            programs = ProgramLibrary(1, listOf(program)),
            planOutlines = PlanOutlineStore(active = listOf(outline), replaced = listOf(outline.copy(version = 2)), inactive = listOf(outline.copy(id = "inactive"))),
            exchanges = ExchangeArchive(1, listOf(pkg, ExchangeCodec.decode(fixture("training-context-v1.json")))),
            proposals = ProposalState(decisions = listOf(decision), expectations = listOf(Expectation("expect", pkg.importId, "squat", "Sentadilla",
                ChartMetric.TOP_LOAD, ExpectedDirection.INCREASE, 14, time.wallMillis, Outcome.INCONCLUSIVE, "Juicio ficticio"))),
            measurements = MeasurementLog(measurements = listOf(
                com.example.workoutpoc.body.BodyMeasurement("mass", time.wallMillis, BodyMetric.BODY_MASS, 75.2, MeasureUnit.KG, MeasureMethod.SCALE, fasted = true),
                com.example.workoutpoc.body.BodyMeasurement("fat", time.wallMillis, BodyMetric.BODY_FAT, 20.1, MeasureUnit.PERCENT, MeasureMethod.BIA, device = "Equipo ficticio"))),
            exercises = ExerciseCatalog(exercises = listOf(CatalogExercise("squat", "Sentadilla", equipment = Equipment.BARBELL,
                loadBasis = LoadBasis.TOTAL, setup = "Configuración ficticia", aliases = listOf("Squat", "Sentadilla anterior")))),
            planningAnswers = PlanningAnswers(answers = listOf(PlanningAnswer("goal", "Fuerza", false, time.wallMillis), PlanningAnswer("phase", null, true, time.wallMillis))),
            weekAnnotations = WeekAnnotations(deloads = listOf(WeekRef(program.id, "week-one")),
                notPerformed = listOf(DayRef(program.id, "week-two", "day-two-c")), goals = Goals(3, 120)),
            migrations = MigrationArchive(documents = listOf(ObsidianParser.parse(fixture("obsidian-week-v3-w1.md"), "Semana ficticia.md"))),
            settings = AppSettings(loggingMode = LoggingMode.OVERLAY))
    }

    /** Real Okio files and production repository wiring; one chosen write can fail once. */
    private inner class Device(val dir: File, val failFile: String? = null) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var failNext = false
        var writes = 0
        private fun <T> store(file: String, serializer: Serializer<T>): DataStore<T> {
            val delegate = DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM, object : OkioSerializer<T> {
                override val defaultValue = serializer.defaultValue
                override suspend fun readFrom(source: BufferedSource): T = serializer.readFrom(source.inputStream())
                override suspend fun writeTo(t: T, sink: BufferedSink) { serializer.writeTo(t, sink.outputStream()) }
            }) { dir.resolve(file).absolutePath.toPath() }, scope = scope)
            return object : DataStore<T> {
                override val data = delegate.data
                override suspend fun updateData(transform: suspend (T) -> T): T {
                    writes++
                    if (failNext && file == failFile) { failNext = false; throw IOException("Injected write failure") }
                    return delegate.updateData(transform)
                }
            }
        }
        val workout = WorkoutRepository(DataStoreWorkoutStorage(store("workout.json", WorkoutSerializer)), { time },
            history = DataStoreSessionHistory(store("history-v1.json", WorkoutHistorySerializer)))
        val programs = ProgramRepository(store("programs-v1.json", ProgramLibrarySerializer))
        val outlines = PlanOutlineRepository(store("plan-outlines-v1.json", PlanOutlineStoreSerializer))
        val exchanges = ExchangeRepository(store("exchanges-v1.json", ExchangeArchiveSerializer))
        val proposals = ProposalRepository(store("proposals-v1.json", ProposalStateSerializer), programs, exchanges, { emptySet() })
        val measurements = MeasurementRepository(store("measurements-v1.json", MeasurementLogSerializer))
        val catalog = CatalogRepository(store("exercises-v1.json", ExerciseCatalogSerializer))
        val answers = PlanningAnswerRepository(store("planning-answers-v1.json", PlanningAnswersSerializer))
        val annotations = WeekAnnotationRepository(store("week-annotations-v1.json", WeekAnnotationsSerializer))
        val migrations = MigrationRepository(store("migrations-v1.json", MigrationArchiveSerializer))
        val settings = AppSettingsRepository(store("app-settings-v1.json", AppSettingsSerializer))
        val backup = BackupRepository(workout, programs, outlines, exchanges, proposals, measurements, catalog, answers, annotations, migrations, settings)
        suspend fun close() { scope.cancel(); scope.coroutineContext[Job]!!.join() }
        suspend fun readFiles(): BackupData {
            val stores = JsonObject(BackupCodec.sections.associate { section ->
                section.key to Json.parseToJsonElement(dir.resolve(section.file).readText())
            })
            var data = BackupData()
            for (section in BackupCodec.sections) data = section.decode(data, stores)
            return data
        }
    }

    private suspend fun rejected(block: suspend () -> Unit): BackupException {
        try { block(); fail("Expected rejected backup") } catch (e: BackupException) { return e }
        error("Unreachable")
    }
    private fun change(text: String, rehash: Boolean = false, block: (MutableMap<String, JsonElement>) -> Unit): String {
        val root = Json.parseToJsonElement(text).jsonObject.toMutableMap()
        block(root)
        if (rehash) root["sha256"] = JsonPrimitive(BackupCodec.digest(root.getValue("stores").jsonObject))
        return JsonObject(root).toString()
    }

    @Test fun `rich codec round trip and independent files match after restore to clean install`() = runTest {
        val original = rich()
        val encoded = BackupCodec.encode(original, date, app)
        assertEquals(Backup(date, app, original), BackupCodec.decodeBytes(encoded.encodeToByteArray()))
        val first = Device(folder.newFolder())
        val second = Device(folder.newFolder())
        try {
            first.backup.restore(original, first.backup.snapshot())
            val exported = BackupCodec.encode(first.backup.snapshot(), date, app)
            second.backup.restore(BackupCodec.decode(exported).data, second.backup.snapshot())
            assertEquals(original, first.readFiles())
            assertEquals(original, second.readFiles())
            assertEquals(original, second.backup.snapshot())
            assertEquals(original.settings, second.settings.settings.value)
            assertEquals(original.exercises, second.catalog.catalog.value)
            assertEquals(12, BackupCodec.sections.size)
            assertFalse(second.dir.resolve("sync-v1.json").exists())
            // Emit synthetic data for the independent emulator/UI verification, never real user data.
            File("build/backup-fixture.json").apply { parentFile!!.mkdirs(); writeText(encoded) }
        } finally { first.close(); second.close() }
    }

    @Test fun `corrupt unknown future and interchange files are rejected before restore`() = runTest {
        val encoded = BackupCodec.encode(rich(), date, app)
        val bad = listOf(encoded.take(encoded.length / 2), "not JSON", "[]", fixture("training-context-v1.json"),
            change(encoded) { it["format"] = JsonPrimitive("other") },
            change(encoded) { it["backupVersion"] = JsonPrimitive(2) },
            change(encoded) { it["backupVersion"] = JsonPrimitive(0) },
            change(encoded) { it["unexpected"] = JsonPrimitive(true) },
            change(encoded) { it.remove("app") },
            change(encoded) { it["sha256"] = JsonPrimitive("0".repeat(64)) },
            change(encoded, true) { root -> root["stores"] = JsonObject(root.getValue("stores").jsonObject + ("unknown" to JsonObject(emptyMap()))) },
            change(encoded, true) { root -> root["stores"] = JsonObject(root.getValue("stores").jsonObject - "settings") },
            change(encoded, true) { root ->
                val stores = root.getValue("stores").jsonObject
                root["stores"] = JsonObject(stores + ("measurements" to JsonObject(stores.getValue("measurements").jsonObject + ("schemaVersion" to JsonPrimitive(2)))))
            },
            change(encoded, true) { root ->
                val stores = root.getValue("stores").jsonObject
                root["stores"] = JsonObject(stores + ("session" to JsonObject(stores.getValue("session").jsonObject + ("futureField" to JsonPrimitive(2)))))
            })
        bad.forEach { text -> rejected { BackupCodec.decode(text) } }
        rejected { BackupCodec.decodeBytes(byteArrayOf(0xc3.toByte(), 0x28)) }
        rejected { BackupCodec.encode(BackupData(settings = AppSettings(schemaVersion = 2)), date, app) }
        assertTrue(rejected { BackupCodec.decode(bad[5]) }.message!!.contains("Actualiza"))
    }

    @Test fun `checksum ignores object order and whitespace but detects changed data`() = runTest {
        val encoded = BackupCodec.encode(rich(), date, app)
        val stores = Json.parseToJsonElement(encoded).jsonObject.getValue("stores").jsonObject
        val reordered = change(encoded) { it["stores"] = JsonObject(stores.entries.reversed().associate { e -> e.key to e.value }) }
        assertEquals(rich(), BackupCodec.decode("  $reordered  ").data)
        rejected { BackupCodec.decode(change(encoded) { root ->
            root["stores"] = JsonObject(stores + ("settings" to JsonObject(mapOf("schemaVersion" to JsonPrimitive(1), "loggingMode" to JsonPrimitive("NOTIFICATION")))))
        }) }
    }

    @Test fun `restore refuses an active session and stale review without any writes`() = runTest {
        val device = Device(folder.newFolder())
        try {
            val before = device.backup.snapshot()
            device.workout.dispatch(device.workout.state.value.command(WorkoutEvent.StartWorkout))
            val writes = device.writes
            assertTrue(rejected { device.backup.restore(rich(), before) }.message!!.contains("sesión en curso"))
            assertEquals(writes, device.writes)
            device.workout.dispatch(device.workout.state.value.command(WorkoutEvent.FinishWorkout))
            val finished = device.backup.snapshot()
            device.settings.setLoggingMode(LoggingMode.OVERLAY)
            val writes2 = device.writes
            assertTrue(rejected { device.backup.restore(rich(), finished) }.message!!.contains("cambiaron"))
            assertEquals(writes2, device.writes)
        } finally { device.close() }
    }

    @Test fun `every corrupt local store aborts restore and remains byte identical`() = runTest {
        val data = rich()
        for (section in BackupCodec.sections) {
            val dir = folder.newFolder()
            val file = dir.resolve(section.file).apply { writeText("{truncated") }
            val device = Device(dir)
            try {
                try { device.backup.restore(data, BackupData()); fail("Expected local corruption in ${section.key}") }
                catch (_: CorruptionException) { }
                assertEquals("{truncated", file.readText())
                assertEquals(0, device.writes)
                assertEquals(listOf(file.name), dir.listFiles()!!.filter { it.isFile }.map { it.name })
            } finally { device.close() }
        }
    }

    @Test fun `mid restore and live session write failures roll back all prior stores`() = runTest {
        for (file in listOf("exercises-v1.json", "app-settings-v1.json", "workout.json")) {
            val device = Device(folder.newFolder(), file)
            try {
                device.backup.restore(rich(), device.backup.snapshot())
                val before = device.backup.snapshot()
                device.failNext = true
                val error = rejected { device.backup.restore(BackupData(), before) }
                assertTrue(error.message!!.contains("revirtieron"))
                assertEquals(before, device.backup.snapshot())
                assertEquals(before, device.readFiles())
            } finally { device.close() }
        }
    }

    @Test fun `lifecycle cancellation cannot strand a partial restore`() = runTest {
        val device = Device(folder.newFolder())
        val reached = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val base = device.settings.backupStore
        val delayed = BackupStore(Mutex(), base.read) { data -> reached.complete(Unit); proceed.await(); base.replace(data) }
        val repository = BackupRepository(device.workout, listOf(device.programs.backupStore, delayed))
        try {
            val before = repository.snapshot()
            val data = rich().copy(planOutlines = PlanOutlineStore(), exchanges = ExchangeArchiveSerializer.defaultValue,
                proposals = ProposalState(), measurements = MeasurementLog(), exercises = ExerciseCatalog(),
                planningAnswers = PlanningAnswers(), weekAnnotations = WeekAnnotations(), migrations = MigrationArchive())
            val job = launch(Dispatchers.Default) { repository.restore(data, before) }
            reached.await(); job.cancel(); proceed.complete(Unit); job.join()
            assertEquals(data, repository.snapshot())
        } finally { device.close() }
    }

    @Test fun `corrupt settings abort without reconciling an expired local rest`() = runTest {
        val dir = folder.newFolder()
        val saved = rich().workout.copy(phase = Phase.Resting, rest = RestDeadline(0, 0, time.bootCount))
        val file = dir.resolve("workout.json")
        file.outputStream().use { WorkoutSerializer.writeTo(saved, it) }
        val bytes = file.readBytes()
        dir.resolve("app-settings-v1.json").writeText("{truncated")
        val device = Device(dir)
        try {
            try { device.backup.restore(BackupData(), BackupData()); fail("Expected corruption") }
            catch (_: CorruptionException) { }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(0, device.writes)
        } finally { device.close() }
    }

    @Test fun `restoring between devices rebases clocks even when boot counts coincide`() = runTest {
        val device = Device(folder.newFolder())
        try {
            val saved = rich().copy(workout = rich().workout.copy(phase = Phase.Resting,
                rest = RestDeadline(9_000_000, time.wallMillis - 1, time.bootCount)))
            device.backup.restore(saved, device.backup.snapshot())
            assertFalse(device.workout.state.value.phase == Phase.Resting)
            assertNull(device.workout.state.value.rest)
            assertEquals(saved.workout.records, device.workout.state.value.records)
        } finally { device.close() }
    }

    @Test fun `paused portable session retains pause duration and rest on another device`() = runTest {
        val device = Device(folder.newFolder())
        try {
            val saved = rich().copy(workout = rich().workout.copy(phase = Phase.Resting,
                rest = RestDeadline(9_000_000, time.wallMillis - 15_000, time.bootCount),
                paused = PauseMark(8_000_000, time.wallMillis - 20_000, time.bootCount)))
            device.backup.restore(saved, device.backup.snapshot())
            val restored = device.workout.state.value
            assertEquals(20_000L, restored.paused!!.elapsedSince(time))
            device.workout.dispatch(restored.command(WorkoutEvent.ResumeSession))
            assertEquals(5_000L, device.workout.state.value.rest!!.remainingMillis(time))
            assertEquals(20_000L, device.workout.state.value.pausedMillis)
            assertEquals(saved.workout.records, device.workout.state.value.records)
        } finally { device.close() }
    }

    @Test fun `failed rollback is explicitly reported and not presented as preserved data`() = runTest {
        val workout = WorkoutRepository(MemoryStorage(), { time })
        var writes = 0
        val broken = BackupStore(Mutex(), { it }) { writes++; throw IOException("Persistent failure") }
        val repository = BackupRepository(workout, listOf(broken))
        val error = rejected { repository.restore(BackupData(), BackupData()) }
        assertEquals(2, writes)
        assertTrue(error.message!!.contains("ni revertir"))
        assertTrue(error.rollbackFailures.isNotEmpty())
    }
}
