package com.example.workoutpoc

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.*
import com.example.workoutpoc.proposal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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

/** AG-02/AG-05/AG-06: imported proposals stay pending, are validated by rules, and only explicit approval applies them. */
class ProposalReviewTest {
    @get:Rule val folder = TemporaryFolder()
    private val program = ProgramCodec.decode(javaClass.classLoader!!.getResourceAsStream("examples/variable-program-v1.json")!!
        .bufferedReader().use { it.readText() })
    private val date = "2026-11-06T12:00:00Z"

    private fun <T> store(file: File, scope: CoroutineScope, s: Serializer<T>): DataStore<T> =
        DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM, object : OkioSerializer<T> {
            override val defaultValue: T = s.defaultValue
            override suspend fun readFrom(source: BufferedSource): T = s.readFrom(source.inputStream())
            override suspend fun writeTo(t: T, sink: BufferedSink) { s.writeTo(t, sink.outputStream()) }
        }) { file.absolutePath.toPath() }, scope = scope)

    private class Env(val programs: ProgramRepository, val exchanges: ExchangeRepository, val proposals: ProposalRepository, val dir: File)

    private suspend fun env(scope: CoroutineScope): Env {
        val dir = folder.newFolder()
        val programs = ProgramRepository(store(dir.resolve("programs.json"), scope, ProgramLibrarySerializer))
        val exchanges = ExchangeRepository(store(dir.resolve("exchanges.json"), scope, ExchangeArchiveSerializer))
        programs.restore(); exchanges.restore()
        programs.importProgram(program)
        val proposals = ProposalRepository(store(dir.resolve("proposals.json"), scope, ProposalStateSerializer), programs, exchanges,
            { programs.library.value.programs.flatMap { p -> p.blocks.flatMap { b -> b.weeks.flatMap { w -> w.days.flatMap { d -> d.exercises.map { it.exerciseId } } } } }.toSet() },
            { 1_800_000_000_000 })
        proposals.restore()
        return Env(programs, exchanges, proposals, dir)
    }

    /** Next week for block-base, based on week-two: more squat sets, heavier machine press, a new exercise and RIR 0. */
    private fun proposedWeek(id: String = "week-proposed", squatUnit: LoadUnit = LoadUnit.KG) = WeekPlan(id, "Semana propuesta", 1, listOf(
        SessionPlan("$id-day", "Día P", listOf(
            ExercisePrescription("$id-squat", "squat", "Sentadilla ficticia", listOf(
                SetPrescription("$id-s1", 5, 3, PrescribedLoad(0.0, squatUnit, LoadBasis.PER_SIDE), 60),
                SetPrescription("$id-s2", 5, 3, PrescribedLoad(0.0, squatUnit, LoadBasis.PER_SIDE), 60))),
            ExercisePrescription("$id-press", "machine-press", "Press ficticio", listOf(
                SetPrescription("$id-p1", 10, 0, PrescribedLoad(4.0, LoadUnit.MACHINE_SETTING, LoadBasis.MACHINE_SETTING), 90),
                SetPrescription("$id-p2", 10, 2, null, 90), SetPrescription("$id-p3", 10, 2, null, 90))),
            ExercisePrescription("$id-lunge", "lunge-new", "Zancada ficticia", listOf(SetPrescription("$id-l1", 8, 2, null, 60)))
        ))))

    private suspend fun Env.importProposal(importId: String, week: WeekPlan, version: Int = 1, programId: String = program.id): String {
        exchanges.save(ExchangePackage(1, ExchangeType.WeekProposal, importId, date, proposal = WeekProposal("proposal-next", version,
            programId, "block-base", "Agente ficticio", week, listOf("Más volumen de sentadilla"), listOf("Progresión ficticia"))))
        return importId
    }

    @Test fun `imported proposal stays pending and evaluation reports differences and warnings without changing anything`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val e = env(scope)
        val id = e.importProposal("import-1", proposedWeek())
        val before = e.programs.library.value
        val eval = e.proposals.evaluate(id)
        assertNull(e.proposals.decisionFor(id))
        assertEquals("week-two", eval.base!!.id)
        assertTrue(eval.canApprove)
        val codes = eval.warnings.map { it.code }.toSet()
        assertEquals(setOf("SET_INCREASE", "LOAD_INCREASE", "RIR_ZERO", "NEW_EXERCISE"), codes)
        assertTrue(eval.differences.any { it.contains("Sentadilla ficticia: series 1 → 2") })
        assertTrue(eval.differences.any { it.contains("carga máxima 3 → 4") })
        assertTrue(eval.differences.any { it.startsWith("Se añade Zancada ficticia") })
        assertEquals(before, e.programs.library.value)
        scope.cancel(); scope.coroutineContext[Job]!!.join()
    }

    @Test fun `explicit approval appends the week once and records a traceable decision`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val e = env(scope)
        val id = e.importProposal("import-1", proposedWeek())
        val decision = e.proposals.approve(id)
        assertEquals(DecisionStatus.APPROVED, decision.status)
        assertEquals(listOf("Progresión ficticia"), decision.reasons); assertEquals(listOf("Más volumen de sentadilla"), decision.statedDifferences)
        assertEquals(4, decision.warnings.size); assertTrue(decision.differences.isNotEmpty())
        val saved = e.programs.library.value.programs.single()
        assertEquals("week-proposed", saved.blocks.single { it.id == "block-base" }.weeks.last().id)
        assertNotNull(saved.findDay("week-proposed", "week-proposed-day")) // trainable via Entrenar este día
        assertEquals(decision, e.proposals.approve(id))
        assertEquals(1, saved.blocks.flatMap { it.weeks }.count { it.id == "week-proposed" })
        try { e.proposals.reject(id); fail() } catch (_: ProposalException) { }
        scope.cancel(); scope.coroutineContext[Job]!!.join()
    }

    @Test fun `a mandatory rule blocks approval until the conflict is resolved`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val e = env(scope)
        val bad = e.importProposal("import-bad", proposedWeek(squatUnit = LoadUnit.LB))
        val eval = e.proposals.evaluate(bad)
        assertFalse(eval.canApprove)
        assertEquals("UNIT_CHANGE", eval.blocking.single().code)
        val before = e.programs.library.value
        try { e.proposals.approve(bad); fail() } catch (ex: ProposalException) { assertTrue(ex.message!!.contains("bloqueos")) }
        assertEquals(before, e.programs.library.value); assertNull(e.proposals.decisionFor(bad))
        // Resolved by a corrected version from the agent.
        val fixed = e.importProposal("import-fixed", proposedWeek(), version = 2)
        assertEquals(DecisionStatus.APPROVED, e.proposals.approve(fixed).status)
        // A rule configured as blocking applies to warnings too.
        e.proposals.updateRules(RuleSettings(newExercise = Severity.BLOCKING))
        // A fresh exercise ID: the approved week above made "lunge-new" known.
        val third = proposedWeek("week-three-proposed").let { w -> w.copy(days = w.days.map { d -> d.copy(exercises = d.exercises.map {
            if (it.exerciseId == "lunge-new") it.copy(exerciseId = "lunge-newer") else it }) }) }
        val next = e.importProposal("import-3", third, version = 3)
        assertEquals("NEW_EXERCISE", e.proposals.evaluate(next).blocking.single().code)
        e.proposals.updateRules(RuleSettings(newExercise = Severity.OFF, setIncrease = Severity.OFF, loadIncrease = Severity.OFF, rirZero = Severity.OFF))
        assertTrue(e.proposals.evaluate(next).issues.isEmpty())
        scope.cancel(); scope.coroutineContext[Job]!!.join()
    }

    @Test fun `integrity is always blocking`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val e = env(scope)
        val existing = e.importProposal("import-existing", program.blocks[0].weeks[0].copy(name = "Cambiada"))
        // Other rules may also block (its squat basis differs from the base week); the reused ID always does.
        assertTrue(e.proposals.evaluate(existing).blocking.any { it.code == "WEEK_EXISTS" })
        val missing = e.importProposal("import-missing", proposedWeek("week-x"), version = 2, programId = "other-program")
        assertEquals(listOf("PROGRAM_MISSING"), e.proposals.evaluate(missing).blocking.map { it.code })
        val reuse = proposedWeek("week-y").let { w -> w.copy(days = w.days.map { d -> d.copy(id = "day-one-a") }) }
        val reused = e.importProposal("import-reuse", reuse, version = 3)
        assertEquals(listOf("INTEGRITY"), e.proposals.evaluate(reused).blocking.map { it.code })
        scope.cancel(); scope.coroutineContext[Job]!!.join()
    }

    @Test fun `rejection changes nothing and an interrupted approval completes without duplicating`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val e = env(scope)
        val rejected = e.importProposal("import-r", proposedWeek())
        val before = e.programs.library.value
        assertEquals(DecisionStatus.REJECTED, e.proposals.reject(rejected).status)
        assertEquals(before, e.programs.library.value)
        try { e.proposals.approve(rejected); fail() } catch (_: ProposalException) { }
        // Simulate a crash after the week was appended but before the decision was recorded.
        val week = proposedWeek("week-crash")
        val id = e.importProposal("import-c", week, version = 2)
        e.programs.importProgram(program.copy(blocks = program.blocks.map { if (it.id == "block-base") it.copy(weeks = it.weeks + week) else it }))
        val eval = e.proposals.evaluate(id)
        assertTrue(eval.alreadyApplied); assertTrue(eval.canApprove)
        assertEquals(DecisionStatus.APPROVED, e.proposals.approve(id).status)
        assertEquals(1, e.programs.library.value.programs.single().blocks.flatMap { it.weeks }.count { it.id == "week-crash" })
        scope.cancel(); scope.coroutineContext[Job]!!.join()
        // Decisions survive a restart.
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reloaded = store(e.dir.resolve("proposals.json"), scope2, ProposalStateSerializer).data.first()
        assertEquals(listOf(DecisionStatus.REJECTED, DecisionStatus.APPROVED), reloaded.decisions.map { it.status })
        scope2.cancel(); scope2.coroutineContext[Job]!!.join()
    }
}
