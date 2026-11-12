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

/** Contract §2: an explicit approval replaces a saved week and deletes its recorded sessions, together or not at all. */
class ProposalReplacementTest {
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

    /** Week two of the saved block, replaced by a proposal with new IDs and a single exercise. */
    private fun replacementWeek() = WeekPlan("week-two", "Semana dos ficticia", 1, listOf(
        SessionPlan("rep-day", "Día R", listOf(ExercisePrescription("rep-ex", "squat", "Sentadilla ficticia", listOf(
            SetPrescription("rep-set", 6, 2, PrescribedLoad(50.0, LoadUnit.KG, LoadBasis.TOTAL), 120)))))))

    private class Env(val programs: ProgramRepository, val exchanges: ExchangeRepository, val proposals: ProposalRepository,
                       val deleted: MutableList<Pair<String, String>>, val recorded: Int)

    private suspend fun env(scope: CoroutineScope, recordedSessions: Int = 2, failDelete: Boolean = false): Env {
        val dir = folder.newFolder()
        val programs = ProgramRepository(store(dir.resolve("programs.json"), scope, ProgramLibrarySerializer))
        val exchanges = ExchangeRepository(store(dir.resolve("exchanges.json"), scope, ExchangeArchiveSerializer))
        programs.restore(); exchanges.restore()
        programs.importProgram(program)
        val deleted = mutableListOf<Pair<String, String>>()
        val proposals = ProposalRepository(store(dir.resolve("proposals.json"), scope, ProposalStateSerializer), programs, exchanges,
            { setOf("squat", "press", "row", "lunge") }, { 1_800_000_000_000 },
            outlines = { emptyList() },
            weekRecords = { _, _ -> recordedSessions },
            deleteWeekRecords = { p, w ->
                if (failDelete) throw IllegalStateException("disco lleno")
                deleted += p to w
                recordedSessions
            })
        proposals.restore()
        return Env(programs, exchanges, proposals, deleted, recordedSessions)
    }

    private suspend fun Env.importReplacement(): String {
        exchanges.save(ExchangePackage(1, ExchangeType.WeekProposal, "import-replace", date, proposal = WeekProposal(
            "proposal-replace", 1, program.id, "block-base", "Agente ficticio", replacementWeek(), listOf("Sustituye la semana"), listOf("Motivo ficticio"))))
        return "import-replace"
    }

    private fun WeekPlan.dayIds() = days.map { it.id }

    private fun TrainingProgram.blockBase() = blocks.first { it.id == "block-base" }

    @Test fun `replacing a saved week needs an explicit replacement approval`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val e = env(scope)
        e.proposals.restore()
        val id = e.importReplacement()
        try { e.proposals.approve(id); fail() } catch (ex: ProposalException) { assertTrue(ex.message!!.contains("bloqueos")) }
        assertTrue(e.deleted.isEmpty())
    }

    @Test fun `an explicit replacement swaps the week and deletes its recorded sessions`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val e = env(scope, recordedSessions = 2)
        val id = e.importReplacement()
        val evaluation = e.proposals.evaluate(id, replacing = true)
        assertTrue(evaluation.warnings.any { it.code == "WEEK_REPLACEMENT" && it.message.contains("2 sesión") })
        val decision = e.proposals.approve(id, replaceWeek = true)

        assertEquals(DecisionStatus.APPROVED, decision.status)
        assertTrue(decision.replacedWeek)
        assertEquals(2, decision.deletedRecords)
        assertEquals(listOf(program.id to "week-two"), e.deleted)
        val saved = e.programs.library.value.programs.single { it.id == program.id }.blockBase().weeks.single { it.id == "week-two" }
        assertEquals(listOf("rep-day"), saved.dayIds())
    }

    @Test fun `a failed deletion restores the previous week and approves nothing`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val e = env(scope, failDelete = true)
        val before = e.programs.library.value.programs.single { it.id == program.id }.blockBase().weeks.single { it.id == "week-two" }
        val id = e.importReplacement()
        try { e.proposals.approve(id, replaceWeek = true); fail() } catch (ex: ProposalException) {
            assertTrue(ex.message!!.contains("Se conserva la planificación anterior"))
        }
        val after = e.programs.library.value.programs.single { it.id == program.id }.blockBase().weeks.single { it.id == "week-two" }
        assertEquals(before, after)
        assertNull(e.proposals.decisionFor(id))
    }
}
