package com.example.workoutpoc

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.example.workoutpoc.catalog.ChartMetric
import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.*
import com.example.workoutpoc.proposal.*
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
import java.io.File

/** AG-04: each recommendation ends favorable, unfavorable, inconclusive or pending, against observed data. */
class RecommendationEvaluationTest {
    @get:Rule val folder = TemporaryFolder()
    private val day = 86_400_000L
    private val t0 = 1_800_000_000_000L
    private var time = ClockSnapshot(1_000, t0, 5)
    private var ids = 0

    private fun week(id: String, load: Double) = WeekPlan(id, "Semana $id", 1, listOf(SessionPlan("$id-d", "Día", listOf(
        ExercisePrescription("$id-e", "squat", "Sentadilla", listOf(SetPrescription("$id-s1", 5, 2, PrescribedLoad(load, LoadUnit.KG, LoadBasis.TOTAL), 0)))))))
    private val program = TrainingProgram("eval-program", "Programa", listOf(TrainingBlock("b", "Bloque",
        listOf(week("w1", 100.0), week("w2", 60.0), week("w3", 105.0), week("w4", 95.0)))))
    private val deload = WeekAnnotations(deloads = listOf(WeekRef("eval-program", "w2")))

    private class MemoryStorage(var saved: WorkoutState = WorkoutState()) : WorkoutStorage {
        override suspend fun read() = saved
        override suspend fun write(state: WorkoutState) { saved = state }
    }

    /** Trains the given weeks on the given days (offset from t0). */
    private suspend fun sessions(vararg plan: Pair<String, Int>): List<WorkoutState> {
        val repo = WorkoutRepository(MemoryStorage(), { time }, { "s-${++ids}" })
        repo.restore()
        plan.forEach { (w, offset) ->
            time = time.copy(wallMillis = t0 + offset * day)
            val d = program.findDay(w, "$w-d")!!
            repo.load(d.day.toPlannedSets(), d.source)
            repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
            repo.completeSet()
            repo.dispatch(repo.state.value.command(WorkoutEvent.ResetDemo))
        }
        return repo.archived.value.sessions
    }
    private fun exp(direction: ExpectedDirection, metric: ChartMetric = ChartMetric.TOP_LOAD, manual: Outcome? = null) =
        Expectation("e", "i", "squat", "Sentadilla", metric, direction, 14, t0 + 1 * day, manual, manual?.let { "Lesión ficticia" })

    @Test fun `all four outcomes are reachable and deloads are not observations`() = runTest {
        // Baseline 100 kg on day 0; decision on day 1; a deload (60 kg) on day 3; 105 kg on day 7.
        val s = sessions("w1" to 0, "w2" to 3, "w3" to 7)
        val open = evaluate(exp(ExpectedDirection.INCREASE), s, deload, t0 + 10 * day)
        assertEquals(Outcome.PENDING, open.outcome); assertTrue(open.detail.contains("provisional"))
        val closed = t0 + 20 * day
        val inc = evaluate(exp(ExpectedDirection.INCREASE), s, deload, closed)
        assertEquals(Outcome.FAVORABLE, inc.outcome); assertEquals(100.0, inc.baseline!!, 0.0); assertEquals(105.0, inc.observed!!, 0.0)
        assertEquals(Outcome.UNFAVORABLE, evaluate(exp(ExpectedDirection.DECREASE), s, deload, closed).outcome)
        assertEquals(Outcome.FAVORABLE, evaluate(exp(ExpectedDirection.MAINTAIN), s, deload, closed).outcome)
        // Without the deload mark, its 60 kg would not be the last observation anyway, but it would be a candidate:
        // with only the deload in the window, the result is inconclusive rather than unfavorable.
        val onlyDeload = sessions("w1" to 0, "w2" to 3)
        assertEquals(Outcome.INCONCLUSIVE, evaluate(exp(ExpectedDirection.INCREASE), onlyDeload, deload, closed).outcome)
        assertEquals(Outcome.UNFAVORABLE, evaluate(exp(ExpectedDirection.INCREASE), onlyDeload, WeekAnnotations(), closed).outcome)
        // A drop below the baseline is unfavorable for an expected increase.
        assertEquals(Outcome.UNFAVORABLE, evaluate(exp(ExpectedDirection.INCREASE), sessions("w1" to 0, "w4" to 7), deload, closed).outcome)
        // No baseline before the decision: inconclusive, never guessed.
        assertEquals(Outcome.INCONCLUSIVE, evaluate(exp(ExpectedDirection.INCREASE), sessions("w3" to 7), deload, closed).outcome)
        // A manual judgement takes precedence and is labelled as such.
        val manual = evaluate(exp(ExpectedDirection.INCREASE, manual = Outcome.INCONCLUSIVE), s, deload, closed)
        assertEquals(Outcome.INCONCLUSIVE, manual.outcome); assertTrue(manual.manual); assertTrue(manual.detail.contains("Lesión ficticia"))
        // Other metrics use completed work sets.
        assertEquals(Outcome.INCONCLUSIVE, evaluate(exp(ExpectedDirection.INCREASE, ChartMetric.TOTAL_REPS), s, deload, closed).outcome)
        assertEquals(Outcome.FAVORABLE, evaluate(exp(ExpectedDirection.INCREASE, ChartMetric.VOLUME), s, deload, closed).outcome)
    }

