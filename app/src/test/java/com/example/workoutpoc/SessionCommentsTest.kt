package com.example.workoutpoc

import com.example.workoutpoc.domain.SessionComment
import com.example.workoutpoc.domain.SessionComments
import org.junit.Assert.*
import org.junit.Test

/** Contract §7: which saved comments reach the agent for a generation. */
class SessionCommentsTest {
    private fun comment(id: String, programId: String = "prog", exerciseId: String? = null, resolved: Long? = null, at: Long = 1) =
        SessionComment(id = id, text = "Texto $id", createdAtMillis = at, programId = programId,
            weekId = "week-one", exerciseId = exerciseId, resolvedAtMillis = resolved)

    @Test fun `an exercise comment reaches generations that include that exercise, and only those`() {
        val remo = comment("remo", exerciseId = "row")
        assertEquals(listOf(remo), SessionComments.forGeneration(listOf(remo), "prog", setOf("row", "press")))
        assertTrue(SessionComments.forGeneration(listOf(remo), "prog", setOf("press")).isEmpty())
    }

    @Test fun `general session comments reach every generation of the same program`() {
        val general = comment("general")
        assertEquals(listOf(general), SessionComments.forGeneration(listOf(general), "prog", emptySet()))
    }

    @Test fun `resolved comments and comments from another program do not reach the agent`() {
        val resolved = comment("old", resolved = 5)
        val other = comment("other", programId = "otro")
        assertTrue(SessionComments.forGeneration(listOf(resolved, other), "prog", setOf("row")).isEmpty())
    }

    @Test fun `comments keep their creation order for the agent`() {
        val late = comment("late", at = 30)
        val early = comment("early", at = 10)
        assertEquals(listOf("early", "late"),
            SessionComments.forGeneration(listOf(late, early), "prog", emptySet()).map { it.id })
    }
}
