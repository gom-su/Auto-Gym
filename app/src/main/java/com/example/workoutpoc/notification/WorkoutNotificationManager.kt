package com.example.workoutpoc.notification

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.workoutpoc.MainActivity
import com.example.workoutpoc.R
import com.example.workoutpoc.data.activePlan
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.presentation.*
import com.example.workoutpoc.timer.clockSnapshot
import com.example.workoutpoc.workoutApp

/** One stable ID. Text clocks share the app's rounding, including negative rest and ascending wait. */
class WorkoutNotificationManager(private val context: Context) {
    companion object { const val ID = 7; const val CHANNEL = "workout_interactive" }
    private var publishedState: WorkoutState? = null
    private var publishedTimer: SurfaceTimer? = null
    private var publishedCatalog: Any? = null
    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Entrenamiento interactivo", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controles de series, descanso y espera por máquina"
            })
    }

    private fun openEditor(s: WorkoutState, lastRecord: Boolean = false): PendingIntent {
        val record = s.records.lastOrNull().takeIf { lastRecord && s.source != null }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = if (record == null) "EDIT" else "EDIT_LAST_RECORD"
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (record != null) {
                data = Uri.Builder().scheme("workout").authority("edit-record").appendPath(s.sessionId).appendPath(record.index.toString()).build()
                putExtra("session", s.sessionId); putExtra("recordIndex", record.index)
            }
        }
        return PendingIntent.getActivity(context, if (record == null) 1 else 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(s: WorkoutState, event: WorkoutEvent, rir: Int? = null): PendingIntent {
        val intent = Intent(context, WorkoutActionReceiver::class.java).apply {
            action = event.name
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            data = Uri.Builder().scheme("workout").authority("interactive-command-v2")
                .appendPath(s.sessionId).appendPath(s.index.toString()).appendPath(s.revision.toString())
                .appendPath(event.name).appendPath((rir ?: -1).toString()).build()
            putExtra("session", s.sessionId); putExtra("index", s.index)
            putExtra("revision", s.revision); putExtra("rir", rir ?: -1)
            putExtra("restSegmentId", s.openRestSegment?.id)
        }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun build(s: WorkoutState): Notification {
        val now = clockSnapshot(context)
        val timer = s.surfaceTimer(now)
        val waiting = timer?.waiting == true
        val paused = s.paused != null
        val current = s.activePlan().getOrNull(s.index)
        val title = when {
            paused -> "Sesión en pausa"
            waiting -> "Espera por máquina"
            s.phase == Phase.ActiveSet -> current?.let { "${it.exercise} · ${it.setType.surfaceLabel()} ${it.number}/${it.total}" }.orEmpty()
            s.phase == Phase.ActiveCardio -> s.currentCardio?.modality ?: "Cardio"
            s.phase == Phase.Resting -> "Descanso"
            s.phase == Phase.Completed -> "Entrenamiento terminado"
            else -> "Preparando entrenamiento"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_workout)
            .setContentTitle(title).setContentText("Abre para ver el entrenamiento").setContentIntent(openEditor(s))
            .setOnlyAlertOnce(true).setOngoing(s.phase.inProgress).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).setShowWhen(false)
        if (!s.phase.inProgress) return builder.build()

        val compact = RemoteViews(context.packageName, R.layout.notification_compact)
        val expanded = RemoteViews(context.packageName, R.layout.notification_expanded)
        fun visible(id: Int, show: Boolean) = expanded.setViewVisibility(id, if (show) View.VISIBLE else View.GONE)
        fun bind(id: Int, text: String, event: WorkoutEvent, enabled: Boolean = true) {
            expanded.setTextViewText(id, text)
            expanded.setBoolean(id, "setEnabled", enabled)
            expanded.setOnClickPendingIntent(id, action(s, event))
        }
        expanded.setTextViewText(R.id.title, title)
        compact.setTextViewText(R.id.compact_title, if (s.phase == Phase.ActiveSet && !waiting && !paused)
            "$title\n${loadLabel(s.values.weightTenths, current?.unit ?: WeightUnit.KG)} · ${s.values.reps} reps" else title)
        compact.setViewVisibility(R.id.compact_countdown, if (timer == null) View.GONE else View.VISIBLE)
        compact.setTextViewText(R.id.compact_countdown, timer?.text.orEmpty())
        visible(R.id.countdown, timer != null)
        expanded.setTextViewText(R.id.countdown, timer?.text.orEmpty())
        compact.setOnClickPendingIntent(R.id.compact_edit, openEditor(s, lastRecord = s.phase == Phase.Resting))

        val balance = s.restSignedBalance(now) ?: s.rest?.signedRemainingMillis(now)
        val expired = s.source != null && balance != null && balance <= 0
        val primary = when {
            paused -> WorkoutEvent.ResumeSession
            waiting -> WorkoutEvent.EndMachineWait
            s.phase == Phase.ActiveCardio -> WorkoutEvent.PauseSession
            s.phase == Phase.Resting -> if (expired) WorkoutEvent.ConfirmRest else WorkoutEvent.SkipRest
            else -> WorkoutEvent.CompleteSet
        }
        val hint = s.completionHint()
        val primaryText = when {
            paused -> "Reanudar"
            waiting -> "Terminar espera"
            s.phase == Phase.ActiveCardio -> "Pausar"
            s.phase == Phase.Resting -> if (expired) "OK" else "Saltar"
            else -> hint ?: "Completar"
        }
        compact.setTextViewText(R.id.compact_complete, primaryText)
        compact.setBoolean(R.id.compact_complete, "setEnabled", primary != WorkoutEvent.CompleteSet || hint == null)
        compact.setOnClickPendingIntent(R.id.compact_complete, action(s, primary))

        val notice = s.seriesNotice(context.workoutApp.catalog.catalog.value.exercises)
        visible(R.id.rest_next, notice != null)
        notice?.let {
            val signals = it.changes.filter { c -> c == "Cambia de máquina" || c.startsWith("Carga") || c.startsWith("Repeticiones") }
            val text = it.compactText()
            expanded.setTextViewText(R.id.rest_next, SpannableString(text).apply {
                signals.forEach { signal -> val start = text.indexOf(signal); if (start >= 0) setSpan(StyleSpan(Typeface.BOLD), start, start + signal.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            })
        }
        visible(R.id.set_controls, s.phase == Phase.ActiveSet && !paused && !waiting)
        visible(R.id.notification_rir, current?.setType == SetType.WORK)
        if (s.phase == Phase.ActiveSet) {
            expanded.setTextViewText(R.id.weight, loadLabel(s.values.weightTenths, current?.unit ?: WeightUnit.KG))
            expanded.setTextViewText(R.id.reps, "${s.values.reps} reps")
            mapOf(R.id.weight_minus to WorkoutEvent.DecrementWeight, R.id.weight_plus to WorkoutEvent.IncrementWeight,
                R.id.reps_minus to WorkoutEvent.DecrementReps, R.id.reps_plus to WorkoutEvent.IncrementReps).forEach { (id, event) ->
                expanded.setOnClickPendingIntent(id, action(s, event))
            }
            listOf(R.id.rir0, R.id.rir1, R.id.rir2, R.id.rir3, R.id.rir4).forEachIndexed { rir, id ->
                expanded.setTextViewText(id, "${if (s.values.rir == rir) "●" else ""}${rirLabel(rir)}")
                expanded.setContentDescription(id, "RIR $rir${if (s.values.rir == rir) ", seleccionado" else ""}")
                expanded.setTextColor(id, context.getColor(if (s.values.rir == rir) R.color.notification_selected else R.color.notification_text))
                expanded.setOnClickPendingIntent(id, action(s, WorkoutEvent.SelectRir, rir))
            }
            bind(R.id.complete, hint ?: "Completar serie", WorkoutEvent.CompleteSet, hint == null)
        }
        visible(R.id.rest_controls, paused || (!waiting && (s.phase == Phase.Resting || s.phase == Phase.ActiveCardio)))
        visible(R.id.reduce_rest, s.phase == Phase.Resting && s.openRestSegment != null && !paused)
        if (s.phase == Phase.Resting && !paused) {
            bind(R.id.reduce_rest, "−15 s", WorkoutEvent.ReduceRestTime)
            bind(R.id.add_rest, if (s.source == null) "+10 s" else "+15 s", WorkoutEvent.AddRestTime)
            bind(R.id.skip_rest, if (expired) "OK" else "Saltar descanso", primary)
        } else {
            bind(R.id.add_rest, if (paused) "Reanudar" else "Pausar", if (paused) WorkoutEvent.ResumeSession else WorkoutEvent.PauseSession)
            visible(R.id.skip_rest, s.phase == Phase.ActiveCardio && !waiting)
            bind(R.id.skip_rest, "Terminar cardio", WorkoutEvent.FinishCardio)
        }
        visible(R.id.wait_status, s.openMachineWait?.pending != null && !waiting)
        visible(R.id.machine_wait, s.source != null && !paused)
        val waitEvent = when { waiting -> WorkoutEvent.EndMachineWait; s.openMachineWait != null -> WorkoutEvent.CancelPendingWait; else -> WorkoutEvent.StartMachineWait }
        bind(R.id.machine_wait, when (waitEvent) {
            WorkoutEvent.EndMachineWait -> "Terminar espera"; WorkoutEvent.CancelPendingWait -> "Cancelar espera pendiente"; else -> "Iniciar espera"
        }, waitEvent)
        val editLast = s.source != null && s.phase == Phase.Resting && s.records.isNotEmpty()
        visible(R.id.edit_last, editLast)
        expanded.setOnClickPendingIntent(R.id.edit_last, openEditor(s, lastRecord = true))
        return builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compact).setCustomBigContentView(expanded).build()
    }

    @Suppress("MissingPermission")
    @Synchronized
    fun update(s: WorkoutState, force: Boolean = false) {
        val timer = s.surfaceTimer(clockSnapshot(context))
        val catalog = context.workoutApp.catalog.catalog.value
        if (!force && publishedState == s && publishedTimer == timer && publishedCatalog == catalog) return
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(ID, build(s))
            publishedState = s; publishedTimer = timer; publishedCatalog = catalog
            Log.d("WorkoutNotification", "posted revision=${s.revision} at=${SystemClock.elapsedRealtime()}")
        }
    }
}
