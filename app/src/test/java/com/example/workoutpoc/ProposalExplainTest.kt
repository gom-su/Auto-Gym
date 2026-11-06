package com.example.workoutpoc

import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.exchange.WeekProposal
import com.example.workoutpoc.planning.*
import com.example.workoutpoc.proposal.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** AG-07: every relevant change is explained with evidence and the agent's reason; a missing reason is never invented. */
class ProposalExplainTest {
    private var time = ClockSnapshot(1_000, 1_800_000_000_000, 5)
    private fun ex(id: String, exerciseId: String, name: String, sets: Int, reps: Int, rir: Int, load: Double?) =
        ExercisePrescription(id, exerciseId, name, (1..sets).map { SetPrescription("$id-$it", reps, rir, load?.let { l -> PrescribedLoad(l, LoadUnit.KG, LoadBasis.TOTAL) }, 60) })
    private val base = WeekPlan("w1", "Semana 1", 1, listOf(SessionPlan("d1", "Día A", listOf(
        ex("b-sq", "squat", "Sentadilla", 3, 8, 2, 100.0),
        ex("b-row", "row", "Remo", 3, 10, 2, 60.0),
        ex("b-curl", "curl", "Curl", 2, 12, 2, 15.0),
        ex("b-calf", "calf", "Gemelo", 2, 15, 2, null)))))
    private val proposed = WeekPlan("w2", "Semana 2", 1, listOf(SessionPlan("d2", "Día A", listOf(
        ex("p-sq", "squat", "Sentadilla", 4, 8, 1, 105.0),            // more sets, more load, lower RIR
        ex("p-pull", "pullup", "Dominada", 3, 8, 2, null),            // replaces Remo at the same position
        ex("p-curl", "curl", "Curl", 1, 12, 2, 12.5)))))              // fewer sets, less load; Gemelo removed

    private fun proposal(reasons: List<String>) =
        WeekProposal("p", 1, "prog", "b", "Agente ficticio", proposed, emptyList(), reasons)

    @Test fun `increases, reductions, substitutions and removals are classified with plain summaries`() {
        val changes = ProposalExplain.explain(proposal(emptyList()), base, emptyList())
        val kinds = changes.groupBy { it.kind }
        assertEquals("Dominada sustituye a Remo (Día A).", kinds.getValue(ChangeKind.SUBSTITUTION).single().summary)
        assertEquals("calf", kinds.getValue(ChangeKind.REMOVAL).single().exerciseId)
        assertNull(kinds[ChangeKind.ADDITION])                                       // the new exercise is a substitution, not an addition
        val inc = kinds.getValue(ChangeKind.INCREASE).map { it.summary }
        assertTrue(inc.toString(), inc.contains("Sentadilla: series 3 → 4 (+33 %)."))
        assertTrue(inc.contains("Sentadilla: carga máxima 100 → 105 KG TOTAL (+5 %)."))
        assertTrue(inc.contains("Sentadilla: RIR mínimo 2 → 1 (más cerca del fallo)."))
        val red = kinds.getValue(ChangeKind.REDUCTION).map { it.summary }
        assertTrue(red.toString(), red.contains("Curl: series 2 → 1 (−50 %).".replace("−", "-")))
        assertTrue(red.toString(), red.contains("Curl: carga máxima 15 → ${"%.1f".format(12.5)} KG TOTAL (-17 %)."))
        // No reason given: never invented, and no history means the evidence says so.
        assertTrue(changes.all { it.reason == null })
        assertTrue(changes.all { it.evidence.startsWith("Sin historial") || it.evidence.startsWith("Remo:") })
    }

    @Test fun `reasons attach only to changes they mention and evidence comes from recorded history`() = runTest {
        val program = TrainingProgram("prog", "Programa", listOf(TrainingBlock("b", "Bloque", listOf(base))))
        val repo = WorkoutRepository(object : WorkoutStorage {
            var s = WorkoutState(); override suspend fun read() = s; override suspend fun write(state: WorkoutState) { s = state }
        }, { time }, { "s1" })
        repo.restore()
        val d = program.findDay("w1", "d1")!!
        repo.load(d.day.toPlannedSets(), d.source)
        repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
        repo.dispatch(repo.state.value.command(WorkoutEvent.CompleteSet))
        repo.dispatch(repo.state.value.command(WorkoutEvent.FinishWorkout))
        val changes = ProposalExplain.explain(proposal(listOf("La sentadilla progresó con RIR 2 estable.", "Dominada para variar el tirón en lugar del Remo.")),
            base, listOf(repo.state.value))
        changes.filter { it.exerciseId == "squat" }.forEach {
            assertEquals("La sentadilla progresó con RIR 2 estable.", it.reason)
            assertTrue(it.evidence, it.evidence.contains("máx. 100 kg × 8"))
        }
        assertEquals("Dominada para variar el tirón en lugar del Remo.", changes.single { it.kind == ChangeKind.SUBSTITUTION }.reason)
        assertNull(changes.single { it.kind == ChangeKind.REMOVAL }.reason)                 // Gemelo is not mentioned
        assertTrue(changes.filter { it.exerciseId == "curl" }.all { it.reason == null })
        assertTrue(ProposalExplain.explain(proposal(emptyList()), null, emptyList()).isEmpty())
    }
}
