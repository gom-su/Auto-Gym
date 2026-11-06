package com.example.workoutpoc

import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.ProgramLibrary
import com.example.workoutpoc.proposal.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * INT-01 interoperability: a proposal written by the MCP server (mcp/examples, produced by mcp/auto_gym_mcp.py from the
 * synthetic context) is a valid interchange v1 package for the app, and passes integrity rules. Approval stays in the app.
 */
class McpInteropTest {
    @Test fun `proposal written by the MCP server decodes and validates in the app`() {
        val text = File("../mcp/examples/week-proposal-from-mcp.json").readText()
        val pkg = ExchangeCodec.decode(text)
        assertEquals(ExchangeType.WeekProposal, pkg.type)
        assertTrue(pkg.importId.startsWith("mcp-"))
        val proposal = pkg.proposal!!
        assertEquals("Agente vía MCP", proposal.source)
        val context = ExchangeCodec.decode(File("src/main/assets/examples/training-context-v1.json").readText()).context!!
        val evaluation = ProposalRules.evaluate(proposal, ProgramLibrary(1, context.programs), setOf("squat"), RuleSettings())
        assertTrue(evaluation.blocking.map { it.message }.toString(), evaluation.canApprove)
        assertFalse(evaluation.alreadyApplied)
    }
}
