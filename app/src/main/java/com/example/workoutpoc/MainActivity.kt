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
import com.example.workoutpoc.navigation.*
import com.example.workoutpoc.ui.LocalBackLabel
import com.example.workoutpoc.ui.LocalTabRoot
import com.example.workoutpoc.ui.TabRoot
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: WorkoutViewModel by viewModels()
    private val programModel: ProgramViewModel by viewModels()
    private val migrationModel: MigrationViewModel by viewModels()
    private val catalogModel: com.example.workoutpoc.catalog.CatalogViewModel by viewModels()
    private val proposalModel: com.example.workoutpoc.proposal.ProposalViewModel by viewModels()
    private val bodyModel: com.example.workoutpoc.body.BodyViewModel by viewModels()
    private val adherenceModel: AdherenceViewModel by viewModels()
    private val questionsModel: com.example.workoutpoc.questions.QuestionsViewModel by viewModels()
    private val stagnationModel: com.example.workoutpoc.catalog.StagnationViewModel by viewModels()
    private val historyModel: com.example.workoutpoc.history.HistoryViewModel by viewModels()
    private val planModel: PlanViewModel by viewModels()
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
    private val historyDestination = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        historyModel.writeCsv(uri)
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
        // Bar icons follow the system theme, like the app's colors (see AutoGymTheme).
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        editorVisible = intent.action == "EDIT"
        pendingMode = savedInstanceState?.getString("pendingMode")
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                workoutApp.safely { RestTimer(workoutApp.repository).run() }
            }
        }
        setContent { com.example.workoutpoc.ui.theme.AutoGymTheme { Screens() } }
    }

    /**
     * Bottom tabs plus the screens opened over them. The system Back closes the top screen first, then returns to Hoy,
     * then leaves the app. Each tab and screen keeps its own saved state (scroll, drill-down) while away.
     */
    @Composable
    private fun Screens() {
        val nav = rememberAppNavigator()
        val saved = rememberSaveableStateHolder()
        val state by model.state.collectAsStateWithLifecycle()
        val ready by model.ready.collectAsStateWithLifecycle()
        val error by model.error.collectAsStateWithLifecycle()
        // The notification's Edit opens the quick editor, which lives on the session screen.
        LaunchedEffect(editorVisible) { if (editorVisible && nav.top != Destination.Session) nav.openSession() }
        BackHandler(nav.canGoBack) { nav.back() }
        val top = nav.top
        if (top != null) {
            LaunchedEffect(top) {
                when (top) {
                    Destination.Plans -> planModel.refresh(); Destination.Proposals -> proposalModel.refresh()
                    Destination.Questions -> questionsModel.refresh(); else -> Unit
                }
            }
            CompositionLocalProvider(LocalBackLabel provides nav.backLabel()) {
                saved.SaveableStateProvider("screen-${top.name}") { DestinationScreen(top, nav, state, ready, error) }
            }
            return
        }
        val root = remember(nav.tab, nav.section) {
            TabRoot(nav.tab.label, actions = { SettingsAction { nav.open(Destination.Settings) } },
                header = if (nav.tab == Tab.Progreso) ({ ProgressSections(nav.section) { nav.section = it } }) else null)
        }
        Scaffold(bottomBar = { AppBottomBar(nav.tab, nav::select) }, contentWindowInsets = WindowInsets(0)) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                CompositionLocalProvider(LocalTabRoot provides root) {
                    val key = if (nav.tab == Tab.Progreso) "tab-Progreso-${nav.section.name}" else "tab-${nav.tab.name}"
                    saved.SaveableStateProvider(key) { TabContent(nav, state, ready, error) }
                }
            }
        }
    }

    @Composable
    private fun TabContent(nav: AppNavigator, state: WorkoutState, ready: Boolean, error: String?) {
        when (nav.tab) {
            Tab.Hoy -> com.example.workoutpoc.home.HomeScreen(state, ready, error, model::retry, nav::openSession) { nav.select(Tab.Plan) }
            Tab.Plan -> {
                val library by programModel.library.collectAsStateWithLifecycle()
                val ui by programModel.ui.collectAsStateWithLifecycle()
                val annotations by programModel.annotations.collectAsStateWithLifecycle()
                ProgramScreen(library, ui, nav::back,
                    { programFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    programModel::previewText, programModel::save, programModel::cancelPreview, programModel::restore,
                    { program, weekId, dayId -> trainDay(program, weekId, dayId, nav) },
                    annotations, programModel::setDeload) { nav.open(Destination.Plans) }
            }
            Tab.Progreso -> {
                LaunchedEffect(nav.section) {
                    when (nav.section) {
                        ProgressSection.Ejercicios -> catalogModel.refresh(); ProgressSection.Sesiones -> historyModel.refresh()
                        ProgressSection.Adherencia -> adherenceModel.refresh(); ProgressSection.Cuerpo -> bodyModel.refresh()
                        ProgressSection.Estancamiento -> stagnationModel.refresh()
                    }
                }
                when (nav.section) {
                    ProgressSection.Ejercicios -> {
                        val catalog by catalogModel.catalog.collectAsStateWithLifecycle()
                        val catalogUi by catalogModel.ui.collectAsStateWithLifecycle()
                        com.example.workoutpoc.catalog.CatalogScreen(catalog, catalogUi, nav::back,
                            catalogModel::refresh, catalogModel::save, catalogModel.annotations.collectAsStateWithLifecycle().value)
                    }
                    ProgressSection.Sesiones -> {
                        val historyUi by historyModel.ui.collectAsStateWithLifecycle()
                        com.example.workoutpoc.history.HistoryScreen(historyUi, nav::back, historyModel::refresh,
                            { historyModel.prepareCsv { name -> historyDestination.launch(name) } })
                    }
                    ProgressSection.Adherencia -> {
                        val adherenceUi by adherenceModel.ui.collectAsStateWithLifecycle()
                        AdherenceScreen(adherenceUi, nav::back, adherenceModel::refresh, adherenceModel::setNotPerformed, adherenceModel::saveGoals)
                    }
                    ProgressSection.Cuerpo -> {
                        val log by bodyModel.log.collectAsStateWithLifecycle()
                        val bodyUi by bodyModel.ui.collectAsStateWithLifecycle()
                        com.example.workoutpoc.body.BodyScreen(log, bodyUi, nav::back, bodyModel::refresh, bodyModel::add, bodyModel::remove)
                    }
                    ProgressSection.Estancamiento -> {
                        val stagnationUi by stagnationModel.ui.collectAsStateWithLifecycle()
                        com.example.workoutpoc.catalog.StagnationScreen(stagnationUi, nav::back, stagnationModel::refresh, stagnationModel::setSettings)
                    }
                }
            }
            Tab.Agente -> com.example.workoutpoc.agent.AgentScreen({ nav.open(Destination.Plans) }, { nav.open(Destination.Questions) },
                { nav.open(Destination.Proposals) }, { nav.open(Destination.Exchange) })
        }
    }

    @Composable
    private fun DestinationScreen(top: Destination, nav: AppNavigator, state: WorkoutState, ready: Boolean, error: String?) {
        when (top) {
            Destination.Session -> SessionScreen(nav, state, ready, error)
            Destination.Settings -> com.example.workoutpoc.settings.SettingsScreen(nav::back, notificationEnabled, overlayGranted,
                ::openNotificationSettings, ::openOverlaySettings, { nav.open(Destination.Migration) }, { nav.open(Destination.Exchange) },
                // The demo is the session only while no planned session is loaded.
                demoAvailable = state.source == null, onDemo = nav::openSession)
            Destination.Plans -> {
                val planUi by planModel.ui.collectAsStateWithLifecycle()
                PlanScreen(planUi, nav::back, planModel::refresh, planModel::select, planModel::activate)
            }
            Destination.Questions -> {
                val questionsUi by questionsModel.ui.collectAsStateWithLifecycle()
                com.example.workoutpoc.questions.QuestionsScreen(questionsUi, nav::back, questionsModel::refresh,
                    questionsModel::answer, questionsModel::reopen)
            }
            Destination.Proposals -> {
                val proposalState by proposalModel.state.collectAsStateWithLifecycle()
                val proposalUi by proposalModel.ui.collectAsStateWithLifecycle()
                com.example.workoutpoc.proposal.ProposalScreen(proposalState, proposalUi, { nav.back(); programModel.restore() },
                    proposalModel::refresh, proposalModel::select, proposalModel::approve, proposalModel::reject, proposalModel::saveRules,
                    proposalModel::addExpectation, proposalModel::setManualOutcome)
            }
            Destination.Migration -> {
                val archive by migrationModel.archive.collectAsStateWithLifecycle()
                val migrationUi by migrationModel.ui.collectAsStateWithLifecycle()
                MigrationScreen(archive, migrationUi, nav::back,
                    // Providers disagree on Markdown MIME types (including null). Validate bytes, not the picker label.
                    { migrationFile.launch(arrayOf("*/*")) },
                    migrationModel::previewText, migrationModel::save, migrationModel::cancel,
                    migrationModel::select, migrationModel::restore, migrationModel::acknowledge, migrationModel::convert)
            }
            Destination.Exchange -> {
                val archive by exchangeModel.archive.collectAsStateWithLifecycle()
                val exchangeUi by exchangeModel.ui.collectAsStateWithLifecycle()
                ExchangeScreen(archive, exchangeUi, nav::back,
                    { exchangeFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    exchangeModel::previewText, exchangeModel::save, exchangeModel::cancel,
                    exchangeModel::restore, exchangeModel::select, exchangeModel::generate,
                    { include -> if (exchangeModel.stageExport(include)) exchangeDestination.launch("auto-gym-interchange-v1.json") },
                    exchangeModel::copy, exchangeModel.syncState.collectAsStateWithLifecycle().value,
                    exchangeModel::pair, exchangeModel::revoke, exchangeModel::copyKey)
            }
        }
    }

    /** Loads a saved program day as the next session and opens it; a session in progress is never replaced. */
    private fun trainDay(program: TrainingProgram, weekId: String, dayId: String, nav: AppNavigator) {
        lifecycleScope.launch {
            workoutApp.safely {
                val selection = program.findDay(weekId, dayId)
                if (selection == null) programModel.showMessage("El día ya no existe en el programa guardado.")
                else when (workoutApp.repository.load(selection.day.toPlannedSets(), selection.source)) {
                    com.example.workoutpoc.data.LoadResult.LOADED -> { message = null; nav.openSession() }
                    com.example.workoutpoc.data.LoadResult.SESSION_IN_PROGRESS -> programModel.showMessage(
                        "Hay una sesión en curso. Termínala o ciérrala antes de cargar otro día; no se ha reemplazado.")
                }
            }
        }
    }

    @Composable
    private fun SessionScreen(nav: AppNavigator, state: WorkoutState, ready: Boolean, error: String?) {
        val archived by model.archived.collectAsStateWithLifecycle()
        WorkoutScreen(state, ready, error, notificationEnabled, overlayGranted, message, editorVisible,
                { editorVisible = false }, { requestMode(WorkoutOverlayService.HIDE) }, {
                    if (Settings.canDrawOverlays(this)) requestMode(WorkoutOverlayService.SHOW)
                    else {
                        message = "Autoriza Mostrar sobre otras aplicaciones para abrir el panel flotante."
                        try { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                        catch (e: RuntimeException) { Log.w("WorkoutActivity", "Ajustes de overlay no disponibles", e); message = "Este dispositivo no permite abrir los ajustes del panel flotante." }
                    }
                }, ::openNotificationSettings, model::retry, { clockSnapshot(this) }, ::dispatch, nav::back, archived.sessions.size, { edit ->
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
    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    /** From Ajustes only: unlike the session's bubble button, granting here does not start a workout. */
    private fun openOverlaySettings() {
        try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (e: RuntimeException) { Log.w("WorkoutActivity", "Ajustes de overlay no disponibles", e) }
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
