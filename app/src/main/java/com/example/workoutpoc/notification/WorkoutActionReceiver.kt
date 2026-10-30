package com.example.workoutpoc.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.workoutApp
import kotlinx.coroutines.launch

/** Explicit, non-exported receiver; no Activity trampoline and no background service launch. */
class WorkoutActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event: WorkoutEvent = WorkoutEvent.entries.firstOrNull { it.name == intent.action } ?: return
        if (event !in allowed) return
        val session: String = intent.getStringExtra("session") ?: return
        val command = WorkoutCommand(event, session, intent.getIntExtra("index", -1),
            intent.getLongExtra("revision", -1), intent.getIntExtra("rir", -1))
        val pending: PendingResult = goAsync()
        val receivedAt: Long = SystemClock.elapsedRealtime()
        Log.d("WorkoutNotification", "received ${event.name} at=$receivedAt revision=${command.revision}")
        context.workoutApp.scope.launch {
            try {
                context.workoutApp.safely {
                    context.workoutApp.repository.dispatch(command)
                    Log.d("WorkoutNotification", "applied ${event.name} after=${SystemClock.elapsedRealtime() - receivedAt}ms")
                    context.workoutApp.notifications.update(context.workoutApp.repository.state.value)
                }
            } finally { pending.finish() }
        }
    }
    companion object {
        private val allowed: Set<WorkoutEvent> = setOf(WorkoutEvent.IncrementWeight, WorkoutEvent.DecrementWeight,
            WorkoutEvent.IncrementReps, WorkoutEvent.DecrementReps, WorkoutEvent.SelectRir,
            WorkoutEvent.CompleteSet, WorkoutEvent.AddRestTime, WorkoutEvent.SkipRest)
    }
}
