package com.example.workoutpoc

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.*
import com.example.workoutpoc.proposal.*
import kotlinx.coroutines.CoroutineScope
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

/** A multi-week plan gives the agent the week to plan and its targets; each week still needs the user's approval. */
class PlanOutlineTest {
    @get:Rule val folder = TemporaryFolder()
    private val fixture = File("src/main/assets/examples/plan-outline-v1.json").readText()
    private val outline = ExchangeCodec.decode(fixture).outline!!

    /** A week built from the plan's day templates and that week's targets, with IDs unique to week [n]. */
    private fun week(n: Int, o: PlanOutline = outline, reps: Int? = null, rir: Int? = null, sets: Int? = null): WeekPlan {
        val w = o.weeks[n - 1]
        return WeekPlan("w$n", "Semana $n", 1, o.days.map { d ->
            SessionPlan("w$n-${d.id}", d.name, d.exercises.map { e ->
                val t = w.targets.find { e.exerciseId in it.exerciseIds } ?: w.targets.find { it.exerciseIds.isEmpty() }
                ExercisePrescription("w$n-${d.id}-${e.exerciseId}", e.exerciseId, e.name, List(sets ?: t?.sets ?: e.sets ?: 2) { s ->
                    SetPrescription("w$n-${d.id}-${e.exerciseId}-$s", reps ?: t?.repsMax, rir ?: t?.rirMax, PrescribedLoad(20.0, LoadUnit.KG, LoadBasis.TOTAL))
                })
            })
        })
    }
    private fun proposal(n: Int, blockId: String = outline.weeks[n - 1].blockId, w: WeekPlan = week(n)) =
        WeekProposal("p$n", 1, outline.programId, blockId, "test", w, emptyList(), listOf("Semana $n del plan."))
    private fun trained(w: WeekPlan) = w.days.map { d ->
        SessionResult("s-${d.id}", d.id, PerformanceStatus.COMPLETED, null, null, null,
            listOf(SetResult("set", null, d.exercises.first().exerciseId, PerformanceStatus.COMPLETED, 10, 2, null, null, null)), emptyList())
    }
    private fun library(p: TrainingProgram?) = ProgramLibrary(1, listOfNotNull(p))

    @Test fun `fixture is a valid 12-week plan with deloads at 4, 8 and 12`() {
        assertEquals(ExchangeType.PlanOutline, ExchangeCodec.decode(fixture).type)
        assertEquals(12, outline.weeks.size)
        assertEquals(listOf(4, 8, 12), outline.weeks.filter { it.deload }.map { it.number })
        assertEquals(listOf("faro-b1", "faro-b2", "faro-b3"), outline.blocks.map { it.id })
        assertEquals(1 to 4, PlanOutlines.weekInBlock(outline, 5))
        assertEquals(4 to 4, PlanOutlines.weekInBlock(outline, 12))
        assertEquals(outline, ExchangeCodec.decode(ExchangeCodec.encode(ExchangeCodec.decode(fixture))).outline)
    }

    @Test fun `invalid plans are rejected with a reason`() {
        fun rejects(o: PlanOutline) = assertThrows(ProgramValidationException::class.java) { PlanOutlines.validate(o) }
        rejects(outline.copy(weeks = outline.weeks.filter { it.number != 3 }))
        rejects(outline.copy(weeks = outline.weeks.mapIndexed { i, w -> if (i == 9) w.copy(blockId = "faro-b1") else w }))
        rejects(outline.copy(blocks = outline.blocks.reversed()))
        rejects(outline.copy(weeks = outline.weeks.map { it.copy(targets = it.targets + WeekTarget("Otro", listOf("inventado"))) }))
        rejects(outline.copy(weeks = outline.weeks.map { it.copy(targets = listOf(WeekTarget("Mal", repsMin = 12, repsMax = 8))) }))
        rejects(outline.copy(weeks = outline.weeks.map { it.copy(targets = listOf(WeekTarget("Uno"), WeekTarget("Dos"))) }))
        rejects(outline.copy(weeks = emptyList()))
        assertThrows(ProgramValidationException::class.java) { ExchangeCodec.decode(fixture.replace("\"deload\": false", "\"deload\": false, \"extra\": 1")) }
    }

