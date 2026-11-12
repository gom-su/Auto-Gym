package com.example.workoutpoc.exchange

import com.example.workoutpoc.WorkoutApplication
import com.example.workoutpoc.planning.PlanOutlines
import com.example.workoutpoc.questions.PlanningQuestions
import java.time.Instant
import java.util.UUID

/**
 * The context or results package built from the local stores: what the user exports for an external agent and what
 * the in-app agent sends, including dated body measurements with their method and original units.
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
        outlines.restore(); measurements.restore(); comments.restore()
        val active = outlines.outlines.value.active
        val saved = programs.library.value.programs
        val inForce = comments.log.value.comments.filter { c -> c.resolvedAtMillis == null && saved.any { it.id == c.programId } }
        // The next week of each plan is computed here, deterministically, so the agent never guesses it.
        TrainingContext(saved, phase, results, rules, goals,
            measurements = measurements.log.value.measurements.sortedBy { it.measuredAtMillis }.map { it.toContextMeasurement() },
            outlines = active,
            nextWeeks = active.map { o -> PlanOutlines.brief(o, saved.find { it.id == o.programId }, sessions) },
            previousOutlines = (outlines.outlines.value.inactive + outlines.outlines.value.replaced).distinctBy { it.id to it.version },
            comments = inForce,
            preferredUnits = catalog.catalog.value.exercises.mapNotNull { e ->
                e.preferredUnit?.let { PreferredUnit(e.id, it.name) } })
    }
    return ExchangePackage(ExchangeCodec.SCHEMA_VERSION, type, UUID.randomUUID().toString(), Instant.now().toString(), context = context,
        results = if (type == ExchangeType.TrainingResults) results else null).also { ExchangeCodec.encode(it) }
}

/** Preserve provenance: muscle and lean mass are different metrics, and different methods stay distinguishable. */
fun com.example.workoutpoc.body.BodyMeasurement.toContextMeasurement() = BodyMeasurement(
    id, Instant.ofEpochMilli(measuredAtMillis).toString(), metric.name, value, unit.name,
    method.name, label, fasted, timeOfDay, device, notes
)
