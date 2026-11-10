package com.example.workoutpoc.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.workoutpoc.exchange.ExchangeCodec
import com.example.workoutpoc.exchange.ExchangeType
import com.example.workoutpoc.exchange.localExchange
import com.example.workoutpoc.exchange.toSessionResult
import com.example.workoutpoc.home.HomePlanner
import com.example.workoutpoc.planning.BriefStatus
import com.example.workoutpoc.planning.NextWeekBrief
import com.example.workoutpoc.planning.PlanOutline
import com.example.workoutpoc.planning.PlanOutlines
import com.example.workoutpoc.workoutApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate

data class GenerateUiState(
    val ready: Boolean = false, val busy: Boolean = false, val message: String? = null,
    val key: ApiKeyStatus = ApiKeyStatus(),
    /** The active plan and the week the app says comes next; null without an active plan. */
    val outline: PlanOutline? = null, val brief: NextWeekBrief? = null,
    /** Saved proposals of this program still waiting for a decision. */
    val pending: Int = 0,
    /** A call to the model is in flight. */
    val generating: Boolean = false,
    /** Questions the model asked; the generation continues when they are answered. */
    val questions: List<AgentQuestion>? = null,
    /** Week number of the proposal this screen just saved. */
    val savedWeek: Int? = null,
    val savedPlan: String? = null,
    /** All days of the final week have records or an explicit not-performed mark. */
    val cycleReady: Boolean = false
) {
    val newCycle get() = cycleReady || brief?.status == BriefStatus.CYCLE_COMPLETE
    val canGenerate get() = ready && !busy && key.present && questions == null &&
        (brief?.status == BriefStatus.READY || (newCycle && cycleReady))
}

/**
 * Sends local context to Claude for the next week or the next 12-week cycle. Results are archived for explicit
 * review: weeks in Propuestas de semana and cycles in Planes de periodización. Never approves or activates.
 */
class GenerateViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.workoutApp
    private val mutableUi = MutableStateFlow(GenerateUiState())
    val ui = mutableUi.asStateFlow()
    /** Replaced in tests; production talks to the Claude API. */
    var chats: AgentChatFactory = ClaudeChatFactory()
    private var job: Job? = null
    private var chat: AgentChat? = null
    private var generation: WeekGeneration? = null
    private var planGeneration: PlanGeneration? = null
    private var week: Int? = null

    init { refresh() }

    fun refresh() = operation { load() }

    fun saveKey(raw: String) = operation {
        app.apiKey.save(raw)
        mutableUi.value = mutableUi.value.copy(key = app.apiKey.status.value, message = "Llave guardada en este dispositivo, cifrada.")
    }

    fun clearKey() = operation {
        app.apiKey.clear()
        mutableUi.value = mutableUi.value.copy(key = app.apiKey.status.value, message = "Llave eliminada de este dispositivo.")
    }

    fun generate(note: String) = operation(generating = true) {
        discard()
        mutableUi.value = mutableUi.value.copy(savedWeek = null, savedPlan = null, questions = null)
        val key = app.apiKey.key() ?: throw AgentException("Guarda primero tu llave API de Claude.")
        // Building and encoding the whole history is kept off the main thread.
        val (data, contextJson) = withContext(Dispatchers.Default) { app.localExchange(ExchangeType.TrainingContext).let { it to compact.encodeToString(it) } }
        val brief = data.context!!.nextWeeks.firstOrNull() ?: throw AgentException("No hay plan activo. Activa uno en Planes de periodización.")
        app.weekAnnotations.restore()
        val states = (app.repository.archived.value.sessions + app.repository.state.value).distinctBy { it.sessionId }
        val newCycle = HomePlanner.summary(data.context!!.programs, data.context!!.outlines, states, app.weekAnnotations.annotations.value).cycleComplete
        if (!newCycle && brief.status == BriefStatus.CYCLE_COMPLETE)
            throw AgentException("Termina de registrar los días de la última semana antes de generar el nuevo plan.")
        if (!newCycle && brief.status != BriefStatus.READY) throw AgentException(brief.message)
        app.proposals.restore(); app.exchanges.restore()
        val taken = (app.exchanges.archive.value.packages.mapNotNull { it.proposal }.map { it.id to it.version } +
            app.proposals.state.value.decisions.map { it.proposalId to it.version }).toSet()
        val prompt = withContext(Dispatchers.IO) { app.assets.open(if (newCycle) PLAN_PROMPT_ASSET else PROMPT_ASSET).use { it.readBytes().decodeToString() } }
        val opened = chats.open(key, if (newCycle) PlanPrompts.system(prompt) else WeekPrompts.system(prompt))
        chat = opened; week = brief.weekNumber
        val userNote = note.trim().take(MAX_NOTE).ifBlank { null }
        if (newCycle) {
            val archived = app.exchanges.archive.value.packages.mapNotNull { it.outline }
            val started = PlanGeneration(opened, data.context!!, archived, cycleComplete = newCycle)
            planGeneration = started
            show(started.start(contextJson, LocalDate.now().toString(), userNote))
        } else {
            val started = WeekGeneration(opened, brief, taken, { p -> app.proposals.check(p).blocking.map { it.message } })
            generation = started
            show(started.start(contextJson, LocalDate.now().toString(), userNote))
        }
    }

    /** [answers] are in the order of the questions shown. */
    fun answer(answers: List<String>) = operation(generating = true) {
        val questions = mutableUi.value.questions ?: return@operation
        if (answers.size != questions.size || answers.any { it.isBlank() }) throw AgentException("Responde todas las preguntas.")
        val paired = questions.zip(answers.map { it.trim().take(MAX_NOTE) })
        show(planGeneration?.answer(paired) ?: generation?.answer(paired)
            ?: throw AgentException("La generación ya no está activa. Vuelve a generar."))
    }

    /**
     * Stops a call in flight, or drops the questions waiting for answers. The screen stays busy until the interrupted
     * call has really ended (see [operation]), so a new generation cannot overlap it.
     */
    fun cancel() {
        val running = job?.takeIf { it.isActive && ui.value.generating }
        if (running != null) running.cancel()
        else if (!ui.value.busy) {
            discard()
            mutableUi.value = mutableUi.value.copy(questions = null, message = CANCELLED)
        }
    }

    private suspend fun show(step: GenerationStep) {
        when (step) {
            is GenerationStep.Questions -> mutableUi.value = mutableUi.value.copy(questions = step.questions,
                message = "Claude necesita tus respuestas antes de proponer ${if (planGeneration != null) "el nuevo plan" else "la semana"}.")
            is GenerationStep.Proposal -> {
                app.exchanges.save(step.pkg)
                // Shown as saved at once: a cancellation or a failed reload after this point must not hide it.
                val plan = step.pkg.outline
                mutableUi.value = mutableUi.value.copy(questions = null, savedWeek = week, savedPlan = plan?.name,
                    message = if (plan != null) "Nuevo plan de 12 semanas guardado. Revísalo y actívalo en Planes de periodización."
                        else "Propuesta de la semana $week guardada. Queda pendiente de tu aprobación.")
                discard(); load()
            }
        }
    }

    private suspend fun load() {
        // An unreadable key is reported, and the rest of the screen still loads so it can be replaced.
        val keyProblem = try { app.apiKey.restore(); null } catch (e: AgentException) { e.message }
        // Only the plan and its brief are needed to draw the screen; the full context is built when generating.
        val (outline, brief, cycleReady) = withContext(Dispatchers.Default) {
            app.outlines.restore(); app.programs.restore(); app.repository.restore(); app.weekAnnotations.restore()
            val active = app.outlines.outlines.value.active.firstOrNull()
            val states = (app.repository.archived.value.sessions + app.repository.state.value).distinctBy { it.sessionId }
            val sessions = states.mapNotNull { it.toSessionResult() }
            val summary = HomePlanner.summary(app.programs.library.value.programs, app.outlines.outlines.value.active, states, app.weekAnnotations.annotations.value)
            Triple(active, active?.let { o -> PlanOutlines.brief(o, app.programs.library.value.programs.find { it.id == o.programId }, sessions) }, summary.cycleComplete)
        }
        app.proposals.restore(); app.exchanges.restore()
        val decided = app.proposals.state.value.decisions.map { it.importId }.toSet()
        mutableUi.value = mutableUi.value.copy(ready = true, key = app.apiKey.status.value, outline = outline,
            brief = brief, cycleReady = cycleReady,
            pending = app.exchanges.archive.value.packages.count { it.proposal?.programId == outline?.programId && outline != null && it.importId !in decided },
            message = keyProblem ?: mutableUi.value.message)
    }

    private fun discard() {
        try { chat?.close() } catch (_: Exception) { }
        chat = null; generation = null; planGeneration = null; week = null
    }

    override fun onCleared() { discard() }

    private fun operation(generating: Boolean = false, block: suspend () -> Unit) {
        if (ui.value.busy) return
        mutableUi.value = mutableUi.value.copy(busy = true, generating = generating, message = null)
        job = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) {
                if (generating && mutableUi.value.savedWeek == null && mutableUi.value.savedPlan == null) mutableUi.value = mutableUi.value.copy(questions = null, message = CANCELLED)
                throw e
            }
            // AgentException, and validation and proposal errors, are already worded for the user.
            catch (e: AgentException) { mutableUi.value = mutableUi.value.copy(message = e.message) }
            catch (e: IllegalArgumentException) { mutableUi.value = mutableUi.value.copy(message = e.message) }
            catch (_: Exception) { mutableUi.value = mutableUi.value.copy(message = "No se pudo completar la generación. Tus datos se conservan. Reintenta.") }
            finally {
                // Without questions waiting there is no conversation to continue: close the client.
                if (mutableUi.value.questions == null) discard()
                mutableUi.value = mutableUi.value.copy(busy = false, generating = false)
            }
        }
    }

    private companion object {
        const val PROMPT_ASSET = "prompts/week-proposal.txt"
        const val PLAN_PROMPT_ASSET = "prompts/next-cycle.txt"
        const val MAX_NOTE = 2000
        const val CANCELLED = "Generación cancelada."
        /** The same content as an export, without the indentation: about half the tokens. */
        val compact = Json(ExchangeCodec.json) { prettyPrint = false }
    }
}
