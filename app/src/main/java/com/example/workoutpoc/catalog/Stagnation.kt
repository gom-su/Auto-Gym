package com.example.workoutpoc.catalog

import com.example.workoutpoc.domain.WeightUnit
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.domain.loadLabel
import com.example.workoutpoc.planning.WeekAnnotations
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Thresholds for a stagnation signal. Defaults favour evidence over sensitivity. */
data class StagnationSettings(val exposures: Int = 4, val minSpanDays: Int = 14) {
    init { require(exposures in 3..12 && minSpanDays in 7..180) }
}

/** One exposure used as evidence, as plotted: completed work sets only. */
data class EvidencePoint(val startedAtMillis: Long, val label: String, val topLoadTenths: Int, val repsAtTop: Int, val minRir: Int?)

/**
 * A stagnation alert for one exercise in one unit/basis group. It always states its period, the data used, its
 * limitations and the exact rule that fired, so it can be checked against the listed points.
 */
data class StagnationAlert(
    val exerciseId: String, val exerciseName: String, val unit: WeightUnit, val basis: String?,
    val fromMillis: Long, val toMillis: Long, val reference: EvidencePoint?, val window: List<EvidencePoint>,
    val explanation: String, val limitations: List<String>
)

/** Why no alert could be evaluated for a group (shown instead of silently omitting it). */
data class InsufficientEvidence(val exerciseId: String, val exerciseName: String, val unit: WeightUnit, val basis: String?, val reason: String)

data class StagnationReport(val alerts: List<StagnationAlert>, val insufficient: List<InsufficientEvidence>)

private fun better(a: EvidencePoint, b: EvidencePoint) = a.topLoadTenths > b.topLoadTenths || (a.topLoadTenths == b.topLoadTenths && a.repsAtTop > b.repsAtTop)

/**
 * Rule: in each unit/basis group, take the last [StagnationSettings.exposures] comparable exposures (completed work
 * sets, not deload weeks) spanning at least [StagnationSettings.minSpanDays]. If none of them beats the best point
 * reached before the window — a higher top load, or the same top load for more reps — a stagnation alert is raised.
 * Without enough comparable data nothing is inferred: the group is reported as insufficient evidence.
 */
fun stagnation(exerciseIds: Map<String, String>, sessions: List<WorkoutState>, annotations: WeekAnnotations,
               settings: StagnationSettings = StagnationSettings()): StagnationReport {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val alerts = mutableListOf<StagnationAlert>(); val insufficient = mutableListOf<InsufficientEvidence>()
    exerciseIds.forEach { (id, name) ->
        exposures(id, sessions).forEach { group ->
            val all = group.exposures.sortedBy { it.startedAtMillis }
            val deloads = all.count { annotations.isDeload(it.programId, it.weekId) }
            val points = all.filter { !annotations.isDeload(it.programId, it.weekId) && it.workSets > 0 && it.topLoadTenths != null }
                .map { EvidencePoint(it.startedAtMillis, it.label, it.topLoadTenths!!, it.bestRepsAtTopLoad!!, it.minRir) }
            val needed = settings.exposures + 1
            if (points.size < needed) {
                insufficient += InsufficientEvidence(id, name, group.unit, group.basis,
                    "Hay ${points.size} exposiciones comparables; se necesitan $needed (una de referencia y ${settings.exposures} en la ventana).")
                return@forEach
            }
            val window = points.takeLast(settings.exposures)
            val before = points.dropLast(settings.exposures)
            val spanDays = (window.last().startedAtMillis - window.first().startedAtMillis) / 86_400_000
            if (spanDays < settings.minSpanDays) {
                insufficient += InsufficientEvidence(id, name, group.unit, group.basis,
                    "Las últimas ${settings.exposures} exposiciones abarcan $spanDays días; se necesitan al menos ${settings.minSpanDays}.")
                return@forEach
            }
            val reference = before.reduce { best, p -> if (better(p, best)) p else best }
            if (window.any { better(it, reference) }) return@forEach
            val limitations = mutableListOf("Solo series de trabajo completadas; aproximaciones y series omitidas no cuentan.",
                "Compara carga máxima y repeticiones a esa carga; no estima 1RM ni considera el volumen total.")
            if (deloads > 0) limitations += "Se excluyeron $deloads exposiciones de semanas de descarga."
            val noRir = window.count { it.minRir == null }
            if (noRir > 0) limitations += "$noRir exposiciones de la ventana sin RIR registrado: el esfuerzo no puede compararse."
            if (window.mapNotNull { it.minRir }.let { r -> r.isNotEmpty() && reference.minRir != null && r.max() > reference.minRir })
                limitations += "En la ventana hubo más margen de RIR que en la referencia: puede ser una elección de esfuerzo, no un estancamiento."
            val unit = group.unit
            alerts += StagnationAlert(id, name, unit, group.basis, window.first().startedAtMillis, window.last().startedAtMillis, reference, window,
                "En ${settings.exposures} exposiciones comparables entre ${format.format(Date(window.first().startedAtMillis))} y " +
                    "${format.format(Date(window.last().startedAtMillis))} ninguna superó la referencia de " +
                    "${loadLabel(reference.topLoadTenths, unit)} × ${reference.repsAtTop} (${format.format(Date(reference.startedAtMillis))}): " +
                    "ni más carga máxima ni más repeticiones con la misma carga.",
                limitations)
        }
    }
    return StagnationReport(alerts, insufficient)
}
