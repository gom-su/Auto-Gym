package com.example.workoutpoc.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.workoutpoc.MainActivity
import com.example.workoutpoc.R
import com.example.workoutpoc.WorkoutApplication
import com.example.workoutpoc.wearprotocol.PanelProtocol
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await

internal class PhoneWearBridge(private val app: WorkoutApplication) {
    val handler = PanelRequestHandler(app.repository, { app.catalog.catalog.value.exercises }, ::requestEdit)

    /** The marker contains no workout content. Connected watches read the live panel on demand. */
    suspend fun publishChanges() {
        val reachable = MutableStateFlow(false)
        val capabilities = Wearable.getCapabilityClient(app)
        val data = Wearable.getDataClient(app)
        val listener = CapabilityClient.OnCapabilityChangedListener { reachable.value = it.nodes.isNotEmpty() }
        capabilities.addListener(listener, PanelProtocol.WATCH_CAPABILITY).await()
        try {
            reachable.value = capabilities.getCapability(PanelProtocol.WATCH_CAPABILITY,
                CapabilityClient.FILTER_REACHABLE).await().nodes.isNotEmpty()
            combine(app.repository.state, app.catalog.catalog, reachable) { state, catalog, connected ->
                if (connected) state to catalog else null
            }.distinctUntilChanged().collect { changed ->
                if (changed != null) try {
                    val marker = PutDataMapRequest.create(PanelProtocol.CHANGED_PATH).apply {
                        dataMap.putString("change", UUID.randomUUID().toString())
                    }
                    data.putDataItem(marker.asPutDataRequest().setUrgent()).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("AutoGymWear", "No se pudo anunciar el cambio de sesión", e)
                }
            }
        } finally {
            capabilities.removeListener(listener, PanelProtocol.WATCH_CAPABILITY)
        }
    }

    private fun requestEdit(sessionId: String, index: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return false
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(EDIT_CHANNEL, "Edición desde el reloj",
            NotificationManager.IMPORTANCE_LOW))
        if (manager.getNotificationChannel(EDIT_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE)
            return false
        val intent = Intent(app, MainActivity::class.java).apply {
            action = "EDIT_LAST_RECORD"
            putExtra("session", sessionId)
            putExtra("recordIndex", index)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val tap = PendingIntent.getActivity(app, EDIT_NOTIFICATION, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        manager.notify(EDIT_NOTIFICATION, NotificationCompat.Builder(app, EDIT_CHANNEL)
            .setSmallIcon(R.drawable.ic_workout)
            .setContentTitle("Editar última serie")
            .setContentText("Toca para abrir la serie en Auto-Gym.")
            .setContentIntent(tap)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setAutoCancel(true)
            .build())
        return true
    }

    private companion object {
        const val EDIT_CHANNEL = "wear_record_editor"
        const val EDIT_NOTIFICATION = 841
    }
}
