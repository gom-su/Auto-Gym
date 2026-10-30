package com.example.workoutpoc.notification

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.workoutpoc.MainActivity
import com.example.workoutpoc.R
import com.example.workoutpoc.data.DemoWorkout
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.timer.clockSnapshot

/** One stable ID for the whole session, including foreground-service ownership. */
class WorkoutNotificationManager(private val context: Context) {
    companion object { const val ID: Int = 7; const val CHANNEL: String = "workout_interactive" }
    private var publishedState: WorkoutState? = null
    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Entrenamiento interactivo", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controles de series y descanso del demo offline"
            })
    }

    private fun openEditor(): PendingIntent = PendingIntent.getActivity(context, 1,
        Intent(context, MainActivity::class.java).setAction("EDIT").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun action(s: WorkoutState, event: WorkoutEvent, rir: Int? = null): PendingIntent {
        val intent = Intent(context, WorkoutActionReceiver::class.java).apply {
            action = event.name
            // These are direct user interactions, not deferrable background work.
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            // Identity includes revision: an old pending intent can never mutate a later set.
            // New identity also replaces pre-fix PendingIntents after an in-place APK
            // upgrade: FLAG_UPDATE_CURRENT only replaces extras, not base Intent flags.
            data = Uri.Builder().scheme("workout").authority("interactive-command-v2")
                .appendPath(s.sessionId).appendPath(s.index.toString()).appendPath(s.revision.toString())
                .appendPath(event.name).appendPath((rir ?: -1).toString()).build()
            putExtra("session", s.sessionId); putExtra("index", s.index)
            putExtra("revision", s.revision); putExtra("rir", rir ?: -1)
        }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun build(s: WorkoutState): Notification {
        val title: String = when (s.phase) {
            Phase.ActiveSet -> DemoWorkout.sets[s.index].let { "${it.exercise} · ${it.number}/${it.total}" }
            Phase.Resting -> "Descanso · siguiente: ${DemoWorkout.sets[(s.index + 1).coerceAtMost(6)].exercise}"
            Phase.Completed -> "Entrenamiento terminado"
            Phase.Idle -> "Preparando entrenamiento"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_workout).setContentTitle(title)
            .setContentText("Abre para ver el entrenamiento")
            .setContentIntent(openEditor()).setOnlyAlertOnce(true)
            .setOngoing(s.phase == Phase.ActiveSet || s.phase == Phase.Resting)
            .setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).setShowWhen(false)
        if (s.phase == Phase.Completed || s.phase == Phase.Idle) return builder.build()
        val compact = RemoteViews(context.packageName, R.layout.notification_compact)
        compact.setTextViewText(R.id.compact_title, if (s.phase == Phase.ActiveSet)
            "$title\n${weightLabel(s.values.weightTenths)} kg · ${s.values.reps} reps" else "Descanso · 10 s o tiempo ampliado")
        compact.setTextViewText(R.id.compact_complete, if (s.phase == Phase.Resting) "Saltar" else "Completar")
        compact.setOnClickPendingIntent(R.id.compact_complete, action(s,
            if (s.phase == Phase.Resting) WorkoutEvent.SkipRest else WorkoutEvent.CompleteSet))
        compact.setOnClickPendingIntent(R.id.compact_edit, openEditor())
        val expanded = RemoteViews(context.packageName, R.layout.notification_expanded)
        expanded.setTextViewText(R.id.title, title)
        expanded.setViewVisibility(R.id.set_controls, if (s.phase == Phase.ActiveSet) View.VISIBLE else View.GONE)
        expanded.setViewVisibility(R.id.rest_controls, if (s.phase == Phase.Resting) View.VISIBLE else View.GONE)
        if (s.phase == Phase.ActiveSet) {
            expanded.setTextViewText(R.id.weight, "${weightLabel(s.values.weightTenths)} kg")
            expanded.setTextViewText(R.id.reps, "${s.values.reps} reps")
            mapOf(R.id.weight_minus to WorkoutEvent.DecrementWeight, R.id.weight_plus to WorkoutEvent.IncrementWeight,
                R.id.reps_minus to WorkoutEvent.DecrementReps, R.id.reps_plus to WorkoutEvent.IncrementReps,
                R.id.complete to WorkoutEvent.CompleteSet).forEach { (id, event) ->
                expanded.setOnClickPendingIntent(id, action(s, event))
            }
            listOf(R.id.rir0, R.id.rir1, R.id.rir2, R.id.rir3, R.id.rir4).forEachIndexed { rir, id ->
                expanded.setTextViewText(id, "${if (s.values.rir == rir) "●" else ""}${if (rir == 4) "4+" else rir}")
                expanded.setContentDescription(id, "RIR $rir${if (s.values.rir == rir) ", seleccionado" else ""}")
                expanded.setTextColor(id, if (s.values.rir == rir) Color.rgb(35, 100, 15) else Color.DKGRAY)
                expanded.setOnClickPendingIntent(id, action(s, WorkoutEvent.SelectRir, rir))
            }
            expanded.setTextViewText(R.id.complete, if (s.values.reps == 0) "Ajusta reps: mínimo 1" else "Completar serie")
        } else {
            val remaining: Long = s.rest?.remainingMillis(clockSnapshot(context)) ?: 0
            expanded.setChronometerCountDown(R.id.countdown, true)
            expanded.setChronometer(R.id.countdown, SystemClock.elapsedRealtime() + remaining, null, true)
            expanded.setOnClickPendingIntent(R.id.add_rest, action(s, WorkoutEvent.AddRestTime))
            expanded.setOnClickPendingIntent(R.id.skip_rest, action(s, WorkoutEvent.SkipRest))
        }
        return builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compact).setCustomBigContentView(expanded).build()
    }

    @Suppress("MissingPermission")
    @Synchronized
    fun update(s: WorkoutState, force: Boolean = false) {
        if (!force && publishedState == s) return
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(ID, build(s))
            publishedState = s
            Log.d("WorkoutNotification", "posted revision=${s.revision} at=${SystemClock.elapsedRealtime()}")
        }
    }
}