    @Test fun `brief walks the plan week by week and stops after week 12`() {
        var program: TrainingProgram? = null
        val sessions = mutableListOf<SessionResult>()
        val first = PlanOutlines.brief(outline, null, sessions)
        assertEquals(BriefStatus.READY, first.status); assertEquals(1, first.weekNumber); assertNull(first.referenceWeekNumber)
        // The brief shipped with the MCP example context is exactly what the app computes.
        val shipped = ExchangeCodec.decode(File("../mcp/examples/training-context-with-plan.json").readText()).context!!
        assertEquals(first, shipped.nextWeeks.single())
        for (n in 1..12) {
            val b = PlanOutlines.brief(outline, program, sessions)
            assertEquals(BriefStatus.READY, b.status); assertEquals(n, b.weekNumber)
            assertEquals(outline.weeks[n - 1].blockId, b.blockId); assertEquals(outline.weeks[n - 1], b.target)
            assertEquals((n - 1) % 4 + 1, b.weekInBlock)
            val p = proposal(n)
            val evaluation = ProposalRules.evaluate(p, library(program), emptySet(), RuleSettings(newExercise = Severity.OFF), outline, sessions)
            assertTrue("semana $n: ${evaluation.issues}", evaluation.issues.isEmpty())
            program = ProposalRules.appliedProgram(p, program, outline)!!
            assertEquals(BriefStatus.ALREADY_PLANNED, PlanOutlines.brief(outline, program, sessions).status)
            sessions += trained(p.week).map { it.copy(id = "w$n-${it.id}") }
        }
        assertEquals(3, program!!.blocks.size)
        assertEquals(List(3) { 4 }, program.blocks.map { it.weeks.size })
        val done = PlanOutlines.brief(outline, program, sessions)
        assertEquals(BriefStatus.CYCLE_COMPLETE, done.status); assertNull(done.weekNumber); assertEquals(12, done.referenceWeekNumber)
        val thirteen = ProposalRules.evaluate(proposal(12, w = week(12).copy(id = "w13", days = week(12).days.map { d ->
            d.copy(id = "x" + d.id, exercises = d.exercises.map { e -> e.copy(id = "x" + e.id, sets = e.sets.map { it.copy(id = "x" + it.id) }) }) })),
            library(program), emptySet(), RuleSettings(), outline, sessions)
        assertTrue(thirteen.blocking.any { it.code == "OUTLINE_COMPLETE" })
    }

    @Test fun `reference week is the highest with real records, not merely planned`() {
        val w1 = week(1); val w2 = week(2)
        val program = TrainingProgram(outline.programId, outline.programName, listOf(TrainingBlock("faro-b1", "B1", listOf(w1, w2))))
        val notPerformed = trained(w2).map { it.copy(status = PerformanceStatus.NOT_PERFORMED) }
        val b = PlanOutlines.brief(outline, program, trained(w1).take(2) + notPerformed)
        assertEquals(1, b.referenceWeekNumber); assertEquals(BriefStatus.ALREADY_PLANNED, b.status); assertEquals(2, b.weekNumber)
        assertEquals(listOf("Día C", "Día D"), b.referenceDaysWithoutData)
    }