    private fun <T> store(file: File, scope: CoroutineScope, s: Serializer<T>): DataStore<T> =
        DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM, object : OkioSerializer<T> {
            override val defaultValue: T = s.defaultValue
            override suspend fun readFrom(source: BufferedSource): T = s.readFrom(source.inputStream())
            override suspend fun writeTo(t: T, sink: BufferedSink) { s.writeTo(t, sink.outputStream()) }
        }) { file.absolutePath.toPath() }, scope = scope)

    @Test fun `expectations exist only for approved recommendations and start at the decision`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dir = folder.newFolder()
        val programs = ProgramRepository(store(dir.resolve("p.json"), scope, ProgramLibrarySerializer))
        val exchanges = ExchangeRepository(store(dir.resolve("x.json"), scope, ExchangeArchiveSerializer))
        programs.restore(); exchanges.restore()
        programs.importProgram(program.copy(blocks = listOf(program.blocks[0].copy(weeks = listOf(program.blocks[0].weeks[0])))))
        val proposals = ProposalRepository(store(dir.resolve("pr.json"), scope, ProposalStateSerializer), programs, exchanges,
            { setOf("squat") }, { t0 + 5 * day })
        proposals.restore()
        exchanges.save(ExchangePackage(1, ExchangeType.WeekProposal, "imp", "2026-11-06T12:00:00Z", proposal = WeekProposal("p", 1,
            "eval-program", "b", "Agente", program.blocks[0].weeks[2], emptyList(), listOf("Sentadilla: subir carga"))))
        val e = Expectation("e1", "imp", "squat", "Sentadilla", ChartMetric.TOP_LOAD, ExpectedDirection.INCREASE, 14, 1L)
        try { proposals.addExpectation(e); fail("pending proposals cannot be evaluated") } catch (_: ProposalException) { }
        proposals.approve("imp")
        proposals.addExpectation(e)
        assertEquals(t0 + 5 * day, proposals.state.value.expectations.single().startMillis)
        proposals.setManualOutcome("e1", Outcome.UNFAVORABLE, "  Lesión  ")
        assertEquals(Outcome.UNFAVORABLE, proposals.state.value.expectations.single().manualOutcome)
        assertEquals("Lesión", proposals.state.value.expectations.single().note)
        try { proposals.addExpectation(e.copy(id = "e2", windowDays = 0)); fail() } catch (_: ProposalException) { }
        try { proposals.addExpectation(e.copy(id = "e3", metric = ChartMetric.MIN_RIR)); fail() } catch (_: ProposalException) { }
        scope.cancel(); scope.coroutineContext[Job]!!.join()
        val reloaded = ProposalStateSerializer.readFrom(dir.resolve("pr.json").inputStream())
        assertEquals(1, reloaded.expectations.size)
    }
}
