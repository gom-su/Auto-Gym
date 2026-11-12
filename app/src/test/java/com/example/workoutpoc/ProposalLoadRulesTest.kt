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

    @Test fun `an exercise with only approach sets is blocked`() {
        val proposal = base.withFirstExercise { e -> e.copy(sets = e.sets.map { it.copy(kind = SetKind.APPROACH) }) }
        assertTrue(evaluate(proposal).blocking.any { it.code == "NO_WORK_SETS" })
    }

    @Test fun `a warm-up heavier than the work sets of its exercise is blocked`() {
        val proposal = base.withFirstExercise { e ->
            val template = e.sets.first().load ?: error("fixture without load")
            val heaviest = e.sets.filter { it.kind == SetKind.WORK }.mapNotNull { it.load?.value }.maxOrNull() ?: 0.0
            val warm = e.sets.first().copy(id = "${e.id}-warm", kind = SetKind.WARMUP, load = template.copy(value = heaviest + 10))
            e.copy(sets = listOf(warm) + e.sets)
        }
        assertTrue(evaluate(proposal).blocking.any { it.code == "NON_WORK_LOAD" })
    }

    @Test fun `a warm-up in another unit or basis than its work sets is blocked, not skipped`() {
        val proposal = base.withFirstExercise { e ->
            val warm = e.sets.first().copy(id = "${e.id}-warm", kind = SetKind.WARMUP,
                load = PrescribedLoad(200.0, LoadUnit.LB, LoadBasis.TOTAL))
            e.copy(sets = listOf(warm) + e.sets)
        }
        assertTrue(evaluate(proposal).blocking.any { it.code == "NON_WORK_LOAD" })
    }

    @Test fun `more than four warm-up or approach sets on one exercise are blocked`() {
        val proposal = base.withFirstExercise { e ->
            val light = e.sets.first().copy(kind = SetKind.APPROACH)
            e.copy(sets = (1..5).map { light.copy(id = "${e.id}-ap$it") } + e.sets)
        }
        assertTrue(evaluate(proposal).blocking.any { it.code == "NON_WORK_CAP" })
        val four = base.withFirstExercise { e ->
            val light = e.sets.first().copy(kind = SetKind.APPROACH)
            e.copy(sets = (1..4).map { light.copy(id = "${e.id}-ap$it") } + e.sets)
        }
        assertTrue(evaluate(four).blocking.none { it.code == "NON_WORK_CAP" })
    }

    @Test fun `rest outside 45 to 120 seconds blocks approval, and 0 stays the app's no-rest value`() {
        fun withRest(seconds: Int) = base.withFirstExercise { e -> e.copy(sets = listOf(e.sets.first().copy(restSeconds = seconds)) + e.sets.drop(1)) }
        assertTrue(evaluate(withRest(30)).blocking.any { it.code == "REST_RANGE" })
        assertTrue(evaluate(withRest(121)).blocking.any { it.code == "REST_RANGE" })
        assertTrue(evaluate(withRest(45)).blocking.none { it.code == "REST_RANGE" })
        assertTrue(evaluate(withRest(120)).blocking.none { it.code == "REST_RANGE" })
        assertTrue(evaluate(withRest(0)).blocking.none { it.code == "REST_RANGE" })
    }

    @Test fun `approach sets do not count as volume, reps or load against the base week`() {
        val proposal = base.withFirstExercise { e ->
            val first = e.sets.first()
            val light = first.load?.let { it.copy(value = it.value / 2) }
            val approach = (1..3).map { first.copy(id = "${e.id}-ap$it", kind = SetKind.APPROACH, reps = 1, rir = null, load = light) }
            e.copy(sets = approach + e.sets)
        }
        val plain = evaluate(base)
        val withApproach = evaluate(proposal)
        assertEquals(plain.issues.map { it.code }, withApproach.issues.map { it.code })
        assertEquals(plain.differences, withApproach.differences)
        // The explained changes of the review use the same definition of a work set.
        val saved = context.programs.flatMap { it.blocks }.flatMap { it.weeks }.last()
        assertEquals(ProposalExplain.explain(base, saved, emptyList()).map { it.summary },
            ProposalExplain.explain(proposal, saved, emptyList()).map { it.summary })
    }
}