    @Test fun `proposals off the plan are blocked or warned`() {
        val program = ProposalRules.appliedProgram(proposal(1), null, outline)!!
        val sessions = trained(week(1))
        fun issues(p: WeekProposal, s: List<SessionResult> = sessions) =
            ProposalRules.evaluate(p, library(program), emptySet(), RuleSettings(newExercise = Severity.OFF), outline, s).issues.map { it.code }
        assertTrue("OUTLINE_BLOCK" in issues(proposal(2, blockId = "faro-b2")))
        assertTrue("OUTLINE_REPS" in issues(proposal(2, w = week(2, reps = 20))))
        assertTrue("OUTLINE_RIR" in issues(proposal(2, w = week(2, rir = 0))))
        assertTrue("OUTLINE_SETS" in issues(proposal(2, w = week(2, sets = 5))))
        assertTrue("OUTLINE_NO_DATA" in issues(proposal(2), emptyList()))
        val extra = week(2).let { w -> w.copy(days = w.days.map { d -> if (d.id != "w2-A") d else d.copy(exercises = d.exercises +
            ExercisePrescription("w2-A-x", "otro", "Otro ficticio", listOf(SetPrescription("w2-A-x-0", 10, 2)))) }) }
        assertTrue("OUTLINE_EXERCISE" in issues(proposal(2, w = extra)))
        assertEquals(emptyList<String>(), issues(proposal(2)))
        // Unknown block outside the plan, and no plan at all, keep the original blocking behaviour.
        assertTrue("BLOCK_MISSING" in issues(proposal(2, blockId = "nope")))
        assertTrue(ProposalRules.evaluate(proposal(1), library(null), emptySet(), RuleSettings()).blocking.any { it.code == "PROGRAM_MISSING" })
    }

    @Test fun `deload week with more volume than the last real week is flagged`() {
        var program: TrainingProgram? = null
        val sessions = mutableListOf<SessionResult>()
        (1..3).forEach { n -> program = ProposalRules.appliedProgram(proposal(n), program, outline); sessions += trained(week(n)).map { it.copy(id = "$n${it.id}") } }
        val heavy = ProposalRules.evaluate(proposal(4, w = week(4, sets = 4)), library(program), emptySet(), RuleSettings(newExercise = Severity.OFF), outline, sessions)
        assertTrue(heavy.issues.map { it.code }.toString(), heavy.issues.any { it.code == "OUTLINE_DELOAD" })
        // After the deload, week 5 is compared with week 3 (the last week of real work), not with the deload.
        program = ProposalRules.appliedProgram(proposal(4), program, outline); sessions += trained(week(4)).map { it.copy(id = "4${it.id}") }
        val five = ProposalRules.evaluate(proposal(5), library(program), emptySet(), RuleSettings(newExercise = Severity.OFF), outline, sessions)
        assertEquals("w3", five.base?.id)
        assertTrue(five.differences.any { it.contains("Bloque 2") })
    }

    @Test fun `context carries plans and next weeks, and rejects a brief without its plan`() {
        val brief = PlanOutlines.brief(outline, null, emptyList())
        val ctx = ExchangePackage(1, ExchangeType.TrainingContext, "ctx", "2026-11-07T12:00:00Z",
            context = TrainingContext(emptyList(), null, TrainingResults(emptyList()), emptyList(), emptyList(), outlines = listOf(outline), nextWeeks = listOf(brief)))
        assertEquals(ctx, ExchangeCodec.decode(ExchangeCodec.encode(ctx)))
        assertThrows(ProgramValidationException::class.java) {
            ExchangeCodec.encode(ctx.copy(context = ctx.context!!.copy(outlines = emptyList())))
        }
        // Older contexts without plans still decode.
        assertTrue(ExchangeCodec.decode(File("src/main/assets/examples/training-context-v1.json").readText()).context!!.outlines.isEmpty())
    }

