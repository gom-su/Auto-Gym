package com.example.workoutpoc

import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.*
import com.example.workoutpoc.proposal.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Contract §1: explicit loads on every strength set, and warm-up or approach sets ordered before work sets. */
class ProposalLoadRulesTest {
    private val context = ExchangeCodec.decode(File("src/main/assets/examples/training-context-v1.json").readText()).context!!
    private val base = ExchangeCodec.decode(File("../mcp/examples/week-proposal-from-mcp.json").readText()).proposal!!

    private fun evaluate(proposal: WeekProposal) =
        ProposalRules.evaluate(proposal, ProgramLibrary(1, context.programs), setOf("squat"), RuleSettings())

    private fun WeekProposal.withFirstExercise(transform: (ExercisePrescription) -> ExercisePrescription): WeekProposal {
        val days = week.days.toMutableList()
        days[0] = days[0].copy(exercises = listOf(transform(days[0].exercises.first())) + days[0].exercises.drop(1))
        return copy(week = week.copy(days = days))
    }

    @Test fun `a strength set without a prescribed load blocks approval and names the set`() {
        val proposal = base.withFirstExercise { e ->
            e.copy(sets = listOf(e.sets.first().copy(load = null)) + e.sets.drop(1))
        }
        val evaluation = evaluate(proposal)
        val missing = evaluation.blocking.filter { it.code == "LOAD_MISSING" }
        assertEquals(1, missing.size)
        assertTrue(missing.single().message.contains("serie 1"))
        assertFalse(evaluation.canApprove)
    }

    @Test fun `a zero load that is prescribed is accepted`() {
        val proposal = base.withFirstExercise { e ->
            e.copy(sets = listOf(e.sets.first().copy(load = PrescribedLoad(0.0, LoadUnit.BODYWEIGHT, LoadBasis.BODYWEIGHT))) + e.sets.drop(1))
        }
        assertTrue(evaluate(proposal).blocking.none { it.code == "LOAD_MISSING" })
    }

    @Test fun `a warm-up set placed after a work set blocks approval`() {
        val proposal = base.withFirstExercise { e ->
            val work = e.sets.first().copy(kind = SetKind.WORK)
            val warm = e.sets.first().copy(id = "${e.id}-warm", kind = SetKind.WARMUP)
            e.copy(sets = listOf(work, warm) + e.sets.drop(1))
        }
        assertTrue(evaluate(proposal).blocking.any { it.code == "WARMUP_ORDER" })
    }

    @Test fun `warm-up sets before work sets are accepted`() {
        val proposal = base.withFirstExercise { e ->
            val warm = e.sets.first().copy(id = "${e.id}-warm", kind = SetKind.WARMUP)
            e.copy(sets = listOf(warm) + e.sets)
        }
        assertTrue(evaluate(proposal).blocking.none { it.code == "WARMUP_ORDER" })
    }
}
