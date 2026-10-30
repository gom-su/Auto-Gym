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
import com.example.workoutpoc.data.DemoWorkout
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.timer.clockSnapshot
import kotlin.math.abs

/** Owns only the window. All user actions are commands against the displayed snapshot. */
class WorkoutOverlayController(private val context: Context, private val dispatch: (WorkoutCommand) -> Unit) {
    private val manager: WindowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var collapsed: Boolean = false
    private var wanted: Boolean = false
    private var snapshot: WorkoutState = WorkoutState()
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
        if (view == null) rebuild()
        val root: View = view ?: return
        if (collapsed) {
            (root as TextView).text = if (state.phase == Phase.Resting) "${seconds(state)}s" else "${state.records.size}/7"
            return
        }
        val active: Boolean = state.phase == Phase.ActiveSet
        val resting: Boolean = state.phase == Phase.Resting
        root.findViewById<View>(R.id.overlay_set).visibility = if (active) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.overlay_rest).visibility = if (resting) View.VISIBLE else View.GONE
        root.findViewById<TextView>(R.id.overlay_title).text = when (state.phase) {
            Phase.ActiveSet -> DemoWorkout.sets[state.index].exercise
            Phase.Resting -> "Descanso"
            Phase.Completed -> "Entrenamiento terminado"
            Phase.Idle -> "Inicia desde la aplicación"
        }
        root.findViewById<TextView>(R.id.overlay_subtitle).text = if (resting)
            "Siguiente: ${DemoWorkout.sets[(state.index + 1).coerceAtMost(6)].exercise}"
        else "${state.records.size}/7 completadas · serie ${DemoWorkout.sets[state.index].number}/${DemoWorkout.sets[state.index].total}"
        root.findViewById<TextView>(R.id.overlay_weight).text = "${weightLabel(state.values.weightTenths)} kg"
        root.findViewById<TextView>(R.id.overlay_reps).text = "${state.values.reps} reps"
        root.findViewById<Button>(R.id.overlay_complete).isEnabled = state.values.reps > 0
        root.findViewById<TextView>(R.id.overlay_countdown).text = "${seconds(state)} s"
        listOf(R.id.overlay_rir0, R.id.overlay_rir1, R.id.overlay_rir2, R.id.overlay_rir3, R.id.overlay_rir4)
            .forEachIndexed { rir, id ->
                root.findViewById<Button>(id).apply {
                    text = "${if (state.values.rir == rir) "●" else ""}${if (rir == 4) "4+" else rir}"
                    contentDescription = "RIR $rir${if (state.values.rir == rir) ", seleccionado" else ""}"
                    setTextColor(if (state.values.rir == rir) Color.rgb(180, 238, 128) else Color.WHITE)
                }
            }
    }

    private fun seconds(s: WorkoutState): Long = ((s.rest?.remainingMillis(clockSnapshot(context)) ?: 0) + 999) / 1000

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
            findViewById<View>(R.id.close).setOnClickListener { close() }
            findViewById<View>(R.id.collapse).setOnClickListener { collapsed = true; rebuild() }
            mapOf(R.id.overlay_weight_minus to WorkoutEvent.DecrementWeight,
                R.id.overlay_weight_plus to WorkoutEvent.IncrementWeight,
                R.id.overlay_reps_minus to WorkoutEvent.DecrementReps,
                R.id.overlay_reps_plus to WorkoutEvent.IncrementReps,
                R.id.overlay_complete to WorkoutEvent.CompleteSet,
                R.id.overlay_add to WorkoutEvent.AddRestTime,
                R.id.overlay_skip to WorkoutEvent.SkipRest).forEach { (id, event) ->
                findViewById<View>(id).setOnClickListener { dispatch(snapshot.command(event)) }
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
