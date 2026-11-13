package com.example.workoutpoc.presentation

import com.example.workoutpoc.catalog.CatalogExercise
import com.example.workoutpoc.catalog.Equipment
import com.example.workoutpoc.data.activePlan
import com.example.workoutpoc.domain.*

/** Shared text and rounding for app, RemoteViews and the floating window. No surface owns time. */
fun counterText(millis: Long, countdown: Boolean = false): String {
    val seconds = if (countdown && millis > 0) (millis + 999) / 1000 else kotlin.math.abs(millis) / 1000
    val sign = if (countdown && millis <= -1000) "−" else ""
    return "$sign${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}

fun SetType.surfaceLabel(): String = when (this) {
    SetType.WORK -> "Trabajo"
    SetType.WARMUP -> "Calentamiento"
    SetType.APPROACH -> "Aproximación"
}

data class RecordEditorRequest(val sessionId: String, val index: Int)

fun WorkoutState.completionHint(): String? = when {
    paused != null -> "Sesión en pausa"
    runningWait != null -> "Termina la espera"
    values.reps <= 0 -> "Registra al menos 1 repetición"
    activePlan().getOrNull(index)?.setType == SetType.WORK && values.rir !in 0..4 -> "Selecciona el RIR"
    else -> null
}

/** Includes the interval before a delayed reconciliation, so every surface switches at the deadline. */
fun WorkoutState.isWaiting(now: ClockSnapshot): Boolean = runningWait != null || openMachineWait?.pending?.let {
    val at = paused?.let { p -> ClockSnapshot(p.elapsedMillis, p.wallMillis, p.bootCount) } ?: now
    it.signedRemainingMillis(at) <= 0
} == true

data class SurfaceTimer(val label: String, val text: String, val waiting: Boolean)

fun WorkoutState.surfaceTimer(now: ClockSnapshot): SurfaceTimer? {
    val at = paused?.let { ClockSnapshot(it.elapsedMillis, it.wallMillis, it.bootCount) } ?: now
    if (isWaiting(now)) {
        val wait = openMachineWait ?: return null
        val duration = wait.durationMillis(at) ?: wait.pending?.asMark()?.elapsedSince(at) ?: 0
        return SurfaceTimer("Espera por máquina", counterText(duration), true)
    }
    if (phase == Phase.Resting) {
        val balance = restSignedBalance(now) ?: rest?.signedRemainingMillis(at) ?: 0
        return SurfaceTimer("Descanso", counterText(balance, countdown = true), false)
    }
    return cardioRemainingMillis(now)?.let { SurfaceTimer("Cardio · tiempo restante", counterText(it, countdown = true), false) }
}

data class SeriesNotice(val heading: String, val detail: String, val changes: List<String>) {
    val text: String get() = (listOf(heading, detail) + changes).joinToString("\n")

    /** Prioritize the changes, retaining configuration even when the equipment category is unknown. */
    fun compactText(): String {
        val signals = changes.filter { it == "Cambia de máquina" || it.startsWith("Carga") || it.startsWith("Repeticiones") }
        val setup = changes.filterNot { it in signals || it.startsWith("Ejercicio:") }
        val lines = detail.lines()
        return listOf("$heading · ${lines.first()}", signals.joinToString(" · ").ifBlank { lines.getOrNull(1).orEmpty() },
            setup.joinToString(" · ")).filter { it.isNotBlank() }.joinToString("\n")
    }
}

private fun fullLoad(value: Int, unit: WeightUnit, basis: String?): String = loadLabel(value, unit) + when (basis) {
    "PER_HAND" -> " por mano"; "PER_SIDE" -> " por lado"; "TOTAL" -> " total"; else -> ""
}

/** The preview during rest becomes the same notice on entering the following set. */
fun WorkoutState.seriesNotice(catalog: List<CatalogExercise> = emptyList()): SeriesNotice? {
    val plan = activePlan()
    val resting = phase == Phase.Resting
    if (!resting && phase != Phase.ActiveSet) return null
    val targetIndex = if (resting) index + 1 else index
    if (resting) nextCardio(plan, targetIndex)?.let {
        return SeriesNotice("Siguiente · Cardio", "${it.modality} · ${counterText(it.plannedSeconds * 1000L)}", emptyList())
    }
    val target = plan.getOrNull(targetIndex) ?: return null
    val previousIndex = targetIndex - 1
    val previous = plan.getOrNull(previousIndex)
    val recorded = records.lastOrNull { it.index == previousIndex && it.status == SetStatus.COMPLETED }
    val proposed = if (resting && previous != null) followingSetValues(previous, target) else values
    val load = fullLoad(proposed.weightTenths, target.unit, target.basis)
    val detail = "${target.exercise} · ${target.setType.surfaceLabel()} ${target.number}/${target.total}\n$load · " +
        if (proposed.reps > 0) "${proposed.reps} reps" else "repeticiones sin indicar"
    val changes = mutableListOf<String>()
    val differentExercise = previous != null && (previous.exerciseId != target.exerciseId || previous.exercise != target.exercise)
    val equipment = catalog.firstOrNull { it.id == target.exerciseId }
    if (differentExercise) {
        changes += "Ejercicio: ${target.exercise}"
        val before = catalog.firstOrNull { it.id == previous?.exerciseId }
        // The catalog identifies equipment categories, not the identity of a physical machine.
        if (equipment?.equipment in setOf(Equipment.MACHINE, Equipment.CABLE) && before?.equipment != null &&
            before.equipment != equipment?.equipment) changes += "Cambia de máquina"
    }
    equipment?.let { entry ->
        entry.equipment?.let { e -> changes += "Equipo: " + when (e) {
            Equipment.BARBELL -> "Barra"; Equipment.DUMBBELL -> "Mancuernas"; Equipment.MACHINE -> "Máquina"
            Equipment.CABLE -> "Polea"; Equipment.BODYWEIGHT -> "Peso corporal"; Equipment.KETTLEBELL -> "Kettlebell"
            Equipment.BAND -> "Banda"; Equipment.OTHER -> "Otro"
        } }
        listOfNotNull(entry.variant, entry.setup).filter { it.isNotBlank() }.forEach { changes += it }
    }
    if (previous != null && recorded != null) {
        val previousUnit = recorded.recordedUnit ?: previous.unit
        if (previousUnit != target.unit || previous.basis != target.basis) changes += "Carga: $load"
        else if (recorded.values.weightTenths != proposed.weightTenths)
            changes += "Carga ${weightLabel(recorded.values.weightTenths)} → $load"
        if (recorded.values.reps > 0 && proposed.reps > 0 && recorded.values.reps != proposed.reps)
            changes += "Repeticiones ${recorded.values.reps} → ${proposed.reps}"
    }
    if (!resting && previous == null) return null
    return SeriesNotice(if (resting) "Siguiente" else "Ahora", detail, changes)
}
