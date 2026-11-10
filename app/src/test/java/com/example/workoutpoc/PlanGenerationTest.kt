package com.example.workoutpoc

import com.example.workoutpoc.agent.*
import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlanGenerationTest {
    private val original = ExchangeCodec.decode(File("src/main/assets/examples/plan-outline-v1.json").readText()).outline!!
    private val next = original.copy(id = "next-plan", programId = "next-program", version = 1)
    private val history = TrainingResults(listOf(SessionResult("previous-session", "previous-day", PerformanceStatus.COMPLETED,
        "2026-11-09T12:00:00Z", "2026-11-09T13:00:00Z", "Registro del ciclo anterior",
        listOf(SetResult("previous-set", null, original.days.first().exercises.first().exerciseId, PerformanceStatus.COMPLETED,
            10, 2, null, "2026-11-09T12:30:00Z", null)), emptyList())))
    private val context = TrainingContext(emptyList(), null, history, original.rules, original.goals,
        measurements = listOf(BodyMeasurement("mass", "2026-11-10T12:00:00Z", "MUSCLE_MASS", 34.0, "KG", "BIA")),
        outlines = listOf(original), nextWeeks = listOf(PlanOutlines.brief(original, null, emptyList()).copy(status = BriefStatus.CYCLE_COMPLETE)))
    private val questions = """{"questions":[{"topic":"Objetivo","text":"¿Mantienes el objetivo?","options":["Sí","Quiero cambiarlo"]}]}"""
    private fun pkg(o: PlanOutline = next) = ExchangeCodec.encode(ExchangePackage(1, ExchangeType.PlanOutline, "synthetic", "2026-11-10T12:00:00Z", outline = o))

    private class Chat(vararg replies: AgentReply) : AgentChat {
        val sent = mutableListOf<String>()
        private val replies = ArrayDeque(replies.toList())
        override suspend fun send(text: String): AgentReply { sent += text; return replies.removeFirst() }
    }

    @Test fun `new cycle receives old plan history and measurements and continues after questions`() = runTest {
        val chat = Chat(AgentReply(questions), AgentReply(pkg()))
        val generation = PlanGeneration(chat, context, newImportId = { "app-id" }, now = { "2026-11-10T15:00:00Z" })
        val json = ExchangeCodec.encode(ExchangePackage(1, ExchangeType.TrainingContext, "context", "2026-11-10T12:00:00Z", context = context))
        val asked = generation.start(json, "2026-11-10", "Tengo menos tiempo") as GenerationStep.Questions
        assertTrue(chat.sent[0].contains(original.id) && chat.sent[0].contains("MUSCLE_MASS") && chat.sent[0].contains("BIA"))
        assertTrue(chat.sent[0].contains("Tengo menos tiempo"))
        assertTrue(chat.sent[0].contains("previous-session") && chat.sent[0].contains("Registro del ciclo anterior"))
        val result = generation.answer(asked.questions.map { it to "Sí" }) as GenerationStep.Proposal
        assertEquals(next, result.pkg.outline)
        assertEquals("app-id", result.pkg.importId)
        assertEquals("2026-11-10T15:00:00Z", result.pkg.createdAt)
        assertTrue(chat.sent[1].contains("PlanOutline") && chat.sent[1].contains("Respuesta: Sí"))
        ExchangeCodec.decode(ExchangeCodec.encode(result.pkg))
    }

    @Test fun `cannot replace previous cycle or use a different number of weeks`() = runTest {
        for (bad in listOf(original, next.copy(programId = original.programId), next.copy(version = 2),
            next.copy(weeks = next.weeks.take(4), blocks = next.blocks.take(1)))) {
            val chat = Chat(AgentReply(pkg(bad)), AgentReply(pkg(bad)))
            try { PlanGeneration(chat, context).start("{}", "2026-11-10", null); fail("Accepted invalid cycle") }
            catch (e: AgentException) { assertTrue(e.message!!.contains("No se guardó nada")) }
            assertEquals(2, chat.sent.size)
        }
    }

    @Test fun `archived identities are reserved and one correction can produce a fresh plan`() = runTest {
        val chat = Chat(AgentReply(pkg()), AgentReply(pkg(next.copy(id = "fresh-plan", programId = "fresh-program"))))
        val result = PlanGeneration(chat, context, listOf(next)).start("{}", "2026-11-10", null) as GenerationStep.Proposal
        assertEquals("fresh-program", result.pkg.outline!!.programId)
        assertTrue(chat.sent[1].contains("programId nuevo"))
    }

    @Test fun `first time setup and unfinished cycle never send to API`() = runTest {
        for (briefs in listOf(emptyList(), context.nextWeeks.map { it.copy(status = BriefStatus.READY) })) {
            val chat = Chat()
            try { PlanGeneration(chat, context.copy(nextWeeks = briefs)).start("{}", "2026-11-10", null); fail() }
            catch (e: AgentException) { assertTrue(e.message!!.contains("primer plan")) }
            assertTrue(chat.sent.isEmpty())
        }
    }

    @Test fun `cycle conversation has the same question limit and refuses weeks or truncated replies`() = runTest {
        val chat = Chat(AgentReply(questions), AgentReply(questions), AgentReply(questions), AgentReply(pkg()))
        val generation = PlanGeneration(chat, context)
        val first = generation.start("{}", "2026-11-10", null) as GenerationStep.Questions
        val second = generation.answer(first.questions.map { it to "Sí" }) as GenerationStep.Questions
        assertTrue(generation.answer(second.questions.map { it to "Sí" }) is GenerationStep.Proposal)
        assertTrue(chat.sent.last().contains("PlanOutline"))
        val week = File("src/main/assets/examples/week-proposal-v1.json").readText()
        val wrong = Chat(AgentReply(week), AgentReply(week))
        try { PlanGeneration(wrong, context).start("{}", "2026-11-10", null); fail() }
        catch (e: AgentException) { assertTrue(e.message!!.contains("PlanOutline")) }
        val cut = Chat(AgentReply(pkg(), truncated = true))
        try { PlanGeneration(cut, context).start("{}", "2026-11-10", null); fail() }
        catch (e: AgentException) { assertTrue(e.message!!.contains("se cortó")) }
        assertEquals(1, cut.sent.size)
    }

    @Test fun `new cycle action requires finished days key and no outstanding questions`() {
        val state = GenerateUiState(ready = true, key = ApiKeyStatus(present = true), brief = context.nextWeeks.single())
        assertTrue(state.newCycle)
        assertFalse(state.canGenerate)
        assertTrue(state.copy(cycleReady = true).canGenerate)
        assertFalse(state.copy(cycleReady = true, busy = true).canGenerate)
        assertFalse(state.copy(cycleReady = true, key = ApiKeyStatus()).canGenerate)
        assertFalse(state.copy(cycleReady = true, questions = listOf(AgentQuestion(text = "¿Cambio?", options = listOf("Sí", "No")))).canGenerate)
    }
}
