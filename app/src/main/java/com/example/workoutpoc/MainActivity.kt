package com.example.workoutpoc

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import com.example.workoutpoc.planning.*
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.overlay.WorkoutOverlayService
import com.example.workoutpoc.presentation.*
import com.example.workoutpoc.timer.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: WorkoutViewModel by viewModels()
    private val programModel: ProgramViewModel by viewModels()
    private val programFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(programModel::previewFile)
    }
    private var editorVisible: Boolean by mutableStateOf(false)
    private var notificationEnabled: Boolean by mutableStateOf(false)
    private var overlayGranted: Boolean by mutableStateOf(false)
    private var message: String? by mutableStateOf(null)
    private var pendingMode: String? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationEnabled = granted && NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (!granted) message = "Permiso denegado: el registro en la Activity sigue disponible; la notificación no aparecerá."
        pendingMode?.let { pendingMode = null; begin(it) }
    }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlayGranted = Settings.canDrawOverlays(this)
        if (overlayGranted) requestMode(WorkoutOverlayService.SHOW)
        else message = "Permiso de superposición no concedido. Puedes usar la notificación o registrar aquí."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        editorVisible = intent.action == "EDIT"
        pendingMode = savedInstanceState?.getString("pendingMode")
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                workoutApp.safely { RestTimer(workoutApp.repository).run() }
            }
        }
        setContent {
            var showPrograms by rememberSaveable { mutableStateOf(false) }
            BackHandler(showPrograms) { showPrograms = false }
            if (showPrograms) {
                val library by programModel.library.collectAsStateWithLifecycle()
                val ui by programModel.ui.collectAsStateWithLifecycle()
                ProgramScreen(library, ui, { showPrograms = false },
                    { programFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    programModel::previewText, programModel::save, programModel::cancelPreview, programModel::restore)
                return@setContent
            }
            val state by model.state.collectAsStateWithLifecycle()
            val ready by model.ready.collectAsStateWithLifecycle()
            val error by model.error.collectAsStateWithLifecycle()
            WorkoutScreen(state, ready, error, notificationEnabled, overlayGranted, message, editorVisible,
                { editorVisible = false }, { requestMode(WorkoutOverlayService.HIDE) }, {
                    if (Settings.canDrawOverlays(this)) requestMode(WorkoutOverlayService.SHOW)
                    else {
                        message = "Autoriza Mostrar sobre otras aplicaciones para abrir el panel flotante."
                        try { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                        catch (e: RuntimeException) { Log.w("WorkoutActivity", "Ajustes de overlay no disponibles", e); message = "Este dispositivo no permite abrir los ajustes del panel flotante." }
                    }
                }, {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }, model::retry, { clockSnapshot(this) }, ::dispatch, { showPrograms = true })
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == "EDIT") editorVisible = true
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingMode", pendingMode)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        notificationEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        overlayGranted = Settings.canDrawOverlays(this)
    }

    private fun requestMode(mode: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingMode = mode
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else begin(mode)
    }

    private fun begin(mode: String) {
        lifecycleScope.launch {
            workoutApp.safely {
                val repo = workoutApp.repository
                repo.restore()
                if (repo.state.value.phase == Phase.Idle) repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
                if (repo.state.value.phase == Phase.ActiveSet || repo.state.value.phase == Phase.Resting) {
                    startWorkoutService(mode)
                    if (notificationEnabled) message = if (mode == WorkoutOverlayService.SHOW)
                        "Panel flotante abierto. Puedes cambiar a otra aplicación." else "Despliega la notificación para registrar sin salir de tu aplicación."
                }
            }
        }
    }

    private fun startWorkoutService(mode: String) {
        try { ContextCompat.startForegroundService(this, Intent(this, WorkoutOverlayService::class.java).setAction(mode)) }
        catch (e: RuntimeException) { Log.w("WorkoutActivity", "No se pudo iniciar el servicio", e); message = "No se pudo activar el segundo plano. El registro sigue disponible aquí." }
    }

    private fun dispatch(command: WorkoutCommand) {
        lifecycleScope.launch {
            workoutApp.safely {
                workoutApp.repository.dispatch(command)
                // Undo from the final summary resumes the same session and the service explicitly.
                if (command.event == WorkoutEvent.UndoLastSet && workoutApp.repository.state.value.phase == Phase.ActiveSet)
                    startWorkoutService(WorkoutOverlayService.HIDE)
            }
        }
    }
}
