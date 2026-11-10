package com.example.workoutpoc.exchange

import com.example.workoutpoc.WorkoutApplication
import com.example.workoutpoc.planning.PlanOutlines
import com.example.workoutpoc.questions.PlanningQuestions
import java.time.Instant
import java.util.UUID

/**
 * The context or results package built from the local stores: what the user exports for an external agent and what
 * the in-app agent sends. Body measurements are never included. Any other type has no local content and is refused.
 */
suspend fun WorkoutApplication.localExchange(type: ExchangeType): ExchangePackage {
    programs.restore(); repository.restore()
    val state = repository.state.value
    // Archived and current planned sessions are typed v1 results; the demo keeps its frozen legacy snapshot.
    val sessions = (repository.archived.value.sessions + state).distinctBy { it.sessionId }.mapNotNull { it.toSessionResult() }
    val results = TrainingResults(sessions, state.toDemoV1()?.let { DemoSnapshot(it) })
    val context = if (type != ExchangeType.TrainingContext) null else {
        // Phase, goals and rules come only from the user's answers to the planning questions (AG-01); never invented.
        catalog.restore(); planningAnswers.restore()
        val (phase, goals, rules) = PlanningQuestions.context(PlanningQuestions.questions(catalog.catalog.value), planningAnswers.answers.value)
        outlines.restore()
        val active = outlines.outlines.value.active
        val saved = programs.library.value.programs
        // The next week of each plan is computed here, deterministically, so the agent never guesses it.
        TrainingContext(saved, phase, results, rules, goals, outlines = active,
            nextWeeks = active.map { o -> PlanOutlines.brief(o, saved.find { it.id == o.programId }, sessions) })
    }
    return ExchangePackage(1, type, UUID.randomUUID().toString(), Instant.now().toString(), context = context,
        results = if (type == ExchangeType.TrainingResults) results else null).also { ExchangeCodec.encode(it) }
}
