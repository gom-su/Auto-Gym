package com.example.workoutpoc.overlay

import android.app.KeyguardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Color
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.TextView
import com.example.workoutpoc.R
import android.content.Intent
import com.example.workoutpoc.MainActivity
import com.example.workoutpoc.workoutApp
import com.example.workoutpoc.presentation.*
import com.example.workoutpoc.data.activePlan
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.presentation.durationLabel
import com.example.workoutpoc.presentation.overlayText
import com.example.workoutpoc.history.review
import com.example.workoutpoc.timer.clockSnapshot
import kotlin.math.abs

/** Owns only the window. All user actions are commands against the displayed snapshot. */
class WorkoutOverlayController(private val context: Context, private val onDismiss: () -> Unit,
                               private val dispatch: (WorkoutCommand) -> Unit) {
    private val manager: WindowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var collapsed: Boolean = false
    private var wanted: Boolean = false
    private var snapshot: WorkoutState = WorkoutState()
    private var completedSession: String? = null
    private var completedState: WorkoutState? = null
    private var completedText: String = ""
    val isRequested get() = wanted
    private val params = WindowManager.LayoutParams(280.dp(), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = 16.dp(); y = 100.dp()
    }
    private fun Int.dp(): Int = (this * context.resources.displayMetrics.density).toInt()

    fun show() { wanted = true; collapsed = false; rebuild() }
    fun close() { wanted = false; remove() }

    fun render(state: WorkoutState) {
        snapshot = state
        if (!wanted) return
        val locked: Boolean = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val awake: Boolean = context.getSystemService(PowerManager::class.java).isInteractive
        if (locked || !awake || !Settings.canDrawOverlays(context)) { remove(); return }
        val reviewReady = state.source != null && state.phase == Phase.Completed
        if (reviewReady && collapsed) { collapsed = false; rebuild() }
        if (view == null) rebuild()
        val root: View = view ?: return
        if (!collapsed) {
            root.findViewById<View>(R.id.overlay_summary).visibility = if (reviewReady) View.VISIBLE else View.GONE
            root.findViewById<View>(R.id.collapse).visibility = if (reviewReady) View.INVISIBLE else View.VISIBLE
        }
        if (reviewReady) {
            renderReview(root, state)
            return
        }
        val plan = state.activePlan()
        val current = plan.getOrNull(state.index)
        val now = clockSnapshot(context)
        val timer = state.surfaceTimer(now)
        val waiting = timer?.waiting == true
        val paused = state.paused != null
        if (collapsed) {
            (root as TextView).apply {
                textSize = 12f
                text = timer?.let { "${if (waiting) "Espera" else if (state.phase == Phase.Resting) "Descanso" else "Cardio"}\n${it.text}" }
                    ?: "${current?.setType?.surfaceLabel().orEmpty()}\n${state.records.size}/${plan.size}"
                contentDescription = "Burbuja de entrenamiento. ${timer?.label ?: current?.setType?.surfaceLabel().orEmpty()} ${timer?.text.orEmpty()}. Toca para expandir"
            }
            return
        }
        fun visible(id: Int, show: Boolean) { root.findViewById<View>(id).visibility = if (show) View.VISIBLE else View.GONE }
        fun label(id: Int, text: String) { root.findViewById<TextView>(id).text = text }
        val active = state.phase == Phase.ActiveSet && !paused && !waiting
        val resting = state.phase == Phase.Resting
        visible(R.id.overlay_set, active)
        visible(R.id.overlay_rest, resting || waiting)
        visible(R.id.overlay_rest_actions, resting && !paused && !waiting)
        visible(R.id.overlay_cardio, state.phase == Phase.ActiveCardio && !waiting)
        label(R.id.overlay_title, when {
            paused -> "Sesión en pausa"
            waiting -> "Espera por máquina"
            resting -> "Descanso"
            state.phase == Phase.ActiveCardio -> state.currentCardio?.modality ?: "Cardio"
            state.phase == Phase.ActiveSet -> current?.exercise.orEmpty()
            else -> "Inicia desde la aplicación"
        })
        label(R.id.overlay_subtitle, current?.let { "${it.setType.surfaceLabel()} · serie ${it.number}/${it.total}" }.orEmpty())
        val notice = state.seriesNotice(context.workoutApp.catalog.catalog.value.exercises)
        visible(R.id.overlay_notice, notice != null)
        label(R.id.overlay_notice, notice?.text.orEmpty())
        label(R.id.overlay_countdown, timer?.text.orEmpty())
        label(R.id.overlay_cardio_countdown, timer?.text.orEmpty())
        label(R.id.overlay_cardio_pause, if (paused) "Reanudar" else "Pausar")
        label(R.id.overlay_weight, current?.let { loadLabel(state.values.weightTenths, it.unit) }.orEmpty())
        label(R.id.overlay_reps, "${state.values.reps} reps")
        val hint = state.completionHint()
        root.findViewById<Button>(R.id.overlay_complete).isEnabled = hint == null
        label(R.id.overlay_complete, hint ?: "Completar serie")
        visible(R.id.overlay_rir, current?.setType == SetType.WORK)
        val postpone = state.postponeTarget(plan)
        visible(R.id.overlay_postpone, postpone != null && !waiting)
        root.findViewById<Button>(R.id.overlay_postpone).contentDescription = postpone?.let { "Posponer ${current?.exercise}; siguiente: ${it.nextExercise}" }
        visible(R.id.overlay_reduce, state.openRestSegment != null)
        label(R.id.overlay_add, if (state.source == null) "+10 s" else "+15 s")
        label(R.id.overlay_skip, if (restExpired(state)) "OK" else "Saltar descanso")
        visible(R.id.overlay_wait_status, state.openMachineWait?.pending != null && !waiting)
        visible(R.id.overlay_wait, state.source != null && state.phase.inProgress && !paused)
        label(R.id.overlay_wait, when {
            waiting -> "Terminar espera"
            state.openMachineWait != null -> "Cancelar espera pendiente"
            else -> "Iniciar espera"
        })
        visible(R.id.overlay_edit_last, resting && state.source != null && state.records.isNotEmpty())
        visible(R.id.overlay_pause, state.source != null && state.phase.inProgress && (state.phase != Phase.ActiveCardio || waiting))
        label(R.id.overlay_pause, if (paused) "Reanudar sesión" else "Pausar sesión")
        listOf(R.id.overlay_rir0, R.id.overlay_rir1, R.id.overlay_rir2, R.id.overlay_rir3, R.id.overlay_rir4).forEachIndexed { rir, id ->
            root.findViewById<Button>(id).apply {
                text = "${if (state.values.rir == rir) "●" else ""}${rirLabel(rir)}"
                contentDescription = "RIR $rir${if (state.values.rir == rir) ", seleccionado" else ""}"
                setTextColor(if (state.values.rir == rir) Color.rgb(180, 238, 128) else Color.WHITE)
            }
        }
    }

    private fun renderReview(root: View, state: WorkoutState) {
        root.findViewById<View>(R.id.overlay_set).visibility = View.GONE
        root.findViewById<View>(R.id.overlay_rest).visibility = View.GONE
        root.findViewById<View>(R.id.overlay_cardio).visibility = View.GONE
        listOf(R.id.overlay_wait, R.id.overlay_wait_status, R.id.overlay_edit_last, R.id.overlay_notice, R.id.overlay_pause).forEach { root.findViewById<View>(it).visibility = View.GONE }
        val partial = state.endedEarly || !state.plannedCardioComplete || state.records.size != state.plan.size ||
            state.records.any { it.status == SetStatus.SKIPPED }
        val title = root.findViewById<TextView>(R.id.overlay_title)
        title.text = if (partial) "Entrenamiento parcial" else "¡Entrenamiento completado!"
        root.findViewById<TextView>(R.id.overlay_subtitle).text = if (partial) "Resultado guardado" else "Buen trabajo"
        if (completedState != state) {
            completedText = state.review().overlayText()
            completedState = state
        }
        root.findViewById<TextView>(R.id.overlay_summary_text).text = completedText
        if (completedSession != state.sessionId) {
            completedSession = state.sessionId
            if (!partial) {
                title.alpha = 0f
                title.animate().alpha(1f).setDuration(280).start()
            }
        }
    }

    private fun restExpired(state: WorkoutState): Boolean =
        state.source != null && (state.restSignedBalance(clockSnapshot(context)) ?: 1) <= 0

    private fun rebuild() {
        remove()
        if (!wanted || !Settings.canDrawOverlays(context) ||
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !context.getSystemService(PowerManager::class.java).isInteractive) return
        val root: View = if (collapsed) TextView(context).apply {
            gravity = Gravity.CENTER; textSize = 20f; setTextColor(Color.rgb(24, 34, 28))
            setBackgroundResource(R.drawable.bubble_background)
            contentDescription = "Burbuja de entrenamiento. Toca para expandir, arrastra para mover"
            setOnClickListener { collapsed = false; rebuild() }
        } else LayoutInflater.from(context).inflate(R.layout.overlay_panel, null).apply {
            findViewById<View>(R.id.close).setOnClickListener { close(); onDismiss() }
            findViewById<View>(R.id.collapse).setOnClickListener { collapsed = true; rebuild() }
            mapOf(R.id.overlay_weight_minus to WorkoutEvent.DecrementWeight,
                R.id.overlay_weight_plus to WorkoutEvent.IncrementWeight,
                R.id.overlay_reps_minus to WorkoutEvent.DecrementReps,
                R.id.overlay_reps_plus to WorkoutEvent.IncrementReps,
                R.id.overlay_complete to WorkoutEvent.CompleteSet,
                R.id.overlay_postpone to WorkoutEvent.PostponeExercise,
                R.id.overlay_add to WorkoutEvent.AddRestTime,
                R.id.overlay_reduce to WorkoutEvent.ReduceRestTime).forEach { (id, event) ->
                findViewById<View>(id).setOnClickListener { dispatch(snapshot.command(event)) }
            }
            findViewById<View>(R.id.overlay_wait).setOnClickListener {
                val event = when {
                    snapshot.isWaiting(clockSnapshot(context)) -> WorkoutEvent.EndMachineWait
                    snapshot.openMachineWait != null -> WorkoutEvent.CancelPendingWait
                    else -> WorkoutEvent.StartMachineWait
                }
                dispatch(snapshot.command(event))
            }
            findViewById<View>(R.id.overlay_edit_last).setOnClickListener {
                val record = snapshot.records.lastOrNull() ?: return@setOnClickListener
                collapsed = true
                rebuild()
                context.startActivity(Intent(context, MainActivity::class.java).apply {
                    action = "EDIT_LAST_RECORD"
                    putExtra("session", snapshot.sessionId); putExtra("recordIndex", record.index)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
            findViewById<View>(R.id.overlay_pause).setOnClickListener {
                dispatch(snapshot.command(if (snapshot.paused == null) WorkoutEvent.PauseSession else WorkoutEvent.ResumeSession))
            }
            findViewById<View>(R.id.overlay_skip).setOnClickListener {
                dispatch(snapshot.command(if (restExpired(snapshot)) WorkoutEvent.ConfirmRest else WorkoutEvent.SkipRest))
            }
            findViewById<View>(R.id.overlay_cardio_pause).setOnClickListener {
                dispatch(snapshot.command(if (snapshot.paused == null) WorkoutEvent.PauseSession else WorkoutEvent.ResumeSession))
            }
            findViewById<View>(R.id.overlay_cardio_finish).setOnClickListener {
                dispatch(snapshot.command(WorkoutEvent.FinishCardio))
            }
            listOf(R.id.overlay_rir0, R.id.overlay_rir1, R.id.overlay_rir2, R.id.overlay_rir3, R.id.overlay_rir4)
                .forEachIndexed { rir, id -> findViewById<View>(id).setOnClickListener {
                    dispatch(snapshot.command(WorkoutEvent.SelectRir, rir))
                } }
        }
        params.width = if (collapsed) 64.dp() else minOf(280.dp(), context.resources.displayMetrics.widthPixels - 16.dp())
        params.height = if (collapsed) 64.dp() else WindowManager.LayoutParams.WRAP_CONTENT
        view = root
        installDrag(if (collapsed) root else root.findViewById(R.id.drag_handle), collapsed)
        try {
            manager.addView(root, params)
            root.post { clamp(); render(snapshot) }
        } catch (e: RuntimeException) { Log.w("WorkoutOverlay", "No se pudo abrir el panel", e); view = null; wanted = false }
    }

    @Suppress("ClickableViewAccessibility")
    private fun installDrag(handle: View, clickOnTap: Boolean) {
        var downX: Float = 0f; var downY: Float = 0f
        var startX: Int = 0; var startY: Int = 0; var dragged: Boolean = false
        val slop: Int = ViewConfiguration.get(context).scaledTouchSlop
        handle.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; dragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) dragged = true
                    if (dragged) {
                        params.x = startX + (event.rawX - downX).toInt()
                        params.y = startY + (event.rawY - downY).toInt()
                        clamp()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragged && clickOnTap) target.performClick(); true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    fun clamp() {
        val root: View = view ?: return
        val metrics = context.resources.displayMetrics
        params.x = params.x.coerceIn(0, (metrics.widthPixels - root.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - root.height - 48.dp()).coerceAtLeast(0))
        try { manager.updateViewLayout(root, params) }
        catch (e: RuntimeException) { Log.w("WorkoutOverlay", "Ventana retirada o permiso revocado", e); remove() }
    }

    private fun remove() {
        val root: View = view ?: return
        view = null
        try { manager.removeView(root) }
        catch (e: IllegalArgumentException) { Log.w("WorkoutOverlay", "Ventana ya retirada", e) }
    }
}