    private fun <T> store(file: File, scope: CoroutineScope, s: Serializer<T>): DataStore<T> =
        DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM, object : OkioSerializer<T> {
            override val defaultValue: T = s.defaultValue
            override suspend fun readFrom(source: BufferedSource): T = s.readFrom(source.inputStream())
            override suspend fun writeTo(t: T, sink: BufferedSink) { s.writeTo(t, sink.outputStream()) }
        }) { file.absolutePath.toPath() }, scope = scope)

    private suspend fun refused(block: suspend () -> Unit) {
        try { block(); fail("Debía rechazarse.") } catch (_: ProgramValidationException) {}
    }

    @Test fun `activation is explicit, versioned, and approval of week 1 creates the program`() = runTest {
        val dir = folder.newFolder()
        val outlines = PlanOutlineRepository(store(dir.resolve("outlines.json"), backgroundScope, PlanOutlineStoreSerializer))
        val programs = ProgramRepository(store(dir.resolve("programs.json"), backgroundScope, ProgramLibrarySerializer))
        val exchanges = ExchangeRepository(store(dir.resolve("exchanges.json"), backgroundScope, ExchangeArchiveSerializer))
        outlines.restore(); programs.restore(); exchanges.restore()
        val proposals = ProposalRepository(store(dir.resolve("proposals.json"), backgroundScope, ProposalStateSerializer), programs, exchanges,
            { emptySet() }, { 1_800_000_000_000 }, outlines = { outlines.outlines.value.active })
        proposals.restore()

        assertEquals(ActivationResult.ACTIVATED, outlines.activate(outline, null))
        assertEquals(ActivationResult.ALREADY_ACTIVE, outlines.activate(outline, null))
        refused { outlines.activate(outline.copy(name = "Otro"), null) }
        refused { outlines.activate(outline.copy(id = "otro-plan", version = 2), null) }

        exchanges.save(ExchangePackage(1, ExchangeType.WeekProposal, "imp-1", "2026-11-07T12:00:00Z", proposal = proposal(1)))
        assertTrue(proposals.evaluate("imp-1").canApprove)
        proposals.approve("imp-1")
        val saved = programs.library.value.programs.single()
        assertEquals(outline.programName, saved.name)
        assertEquals(listOf("faro-b1"), saved.blocks.map { it.id })
        assertEquals("Bloque 1 — Base", saved.blocks.single().name)

        // A new version that moves an already saved week to another block is refused; a compatible one replaces it.
        val moved = outline.copy(version = 2, blocks = listOf(OutlineBlock("nuevo", "Nuevo")) + outline.blocks.drop(1),
            weeks = outline.weeks.map { if (it.blockId == "faro-b1") it.copy(blockId = "nuevo") else it })
        refused { outlines.activate(moved, saved) }
        assertEquals(ActivationResult.REPLACED, outlines.activate(outline.copy(version = 2, name = "Faro v2"), saved))
        assertEquals(listOf(outline), outlines.outlines.value.replaced)
        assertEquals("Faro v2", outlines.outlines.value.forProgram(outline.programId)!!.name)
    }

    @Test fun `only one plan is active and the others are kept as inactive`() = runTest {
        val outlines = PlanOutlineRepository(store(folder.newFolder().resolve("outlines.json"), backgroundScope, PlanOutlineStoreSerializer))
        outlines.restore()
        val other = outline.copy(id = "otro-plan", programId = "otro-programa", programName = "Otro programa")
        assertEquals(ActivationResult.ACTIVATED, outlines.activate(outline, null))
        assertEquals(ActivationResult.ACTIVATED, outlines.activate(other, null))
        assertEquals(listOf(other), outlines.outlines.value.active)
        assertEquals(listOf(outline), outlines.outlines.value.inactive)
        // Activating it again swaps them back; nothing is lost.
        assertEquals(ActivationResult.ACTIVATED, outlines.activate(outline, null))
        assertEquals(listOf(outline), outlines.outlines.value.active)
        assertEquals(listOf(other), outlines.outlines.value.inactive)
    }

    @Test fun `plan written by the MCP server decodes in the app`() {
        val pkg = ExchangeCodec.decode(File("../mcp/examples/plan-outline-from-mcp.json").readText())
        assertEquals(ExchangeType.PlanOutline, pkg.type)
        assertTrue(pkg.importId.startsWith("mcp-"))
        assertEquals(outline, pkg.outline)
    }
}
