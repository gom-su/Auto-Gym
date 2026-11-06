package com.example.workoutpoc.proposal

import com.example.workoutpoc.catalog.ChartMetric
import com.example.workoutpoc.catalog.exposures
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.domain.loadLabel
import com.example.workoutpoc.domain.weightLabel
import com.example.workoutpoc.planning.WeekAnnotations
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable enum class Outcome(val label: String) {
    FAVORABLE("Favorable"), UNFAVORABLE("Desfavorable"), INCONCLUSIVE("Inconclusa"), PENDING("Pendiente")
}
@Serializable enum class ExpectedDirection(val label: String) { INCREASE("aumentar"), MAINTAIN("mantener"), DECREASE("reducir") }

/**
 * What an approved recommendation is expected to achieve for one exercise and metric within [windowDays] after the
 * decision. [manualOutcome] lets the user close it with their own judgement (e.g. an injury); it is labelled manual.
 */
@Serializable data class Expectation(
    val id: String, val importId: String, val exerciseId: String, val exerciseName: String,
    val metric: ChartMetric, val direction: ExpectedDirection, val windowDays: Int,
    val startMillis: Long, val manualOutcome: Outcome? = null, val note: String? = null
) {
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) && exerciseId.isNotBlank()) { "Identificador no válido." }
        require(windowDays in 1..84) { "La ventana de evaluación debe ser de 1 a 84 días." }
        require(metric != ChartMetric.MIN_RIR) { "Usa carga, repeticiones o volumen para evaluar." }
        require(note == null || (note.isNotBlank() && note.length <= 1000)) { "Nota vacía o demasiado larga." }
        require(startMillis > 0)
    }
}

data class EvaluationResult(val outcome: Outcome, val manual: Boolean, val baseline: Double?, val observed: Double?, val detail: String)

private const val MAINTAIN_TOLERANCE = 0.025

/**
 * Contrasts an expectation with observed data. The baseline is the latest exposure before the decision; observations
 * are exposures inside the window in the same unit/basis group, excluding deload weeks. While the window is open the
 * result stays PENDING; once closed, missing baseline or observations make it INCONCLUSIVE rather than guessing.
 */
fun evaluate(e: Expectation, sessions: List<WorkoutState>, annotations: WeekAnnotations, nowMillis: Long): EvaluationResult {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val end = e.startMillis + e.windowDays * 86_400_000L
    val groups = exposures(e.exerciseId, sessions)
    fun value(ex: com.example.workoutpoc.catalog.Exposure): Double? = if (ex.workSets == 0) null else when (e.metric) {
        ChartMetric.TOP_LOAD -> ex.topLoadTenths?.div(10.0)
        ChartMetric.TOTAL_REPS -> ex.totalReps.toDouble()
        ChartMetric.VOLUME -> ex.volumeTenths / 10.0
        ChartMetric.MIN_RIR -> null
    }
    val baselinePair = groups.flatMap { g -> g.exposures.map { g to it } }
        .filter { (_, x) -> x.startedAtMillis < e.startMillis && value(x) != null && !annotations.isDeload(x.programId, x.weekId) }
        .maxByOrNull { it.second.startedAtMillis }
    val group = baselinePair?.first
    val observedList = group?.exposures.orEmpty()
        .filter { it.startedAtMillis in e.startMillis..end && value(it) != null && !annotations.isDeload(it.programId, it.weekId) }
        .sortedBy { it.startedAtMillis }
    val baseline = baselinePair?.second?.let(::value)
    val observed = observedList.lastOrNull()?.let(::value)
    fun fmt(v: Double) = if (e.metric == ChartMetric.TOP_LOAD && group != null) loadLabel((v * 10).toInt(), group.unit) else weightLabel((v * 10).toInt())
    val compared = if (baseline != null && observed != null)
        "Base ${fmt(baseline)} (${format.format(Date(baselinePair.second.startedAtMillis))}) → observado ${fmt(observed)} (${format.format(Date(observedList.last().startedAtMillis))})."
    else null
    e.manualOutcome?.let { return EvaluationResult(it, true, baseline, observed, "Resultado manual" + (e.note?.let { n -> ": $n" } ?: ".") + (compared?.let { " $it" } ?: "")) }
    val window = "Ventana hasta ${format.format(Date(end))}."
    if (nowMillis < end) return EvaluationResult(Outcome.PENDING, false, baseline, observed,
        "$window Aún abierta" + (compared?.let { ": provisional — $it" } ?: ": sin observaciones todavía."))
    if (baseline == null) return EvaluationResult(Outcome.INCONCLUSIVE, false, null, observed, "$window No hay una exposición anterior comparable como base.")
    if (observed == null) return EvaluationResult(Outcome.INCONCLUSIVE, false, baseline, null, "$window No hubo exposiciones comparables (sin descargas) en la ventana.")
    val outcome = when (e.direction) {
        ExpectedDirection.INCREASE -> if (observed > baseline) Outcome.FAVORABLE else if (observed < baseline) Outcome.UNFAVORABLE else Outcome.INCONCLUSIVE
        ExpectedDirection.MAINTAIN -> if (observed >= baseline * (1 - MAINTAIN_TOLERANCE)) Outcome.FAVORABLE else Outcome.UNFAVORABLE
        ExpectedDirection.DECREASE -> if (observed < baseline) Outcome.FAVORABLE else Outcome.UNFAVORABLE
    }
    return EvaluationResult(outcome, false, baseline, observed, "$window $compared")
}
