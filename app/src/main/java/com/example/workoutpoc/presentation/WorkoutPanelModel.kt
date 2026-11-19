package com.example.workoutpoc.presentation

import com.example.workoutpoc.catalog.CatalogExercise
import com.example.workoutpoc.data.activePlan
import com.example.workoutpoc.domain.ClockSnapshot
import com.example.workoutpoc.domain.Phase
import com.example.workoutpoc.domain.SetStatus
import com.example.workoutpoc.domain.SetType
import com.example.workoutpoc.domain.WorkoutEvent
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.domain.currentCardio
import com.example.workoutpoc.domain.inProgress
import com.example.workoutpoc.domain.loadLabel
import com.example.workoutpoc.domain.openMachineWait
import com.example.workoutpoc.domain.openRestSegment
import com.example.workoutpoc.domain.plannedCardioComplete
import com.example.workoutpoc.domain.postponeTarget
import com.example.workoutpoc.domain.restSignedBalance
import com.example.workoutpoc.domain.rirLabel
import com.example.workoutpoc.wearprotocol.PanelAction
import com.example.workoutpoc.wearprotocol.PanelAction.*
import com.example.workoutpoc.wearprotocol.PanelControl
import com.example.workoutpoc.wearprotocol.PanelMode
import com.example.workoutpoc.wearprotocol.PanelSnapshot
import com.example.workoutpoc.wearprotocol.TimerKind

/** The session controls shared by the floating phone window and the Wear companion. */
internal fun WorkoutState.panelSnapshot(
    now: ClockSnapshot,
    catalog: List<CatalogExercise> = emptyList(),
): PanelSnapshot {
    val snapshot = PanelSnapshot(sessionId = sessionId, index = index, revision = revision,
        restSegmentId = openRestSegment?.id, title = "Inicia desde la aplicación")
    if (phase == Phase.Completed && source != null) {
        val partial = endedEarly || !plannedCardioComplete || records.size != plan.size ||
            records.any { it.status == SetStatus.SKIPPED }
        return snapshot.copy(
            mode = PanelMode.COMPLETED,
            title = if (partial) "Entrenamiento parcial" else "¡Entrenamiento completado!",
            subtitle = if (partial) "Resultado guardado" else "Buen trabajo",
        )
    }
    if (!phase.inProgress) return snapshot

    val planned = source != null
    val timer = panelTimer(now)
    val isPaused = paused != null
    val mode = when {
        timer?.kind == TimerKind.WAIT -> PanelMode.WAIT
        phase == Phase.Resting -> PanelMode.REST
        phase == Phase.ActiveCardio -> PanelMode.CARDIO
        else -> PanelMode.SET
    }
    val plan = activePlan()
    val set = plan.getOrNull(index)
    val hint = completionHint().takeIf { mode == PanelMode.SET && !isPaused }
    val needsOk = planned && mode == PanelMode.REST && !isPaused && (restSignedBalance(now) ?: 1) <= 0
    val controls = buildList {
        if (mode == PanelMode.SET && !isPaused) {
            add(PanelControl(WEIGHT_DOWN, "Reducir peso"))
            add(PanelControl(WEIGHT_UP, "Aumentar peso"))
            add(PanelControl(REPS_DOWN, "Reducir repeticiones"))
            add(PanelControl(REPS_UP, "Aumentar repeticiones"))
            if (set?.setType == SetType.WORK) {
                (0..4).forEach { add(PanelControl(SELECT_RIR, rirLabel(it), argument = it)) }
            }
            add(PanelControl(COMPLETE_SET, "Completar serie", enabled = hint == null))
            if (postponeTarget(plan) != null) add(PanelControl(POSTPONE, "Posponer ejercicio"))
        }
        if (mode == PanelMode.REST && !isPaused) {
            if (openRestSegment != null) add(PanelControl(REST_REDUCE, "−15 s"))
            add(PanelControl(REST_ADD, if (planned) "+15 s" else "+10 s"))
            add(if (needsOk) PanelControl(REST_OK, "OK") else PanelControl(REST_SKIP, "Saltar descanso"))
        }
        if (mode == PanelMode.CARDIO) {
            add(PanelControl(if (isPaused) RESUME else PAUSE, if (isPaused) "Reanudar" else "Pausar"))
            add(PanelControl(CARDIO_FINISH, "Terminar cardio"))
        }
        if (planned && !isPaused) {
            add(when {
                mode == PanelMode.WAIT -> PanelControl(WAIT_END, "Terminar espera")
                openMachineWait != null -> PanelControl(WAIT_CANCEL, "Cancelar espera pendiente")
                else -> PanelControl(WAIT_START, "Iniciar espera")
            })
        }
        if (planned && phase == Phase.Resting && records.isNotEmpty()) {
            add(PanelControl(EDIT_LAST, "Editar última serie"))
        }
        if (planned && mode != PanelMode.CARDIO) {
            add(PanelControl(if (isPaused) RESUME else PAUSE, if (isPaused) "Reanudar sesión" else "Pausar sesión"))
        }
    }
    return snapshot.copy(
        mode = mode,
        paused = isPaused,
        title = if (isPaused) "Sesión en pausa" else when (mode) {
            PanelMode.SET -> set?.exercise.orEmpty()
            PanelMode.REST -> "Descanso"
            PanelMode.WAIT -> "Espera por máquina"
            PanelMode.CARDIO -> currentCardio?.modality ?: "Cardio"
            else -> snapshot.title
        },
        subtitle = set?.let { "${it.setType.surfaceLabel()} · serie ${it.number}/${it.total}" }.orEmpty(),
        notice = seriesNotice(catalog)?.text,
        weight = set?.let { loadLabel(values.weightTenths, it.unit) },
        reps = values.reps,
        rir = values.rir,
        completionHint = hint,
        timer = timer,
        waitPending = openMachineWait?.pending != null && mode != PanelMode.WAIT,
        alert = needsOk && openRestSegment != null,
        controls = controls,
    )
}

internal fun PanelAction.workoutEvent(): WorkoutEvent? = when (this) {
    WEIGHT_DOWN -> WorkoutEvent.DecrementWeight
    WEIGHT_UP -> WorkoutEvent.IncrementWeight
    REPS_DOWN -> WorkoutEvent.DecrementReps
    REPS_UP -> WorkoutEvent.IncrementReps
    SELECT_RIR -> WorkoutEvent.SelectRir
    COMPLETE_SET -> WorkoutEvent.CompleteSet
    POSTPONE -> WorkoutEvent.PostponeExercise
    REST_ADD -> WorkoutEvent.AddRestTime
    REST_REDUCE -> WorkoutEvent.ReduceRestTime
    REST_SKIP -> WorkoutEvent.SkipRest
    REST_OK -> WorkoutEvent.ConfirmRest
    WAIT_START -> WorkoutEvent.StartMachineWait
    WAIT_CANCEL -> WorkoutEvent.CancelPendingWait
    WAIT_END -> WorkoutEvent.EndMachineWait
    PAUSE -> WorkoutEvent.PauseSession
    RESUME -> WorkoutEvent.ResumeSession
    CARDIO_FINISH -> WorkoutEvent.FinishCardio
    EDIT_LAST -> null
}
