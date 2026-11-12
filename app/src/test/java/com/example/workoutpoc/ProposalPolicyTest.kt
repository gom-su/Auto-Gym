package com.example.workoutpoc

import com.example.workoutpoc.exchange.WeekProposal
import com.example.workoutpoc.planning.*
import com.example.workoutpoc.proposal.ProposalRules
import com.example.workoutpoc.proposal.RuleSettings
import org.junit.Assert.*
import org.junit.Test

/** Decisions §1 and §2: only the latest week of a block is regenerable, and a program can require cardio per day. */
class ProposalPolicyTest {
    private val saved = ProgramCodec.decode(javaClass.classLoader!!.getResourceAsStream("examples/variable-program-v1.json")!!
        .bufferedReader().use { it.readText() })

    private fun proposal(weekId: String, day: SessionPlan) = WeekProposal("proposal-policy", 1, saved.id, "block-base", "Agente ficticio",
        WeekPlan(weekId, "Semana ficticia", 1, listOf(day)), emptyList(), emptyList())

    private fun evaluate(p: WeekProposal, program: TrainingProgram, replacing: Boolean) =
        ProposalRules.evaluate(p, ProgramLibrary(1, listOf(program)), setOf("squat", "press", "row"), RuleSettings(), replacing = replacing,
            replacedRecords = 1)

    private val strengthDay = SessionPlan("policy-day", "Día A", listOf(ExercisePrescription("policy-ex", "squat", "Sentadilla",
        listOf(SetPrescription("policy-set", 8, 2, PrescribedLoad(40.0, LoadUnit.KG, LoadBasis.TOTAL), 90)))))

    @Test fun `an intermediate week of a block cannot be regenerated`() {
        val first = saved.blocks.first { it.id == "block-base" }.weeks.first().id
        val evaluation = evaluate(proposal(first, strengthDay), saved, replacing = true)
        assertTrue(evaluation.blocking.any { it.code == "WEEK_NOT_LAST" })
        assertFalse(evaluation.canApprove)
    }

    @Test fun `the latest week of a block can be regenerated with an explicit warning`() {
        val last = saved.blocks.first { it.id == "block-base" }.weeks.last().id
        val evaluation = evaluate(proposal(last, strengthDay), saved, replacing = true)
        assertTrue(evaluation.warnings.any { it.code == "WEEK_REPLACEMENT" })
        assertTrue(evaluation.blocking.none { it.code == "WEEK_NOT_LAST" })
    }

    @Test fun `a cardio rule of the program is enforced on the proposed day`() {
        val withRule = saved.copy(cardioRules = listOf(CardioRule("policy-day", 1800)))
        val evaluation = evaluate(proposal("week-proposed-policy", strengthDay), withRule, replacing = false)
        val violations = evaluation.blocking.filter { it.code == "CARDIO_DISTRIBUTION" }
        assertEquals(listOf("Día A: falta el cardio obligatorio de 30 min."), violations.map { it.message })
    }

    @Test fun `a program without cardio rules is not affected`() {
        val evaluation = evaluate(proposal("week-proposed-policy", strengthDay), saved, replacing = false)
        assertTrue(evaluation.blocking.none { it.code == "CARDIO_DISTRIBUTION" })
    }
}
