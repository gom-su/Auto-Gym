package com.example.workoutpoc

import androidx.datastore.core.DataStore
import com.example.workoutpoc.catalog.ChartMetric
import com.example.workoutpoc.data.SessionCommentRepository
import com.example.workoutpoc.domain.SessionComment
import com.example.workoutpoc.domain.SessionCommentLog
import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.*
import com.example.workoutpoc.proposal.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ProgramDeletionRepositoryTest {
    private val target = "target-program"
    private val other = "other-program"
    private fun fixture(name: String) = File("src/main/assets/examples/$name.json").readText()

    private class TestStore<T>(initial: T) : DataStore<T> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(transform: suspend (T) -> T): T =
            transform(data.value).also { data.value = it }
    }

    @Test fun `removes all outline versions for only the selected program`() = runTest {
        val outline = ExchangeCodec.decode(fixture("plan-outline-v1")).outline!!.copy(programId = target)
        val otherOutline = outline.copy(programId = other, id = "other-plan")
        val initial = PlanOutlineStore(
            active = listOf(outline),
            replaced = listOf(outline.copy(version = 2), otherOutline.copy(version = 2)),
            inactive = listOf(outline.copy(version = 3), otherOutline)
        )
        val store = TestStore(initial)
        val repository = PlanOutlineRepository(store)
        assertEquals(3, repository.removeProgram(target))
        val expected = initial.copy(active = emptyList(), replaced = listOf(otherOutline.copy(version = 2)),
            inactive = listOf(otherOutline))
        assertEquals(expected, store.data.value)
        assertEquals(expected, repository.outlines.value)
        assertEquals(0, repository.removeProgram(target))
    }

    @Test fun `removes the selected program with its weeks`() = runTest {
        val program = ProgramCodec.decode(fixture("variable-program-v1")).copy(id = target)
        val kept = program.copy(id = other)
        val store = TestStore(ProgramLibrary(1, listOf(program, kept)))
        val repository = ProgramRepository(store)
        assertEquals(1, repository.removeProgram(target))
        assertEquals(listOf(kept), store.data.value.programs)
        assertEquals(store.data.value, repository.library.value)
        assertEquals(0, repository.removeProgram(target))
    }

    @Test fun `removes selected week annotations and preserves goals`() = runTest {
        val kept = WeekAnnotations(deloads = listOf(WeekRef(other, "week")),
            notPerformed = listOf(DayRef(other, "week", "day")), goals = Goals(3, 90))
        val store = TestStore(kept.copy(deloads = kept.deloads + WeekRef(target, "week"),
            notPerformed = kept.notPerformed + DayRef(target, "week", "day")))
        val repository = WeekAnnotationRepository(store)
        assertEquals(2, repository.removeProgram(target))
        assertEquals(kept, store.data.value)
        assertEquals(kept, repository.annotations.value)
        assertEquals(0, repository.removeProgram(target))
    }

    @Test fun `removes decisions and their expectations while retaining rules`() = runTest {
        val decision = ProposalDecision("selected", "proposal", 1, DecisionStatus.APPROVED, 1L,
            target, "block", "week", emptyList(), emptyList(), emptyList(), emptyList())
        val keptDecision = decision.copy(importId = "kept", proposalId = "other-proposal", programId = other)
        val expectation = Expectation("selected-expectation", decision.importId, "squat", "Sentadilla",
            ChartMetric.TOP_LOAD, ExpectedDirection.INCREASE, 14, 1L)
        val keptExpectation = expectation.copy(id = "kept-expectation", importId = keptDecision.importId)
        val expected = ProposalState(rules = RuleSettings(outlineTargets = Severity.BLOCKING),
            decisions = listOf(keptDecision), expectations = listOf(keptExpectation))
        val store = TestStore(expected.copy(decisions = listOf(decision, keptDecision),
            expectations = listOf(expectation, keptExpectation)))
        val repository = ProposalRepository(store, ProgramRepository(TestStore(ProgramLibrary(1, emptyList()))),
            ExchangeRepository(TestStore(ExchangeArchive(1, emptyList()))), { emptySet() })
        assertEquals(2, repository.removeProgram(target))
        assertEquals(expected, store.data.value)
        assertEquals(expected, repository.state.value)
        assertEquals(0, repository.removeProgram(target))
    }

    @Test fun `removes only comments from the selected program`() = runTest {
        val selected = SessionComment("selected", "Selected comment", 10, target, "week", "session")
        val kept = selected.copy(id = "kept", programId = other)
        val store = TestStore(SessionCommentLog(comments = listOf(selected, kept)))
        val repository = SessionCommentRepository(store)
        assertEquals(1, repository.removeProgram(target))
        assertEquals(listOf(kept), store.data.value.comments)
        assertEquals(store.data.value, repository.log.value)
        assertEquals(0, repository.removeProgram(target))
    }

    @Test fun `removes imported plans and proposals but retains contexts and other programs`() = runTest {
        val planPackage = ExchangeCodec.decode(fixture("plan-outline-v1"))
        val proposalPackage = ExchangeCodec.decode(fixture("week-proposal-v1"))
        val context = ExchangeCodec.decode(fixture("training-context-v1"))
        val programInContext = context.context!!.programs.first().id
        val plan = planPackage.copy(outline = planPackage.outline!!.copy(programId = programInContext))
        val proposal = proposalPackage.copy(proposal = proposalPackage.proposal!!.copy(programId = programInContext))
        val kept = listOf(
            plan.copy(importId = "other-plan-import", outline = plan.outline!!.copy(programId = other)),
            proposal.copy(importId = "other-proposal-import", proposal = proposal.proposal!!.copy(programId = other)),
            context
        )
        val store = TestStore(ExchangeArchive(1, listOf(plan, proposal) + kept))
        val repository = ExchangeRepository(store)
        assertEquals(2, repository.removeProgram(programInContext))
        assertEquals(kept, store.data.value.packages)
        assertEquals(store.data.value, repository.archive.value)
        assertEquals(0, repository.removeProgram(programInContext))
    }
}
