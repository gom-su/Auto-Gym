package com.example.workoutpoc

import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Decision §2: the brief that regenerates one saved week is only offered for the latest week of its block. */
class PlanOutlineRegenerationTest {
    private val outline = ExchangeCodec.decode(File("src/main/assets/examples/plan-outline-v1.json").readText()).outline!!

    private fun week(n: Int): WeekPlan {
        val w = outline.weeks[n - 1]
        return WeekPlan("w$n", "Semana $n", 1, outline.days.map { d ->
            SessionPlan("w$n-${d.id}", d.name, d.exercises.map { e ->
                val t = w.targets.find { e.exerciseId in it.exerciseIds } ?: w.targets.find { it.exerciseIds.isEmpty() }
                ExercisePrescription("w$n-${d.id}-${e.exerciseId}", e.exerciseId, e.name, List(t?.sets ?: e.sets ?: 2) { s ->
                    SetPrescription("w$n-${d.id}-${e.exerciseId}-$s", t?.repsMax, t?.rirMax, PrescribedLoad(20.0, LoadUnit.KG, LoadBasis.TOTAL))
                })
            })
        })
    }

    /** Program with the first [count] weeks of the outline, grouped by their blocks. */
    private fun program(count: Int) = TrainingProgram(outline.programId, "Programa", outline.blocks.map { b ->
        TrainingBlock(b.id, b.name, (1..count).filter { outline.weeks[it - 1].blockId == b.id }.map { week(it) })
    }.filter { it.weeks.isNotEmpty() })

    private fun trained(w: WeekPlan) = w.days.map { d ->
        SessionResult("s-${d.id}", d.id, PerformanceStatus.COMPLETED, null, null, null,
            listOf(SetResult("set", null, d.exercises.first().exerciseId, PerformanceStatus.COMPLETED, 10, 2, null, null, null)), emptyList())
    }

    @Test fun `the latest week of a block gets a brief that ignores its own records`() {
        val firstBlock = (1..12).groupBy { outline.weeks[it - 1].blockId }.values.first()
        val last = firstBlock.last()
        val brief = PlanOutlines.regenerationBrief(outline, program(last), trained(week(last)), "w$last")

        assertEquals(BriefStatus.READY, brief.status)
        assertEquals(last, brief.weekNumber)
        assertNull("the regenerated week's own records are not its reference", brief.referenceWeekNumber)
    }

    @Test fun `the reference is the last earlier week with real records`() {
        val firstBlock = (1..12).groupBy { outline.weeks[it - 1].blockId }.values.first()
        assertTrue("fixture block must have several weeks", firstBlock.size >= 2)
        val last = firstBlock.last()
        val previous = last - 1
        val brief = PlanOutlines.regenerationBrief(outline, program(last), trained(week(previous)), "w$last")
        assertEquals(previous, brief.referenceWeekNumber!! )
    }

    @Test fun `an intermediate week of a block is refused`() {
        val firstBlock = (1..12).groupBy { outline.weeks[it - 1].blockId }.values.first()
        assertTrue("fixture block must have several weeks", firstBlock.size >= 2)
        val intermediate = firstBlock.first()
        try {
            PlanOutlines.regenerationBrief(outline, program(firstBlock.last()), emptyList(), "w$intermediate")
            fail("Expected refusal of an intermediate week")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("última semana del bloque"))
        }
    }
}
