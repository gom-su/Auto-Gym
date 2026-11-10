package com.example.workoutpoc

import com.example.workoutpoc.agent.*
import com.example.workoutpoc.exchange.ExchangeCodec
import com.example.workoutpoc.exchange.WeekProposal
import com.example.workoutpoc.planning.BriefStatus
import com.example.workoutpoc.planning.PlanOutlines
import com.example.workoutpoc.planning.ProgramLibrary
import com.example.workoutpoc.proposal.ProposalRules
import com.example.workoutpoc.proposal.RuleSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * In-app generation: the app accepts only a proposal for the week its own brief names, that decodes and has no
 * blocking issue; the model may ask first; an unacceptable reply is sent back once and never saved.
 */
class WeekGenerationTest {
    private val examples = Regex("```json\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        .findAll(File("../docs/AGENT_PROMPTS.md").readText().replace("\r\n", "\n")).map { it.groupValues[1] }.toList()
    private val outline = ExchangeCodec.decode(examples[0]).outline!!
    private val week = examples[1]
    private val brief = PlanOutlines.brief(outline, null, emptyList())
    private val questions = """{"questions":[{"topic":"Hombro","text":"¿Cómo está el hombro?","options":["Sin dolor","Molestia leve"]}]}"""

    private class ScriptedChat(vararg replies: AgentReply) : AgentChat {
        val sent = mutableListOf<String>()
        private val queue = ArrayDeque(replies.toList())
        override suspend fun send(text: String): AgentReply { sent += text; return queue.removeFirst() }
    }

    private val rules: suspend (WeekProposal) -> List<String> = { p ->
        ProposalRules.evaluate(p, ProgramLibrary(1, emptyList()), emptySet(), RuleSettings(), outline).blocking.map { it.message }
    }

    private fun generation(chat: AgentChat, taken: Set<Pair<String, Int>> = emptySet(), blocking: suspend (WeekProposal) -> List<String> = rules) =
        WeekGeneration(chat, brief, taken, blocking, { "11111111-1111-1111-1111-111111111111" }, { "2026-11-10T08:00:00Z" })

    private suspend fun fails(block: suspend () -> Unit): String =
        try { block(); fail("Expected the generation to fail"); "" } catch (e: AgentException) { e.message!! }

    @Test fun `the brief of the example plan asks for week 1`() {
        assertEquals(BriefStatus.READY, brief.status)
        assertEquals(1, brief.weekNumber)
    }

    @Test fun `a valid package is accepted even inside a code block, and the app stamps identity, time and origin`() = runTest {
        val chat = ScriptedChat(AgentReply("Aquí está:\n```json\n$week\n```"))
        val step = generation(chat).start("{\"x\":1}", "2026-11-10", "Dormí mal") as GenerationStep.Proposal
        assertEquals("11111111-1111-1111-1111-111111111111", step.pkg.importId)
        assertEquals("2026-11-10T08:00:00Z", step.pkg.createdAt)
        assertEquals(WeekPrompts.SOURCE, step.pkg.proposal!!.source)
        assertEquals("s1", step.pkg.proposal!!.week.id)
        assertEquals(1, chat.sent.size)
        assertTrue(chat.sent[0].contains("<contexto>\n{\"x\":1}\n</contexto>"))
        assertTrue(chat.sent[0].contains("Dormí mal") && chat.sent[0].contains("2026-11-10"))
        // What is archived is a package the app's own import accepts.
        ExchangeCodec.decode(ExchangeCodec.encode(step.pkg))
    }

    @Test fun `questions are returned to the user and their answers continue the same conversation`() = runTest {
        val chat = ScriptedChat(AgentReply(questions), AgentReply(week))
        val running = generation(chat)
        val asked = (running.start("{}", "2026-11-10", null) as GenerationStep.Questions).questions
        assertEquals(listOf("Sin dolor", "Molestia leve"), asked.single().options)
        assertTrue(running.answer(listOf(asked[0] to "Sin dolor")) is GenerationStep.Proposal)
        assertTrue(chat.sent[1].contains("¿Cómo está el hombro?") && chat.sent[1].contains("Respuesta: Sin dolor"))
    }

    @Test fun `a third round of questions is refused and the model is told to propose`() = runTest {
        val chat = ScriptedChat(AgentReply(questions), AgentReply(questions), AgentReply(questions), AgentReply(week))
        val running = generation(chat)
        val first = (running.start("{}", "2026-11-10", null) as GenerationStep.Questions).questions
        val second = (running.answer(listOf(first[0] to "Sin dolor")) as GenerationStep.Questions).questions
        assertTrue(running.answer(listOf(second[0] to "Sin dolor")) is GenerationStep.Proposal)
        assertTrue(chat.sent[3].contains("Ya hiciste tus preguntas"))
    }

    @Test fun `being told to propose does not spend the correction round`() = runTest {
        val wrongBlock = week.replace("\"blockId\":\"b1\"", "\"blockId\":\"b9\"")
        val chat = ScriptedChat(AgentReply(questions), AgentReply(questions), AgentReply(questions), AgentReply(wrongBlock), AgentReply(week))
        val running = generation(chat)
        val first = (running.start("{}", "2026-11-10", null) as GenerationStep.Questions).questions
        val second = (running.answer(listOf(first[0] to "Sin dolor")) as GenerationStep.Questions).questions
        assertTrue(running.answer(listOf(second[0] to "Sin dolor")) is GenerationStep.Proposal)
        assertTrue(chat.sent[4].contains("blockId debe ser b1"))
    }

    @Test fun `asking again after being told to propose ends the generation`() = runTest {
        val chat = ScriptedChat(AgentReply(questions), AgentReply(questions), AgentReply(questions), AgentReply(questions), AgentReply(questions))
        val running = generation(chat)
        val first = (running.start("{}", "2026-11-10", null) as GenerationStep.Questions).questions
        val second = (running.answer(listOf(first[0] to "Sin dolor")) as GenerationStep.Questions).questions
        assertTrue(fails { running.answer(listOf(second[0] to "Sin dolor")) }.contains("No se guardó nada"))
        assertEquals(5, chat.sent.size)
    }

    @Test fun `a proposal for another block is sent back once with the reason and then accepted`() = runTest {
        val chat = ScriptedChat(AgentReply(week.replace("\"blockId\":\"b1\"", "\"blockId\":\"b9\"")), AgentReply(week))
        assertTrue(generation(chat).start("{}", "2026-11-10", null) is GenerationStep.Proposal)
        assertEquals(2, chat.sent.size)
        assertTrue(chat.sent[1].contains("blockId debe ser b1"))
    }

    @Test fun `blocking issues from the rules are sent back for correction`() = runTest {
        var calls = 0
        val chat = ScriptedChat(AgentReply(week), AgentReply(week))
        val step = generation(chat, blocking = { if (calls++ == 0) listOf("El ID s1 ya existe en el programa.") else emptyList() })
            .start("{}", "2026-11-10", null)
        assertTrue(step is GenerationStep.Proposal)
        assertTrue(chat.sent[1].contains("El ID s1 ya existe en el programa."))
    }

    @Test fun `a second unacceptable reply ends the generation with nothing to save`() = runTest {
        val chat = ScriptedChat(AgentReply("No puedo ayudar con eso."), AgentReply("{\"schemaVersion\":1}"))
        val message = fails { generation(chat).start("{}", "2026-11-10", null) }
        assertTrue(message, message.contains("No se guardó nada"))
        assertEquals(2, chat.sent.size)
    }

    @Test fun `unknown fields and other package types are not accepted`() = runTest {
        val chat = ScriptedChat(AgentReply(week.replace("\"differences\"", "\"approve\":true,\"differences\"")), AgentReply(examples[0]))
        val message = fails { generation(chat).start("{}", "2026-11-10", null) }
        assertTrue(message, message.contains("WeekProposal"))
        assertTrue(chat.sent[1].contains("JSON incompatible"))
    }

    @Test fun `a reply cut at the output limit is never parsed`() = runTest {
        val chat = ScriptedChat(AgentReply(week.dropLast(40), truncated = true))
        assertTrue(fails { generation(chat).start("{}", "2026-11-10", null) }.contains("se cortó"))
        assertEquals(1, chat.sent.size)
    }

    @Test fun `a regenerated proposal takes the next free version`() = runTest {
        val step = generation(ScriptedChat(AgentReply(week)), taken = setOf("prop-s1" to 1, "prop-s1" to 2))
            .start("{}", "2026-11-10", null) as GenerationStep.Proposal
        assertEquals(3, step.pkg.proposal!!.version)
    }

    @Test fun `nothing is sent when the app says there is no week to plan`() = runTest {
        val chat = ScriptedChat()
        val planned = brief.copy(status = BriefStatus.ALREADY_PLANNED, message = "La semana 1 ya está en el programa.")
        val message = fails { WeekGeneration(chat, planned, emptySet(), rules).start("{}", "2026-11-10", null) }
        assertEquals("La semana 1 ya está en el programa.", message)
        assertTrue(chat.sent.isEmpty())
    }

    @Test fun `malformed questions are refused with the reason`() {
        listOf("""{"questions":[]}""", """{"questions":[{"text":"¿A?","options":["solo una"]}]}""",
            """{"questions":[{"text":" ","options":["a","b"]}]}""", """{"questions":[{"text":"¿A?","options":["a","a"]}]}""",
            """{"questions":"no"}""").forEach { bad ->
            try { WeekGeneration.questions(bad); fail("Accepted $bad") } catch (_: AgentException) { }
        }
        assertEquals("Tema", WeekGeneration.questions("""{"questions":[{"topic":" Tema ","text":"¿A?","options":["a","b"],"extra":1}]}""").single().topic)
    }

    @Test fun `the system prompt is the weekly prompt of the docs plus the in-app mode`() {
        val asset = File("src/main/assets/prompts/week-proposal.txt").readText().replace("\r\n", "\n")
        val doc = Regex("````text\\n(.*?)\\n````", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("../docs/AGENT_PROMPTS.md").readText().replace("\r\n", "\n")).map { it.groupValues[1] }.toList()
        assertEquals("Two prompts in the docs", 2, doc.size)
        assertEquals("The app must send the weekly prompt exactly as documented", doc[1], asset.trimEnd())
        val system = WeekPrompts.system(asset)
        assertTrue(system.startsWith(doc[1]))
        assertTrue(system.contains("Modo dentro de la app") && system.contains("\"questions\""))
    }
}
