package com.example.workoutpoc

import com.example.workoutpoc.exchange.ExchangeCodec
import com.example.workoutpoc.exchange.ExchangeType
import com.example.workoutpoc.planning.ProgramCodec
import com.example.workoutpoc.planning.ProgramLibrary
import com.example.workoutpoc.proposal.ProposalRules
import com.example.workoutpoc.proposal.RuleSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * The JSON examples inside docs/AGENT_PROMPTS.md are what external agents copy: they must pass the app's own checks.
 * The plan example decodes as a PlanOutline; the week example is week 1 of that plan, approvable with no blocking
 * issue and no plan-target warning, and approving it creates a valid program.
 */
class AgentPromptExamplesTest {
    private val examples = Regex("```json\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        .findAll(File("../docs/AGENT_PROMPTS.md").readText().replace("\r\n", "\n")).map { it.groupValues[1] }.toList()

    @Test fun `both prompt examples are accepted by the app`() {
        assertEquals("One plan example and one week example", 2, examples.size)
        val plan = ExchangeCodec.decode(examples[0])
        assertEquals(ExchangeType.PlanOutline, plan.type)
        val week = ExchangeCodec.decode(examples[1])
        assertEquals(ExchangeType.WeekProposal, week.type)

        val outline = plan.outline!!
        val evaluation = ProposalRules.evaluate(week.proposal!!, ProgramLibrary(1, emptyList()), emptySet(), RuleSettings(), outline)
        assertTrue("Blocking: ${evaluation.blocking}", evaluation.canApprove)
        // New exercise IDs are expected on a first week; anything about the plan's targets is not.
        assertEquals(emptyList<String>(), evaluation.warnings.map { it.code }.filter { it != "NEW_EXERCISE" })

        val created = ProposalRules.appliedProgram(week.proposal!!, null, outline)!!
        ProgramCodec.validate(created)
        assertEquals(outline.programName, created.name)
    }
}
