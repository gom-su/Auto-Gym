package com.example.workoutpoc

import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.migration.*
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
    private val migrationModel: MigrationViewModel by viewModels()
    private val catalogModel: com.example.workoutpoc.catalog.CatalogViewModel by viewModels()
    private val proposalModel: com.example.workoutpoc.proposal.ProposalViewModel by viewModels()
    private val bodyModel: com.example.workoutpoc.body.BodyViewModel by viewModels()
    private val adherenceModel: AdherenceViewModel by viewModels()
    private val migrationFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(migrationModel::previewFile)
    }
    private val programFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(programModel::previewFile)
    }
    private val exchangeModel: ExchangeViewModel by viewModels()
    private val exchangeFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(exchangeModel::previewFile)
    }
    private val exchangeDestination = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        exchangeModel.write(uri)
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
            var showExchange by rememberSaveable { mutableStateOf(false) }
            var showMigration by rememberSaveable { mutableStateOf(false) }
            var showCatalog by rememberSaveable { mutableStateOf(false) }
            var showProposals by rememberSaveable { mutableStateOf(false) }
            var showBody by rememberSaveable { mutableStateOf(false) }
            var showAdherence by rememberSaveable { mutableStateOf(false) }
            BackHandler(showAdherence) { showAdherence = false }
            if (showAdherence) {
                val adherenceUi by adherenceModel.ui.collectAsStateWithLifecycle()
                AdherenceScreen(adherenceUi, { showAdherence = false }, adherenceModel::refresh, adherenceModel::setNotPerformed, adherenceModel::saveGoals)
                return@setContent
            }
            BackHandler(showBody) { showBody = false }
            if (showBody) {
                val log by bodyModel.log.collectAsStateWithLifecycle()
                val bodyUi by bodyModel.ui.collectAsStateWithLifecycle()
                com.example.workoutpoc.body.BodyScreen(log, bodyUi, { showBody = false }, bodyModel::refresh, bodyModel::add, bodyModel::remove)
                return@setContent
            }
            BackHandler(showProposals) { showProposals = false }
            if (showProposals) {
                val proposalState by proposalModel.state.collectAsStateWithLifecycle()
                val proposalUi by proposalModel.ui.collectAsStateWithLifecycle()
                com.example.workoutpoc.proposal.ProposalScreen(proposalState, proposalUi, { showProposals = false; programModel.restore() },
                    proposalModel::refresh, proposalModel::select, proposalModel::approve, proposalModel::reject, proposalModel::saveRules,
                    proposalModel::addExpectation, proposalModel::setManualOutcome)
                return@setContent
            }
            BackHandler(showCatalog) { showCatalog = false }
            if (showCatalog) {
                val catalog by catalogModel.catalog.collectAsStateWithLifecycle()
                val catalogUi by catalogModel.ui.collectAsStateWithLifecycle()
                com.example.workoutpoc.catalog.CatalogScreen(catalog, catalogUi, { showCatalog = false },
                    catalogModel::refresh, catalogModel::save, catalogModel.annotations.collectAsStateWithLifecycle().value)
                return@setContent
            }
            BackHandler(showMigration) { showMigration = false }
            if (showMigration) {
                val archive by migrationModel.archive.collectAsStateWithLifecycle()
                val migrationUi by migrationModel.ui.collectAsStateWithLifecycle()
                MigrationScreen(archive, migrationUi, { showMigration = false },
                    // Providers disagree on Markdown MIME types (including null). Validate bytes, not the picker label.
                    { migrationFile.launch(arrayOf("*/*")) },
                    migrationModel::previewText, migrationModel::save, migrationModel::cancel,
                    migrationModel::select, migrationModel::restore, migrationModel::acknowledge)
                return@setContent
            }
            BackHandler(showExchange) { showExchange = false }
            if (showExchange) {
                val archive by exchangeModel.archive.collectAsStateWithLifecycle()
                val exchangeUi by exchangeModel.ui.collectAsStateWithLifecycle()
                ExchangeScreen(archive, exchangeUi, { showExchange = false },
                    { exchangeFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    exchangeModel::previewText, exchangeModel::save, exchangeModel::cancel,
                    exchangeModel::restore, exchangeModel::select, exchangeModel::generate,
                    { include -> if (exchangeModel.stageExport(include)) exchangeDestination.launch("auto-gym-interchange-v1.json") },
                    exchangeModel::copy)
                return@setContent
            }
            BackHandler(showPrograms) { showPrograms = false }
            if (showPrograms) {
                val library by programModel.library.collectAsStateWithLifecycle()
                val ui by programModel.ui.collectAsStateWithLifecycle()
                val annotations by programModel.annotations.collectAsStateWithLifecycle()
                ProgramScreen(library, ui, { showPrograms = false },
                    { programFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    programModel::previewText, programModel::save, programModel::cancelPreview, programModel::restore,
                    { showExchange = true }, { showMigration = true }, { program, weekId, dayId ->
                        lifecycleScope.launch {
                            workoutApp.safely {
                                val selection = program.findDay(weekId, dayId)
                                if (selection == null) programModel.showMessage("El día ya no existe en el programa guardado.")
                                else when (workoutApp.repository.load(selection.day.toPlannedSets(), selection.source)) {
                                    com.example.workoutpoc.data.LoadResult.LOADED -> {
                                        message = null
                                        showPrograms = false
                                    }
                                    com.example.workoutpoc.data.LoadResult.SESSION_IN_PROGRESS -> programModel.showMessage(
                                        "Hay una sesión en curso. Termínala o ciérrala antes de cargar otro día; no se ha reemplazado.")
                                }
                            }
                        }
                    }, { catalogModel.refresh(); showCatalog = true }, { proposalModel.refresh(); showProposals = true },
                    annotations, programModel::setDeload, { bodyModel.refresh(); showBody = true },
                    { adherenceModel.refresh(); showAdherence = true })
                return@setContent
            }
            val state by model.state.collectAsStateWithLifecycle()
            val ready by model.ready.collectAsStateWithLifecycle()
            val error by model.error.collectAsStateWithLifecycle()
            val archived by model.archived.collectAsStateWithLifecycle()
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
                }, model::retry, { clockSnapshot(this) }, ::dispatch, { showPrograms = true }, archived.sessions.size, { edit ->
                    lifecycleScope.launch {
                        try {
                            if (!workoutApp.repository.edit(edit.sessionId, edit.revision, edit.index, edit.status, edit.values, edit.notes))
                                message = "La sesión cambió mientras editabas. Vuelve a abrir la serie; no se guardó nada."
                        } catch (e: WorkoutEditException) { message = e.message }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { workoutApp.safely { throw e } }
                    }
                }, { add ->
                    lifecycleScope.launch {
                        try {
                            if (!workoutApp.repository.addCardio(add.sessionId, add.revision, add.entry))
                                message = "La sesión cambió mientras añadías cardio. Vuelve a intentarlo; no se guardó nada."
                        } catch (e: WorkoutEditException) { message = e.message }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { workoutApp.safely { throw e } }
                    }
                }, { entry ->
                    lifecycleScope.launch {
                        workoutApp.safely {
                            val s = workoutApp.repository.state.value
                            workoutApp.repository.removeCardio(s.sessionId, s.revision, entry.id)
                        }
                    }
                })
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
