package com.example.workoutpoc.proposal

import com.example.workoutpoc.catalog.exposures
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.domain.loadLabel
import com.example.workoutpoc.domain.rirLabel
import com.example.workoutpoc.exchange.WeekProposal
import com.example.workoutpoc.planning.ExercisePrescription
import com.example.workoutpoc.planning.WeekPlan
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Serializable enum class ChangeKind(val label: String) {
    INCREASE("Aumento"), REDUCTION("Reducción"), SUBSTITUTION("Sustitución"), ADDITION("Nuevo ejercicio"), REMOVAL("Ejercicio retirado")
}

/**
 * One relevant change of a proposal against its base week, in plain language, with the evidence available on the
 * device and the agent's reason for it. [reason] is null when the agent gave no reason mentioning this exercise:
 * a reason is never invented or inferred.
 */
@Serializable data class ExplainedChange(
    val kind: ChangeKind, val exerciseId: String, val exercise: String, val summary: String,
    val evidence: String, val reason: String?
)

private fun Double.fmt() = if (this == Math.floor(this)) toLong().toString() else "%.1f".format(this)

object ProposalExplain {
    fun explain(proposal: WeekProposal, base: WeekPlan?, sessions: List<WorkoutState>): List<ExplainedChange> {
        if (base == null) return emptyList()
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        fun evidence(exerciseId: String): String {
            val latest = exposures(exerciseId, sessions).flatMap { g -> g.exposures.map { g to it } }.maxByOrNull { it.second.startedAtMillis }
                ?: return "Sin historial registrado para este ejercicio en el dispositivo."
            val (g, e) = latest
            return "Última exposición ${format.format(Date(e.startedAtMillis))}: " + listOfNotNull(
                e.topLoadTenths?.let { "máx. ${loadLabel(it, g.unit)} × ${e.bestRepsAtTopLoad}" }, "${e.workSets} series",
                e.minRir?.let { "RIR mín. ${rirLabel(it)}" }).joinToString(" · ") +
                if (e.workSets == 0) " (sin series de trabajo completadas)" else ""
        }
        fun reason(vararg names: String): String? = proposal.reasons.filter { r -> names.any { n -> n.isNotBlank() && r.contains(n, ignoreCase = true) } }
            .joinToString(" ").ifBlank { null }

        val before = base.days.flatMap { it.exercises }.groupBy { it.exerciseId }
        val after = proposal.week.days.flatMap { it.exercises }.groupBy { it.exerciseId }
        val out = mutableListOf<ExplainedChange>()

        // Substitutions: a removed exercise replaced at the same day and position by a new one.
        val removed = (before.keys - after.keys).toMutableSet(); val added = (after.keys - before.keys).toMutableSet()
        base.days.forEachIndexed { d, day -> day.exercises.forEachIndexed { i, old ->
            val new = proposal.week.days.getOrNull(d)?.exercises?.getOrNull(i) ?: return@forEachIndexed
            if (old.exerciseId in removed && new.exerciseId in added) {
                removed -= old.exerciseId; added -= new.exerciseId
                out += ExplainedChange(ChangeKind.SUBSTITUTION, new.exerciseId, new.name, "${new.name} sustituye a ${old.name} (${day.name}).",
                    "${old.name}: ${evidence(old.exerciseId)}", reason(new.name, old.name, new.exerciseId, old.exerciseId))
            }
        } }
        removed.forEach { id -> val e = before.getValue(id).first()
            out += ExplainedChange(ChangeKind.REMOVAL, id, e.name, "Se retira ${e.name} (${before.getValue(id).sumOf { it.sets.size }} series en la semana base).",
                evidence(id), reason(e.name, id)) }
        added.forEach { id -> val e = after.getValue(id).first()
            out += ExplainedChange(ChangeKind.ADDITION, id, e.name, "Se añade ${e.name} con ${after.getValue(id).sumOf { it.sets.size }} series.",
                evidence(id), reason(e.name, id)) }

        (before.keys intersect after.keys).forEach { id ->
            val b = before.getValue(id); val a = after.getValue(id); val name = a.first().name
            fun sets(l: List<ExercisePrescription>) = l.sumOf { it.sets.size }
            fun change(kind: ChangeKind, summary: String) { out += ExplainedChange(kind, id, name, summary, evidence(id), reason(name, id)) }
            val sb = sets(b); val sa = sets(a)
            if (sa != sb) change(if (sa > sb) ChangeKind.INCREASE else ChangeKind.REDUCTION, "$name: series $sb → $sa (${pct(sb.toDouble(), sa.toDouble())}).")
            val loadsB = b.flatMap { it.sets }.mapNotNull { it.load }.groupBy { it.unit to it.basis }.mapValues { (_, l) -> l.maxOf { it.value } }
            val loadsA = a.flatMap { it.sets }.mapNotNull { it.load }.groupBy { it.unit to it.basis }.mapValues { (_, l) -> l.maxOf { it.value } }
            loadsA.forEach { (key, v) ->
                val old = loadsB[key] ?: return@forEach
                if (old != v) change(if (v > old) ChangeKind.INCREASE else ChangeKind.REDUCTION,
                    "$name: carga máxima ${old.fmt()} → ${v.fmt()} ${key.first} ${key.second} (${pct(old, v)}).")
            }
            val repsB = b.flatMap { it.sets }.mapNotNull { it.reps }; val repsA = a.flatMap { it.sets }.mapNotNull { it.reps }
            if (repsB.isNotEmpty() && repsA.isNotEmpty() && repsB.sum() != repsA.sum())
                change(if (repsA.sum() > repsB.sum()) ChangeKind.INCREASE else ChangeKind.REDUCTION, "$name: repeticiones prescritas ${repsB.sum()} → ${repsA.sum()} en total.")
            val rirB = b.flatMap { it.sets }.mapNotNull { it.rir }.minOrNull(); val rirA = a.flatMap { it.sets }.mapNotNull { it.rir }.minOrNull()
            if (rirB != null && rirA != null && rirB != rirA)
                change(if (rirA < rirB) ChangeKind.INCREASE else ChangeKind.REDUCTION,
                    "$name: RIR mínimo $rirB → $rirA (${if (rirA < rirB) "más cerca del fallo" else "más margen"}).")
        }
        return out.sortedBy { it.kind.ordinal }
    }

    private fun pct(old: Double, new: Double) = if (old == 0.0) "sin base para %" else ((new - old) * 100 / old).roundToInt().let { (if (it > 0) "+" else "") + "$it %" }
}
