package com.example.workoutpoc.proposal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.workoutpoc.exchange.ExchangePackage
import com.example.workoutpoc.exchange.ExchangeType
import com.example.workoutpoc.workoutApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProposalUiState(
    val ready: Boolean = false, val busy: Boolean = false, val message: String? = null,
    val proposals: List<ExchangePackage> = emptyList(), val selected: String? = null, val evaluation: ProposalEvaluation? = null,
    /** Current evaluation of every expectation, by expectation ID (AG-04). */
    val results: Map<String, EvaluationResult> = emptyMap(),
    /** The user asked to replace the saved week; the evaluation shows what that approval deletes (§2). */
    val replacePreview: Boolean = false,
    /** Saved training comments, so the approval view can anchor them to days, exercises and sets (requirement 2). */
    val comments: List<com.example.workoutpoc.domain.SessionComment> = emptyList()
)

class ProposalViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.workoutApp
    val state = app.proposals.state
    private val mutableUi = MutableStateFlow(ProposalUiState())
    val ui = mutableUi.asStateFlow()
    init { refresh() }

    private suspend fun evaluateAll() {
        app.repository.restore(); app.weekAnnotations.restore(); app.comments.restore()
        val sessions = app.repository.archived.value.sessions + app.repository.state.value
        val now = System.currentTimeMillis()
        mutableUi.value = mutableUi.value.copy(comments = app.comments.log.value.comments,
            results = app.proposals.state.value.expectations.associate {
                it.id to evaluate(it, sessions, app.weekAnnotations.annotations.value, now) })
    }

    fun addExpectation(e: Expectation) = operation {
        app.proposals.addExpectation(e); evaluateAll()
        mutableUi.value = mutableUi.value.copy(message = "Resultado esperado guardado. Se evaluará al cerrar la ventana.")
    }

    fun setManualOutcome(id: String, outcome: Outcome?, note: String?) = operation { app.proposals.setManualOutcome(id, outcome, note); evaluateAll() }

    fun refresh() = operation {
        app.proposals.restore(); app.exchanges.restore(); evaluateAll()
        mutableUi.value = mutableUi.value.copy(ready = true,
            proposals = app.exchanges.archive.value.packages.filter { it.type == ExchangeType.WeekProposal })
        mutableUi.value.selected?.let { mutableUi.value = mutableUi.value.copy(evaluation = app.proposals.evaluate(it, mutableUi.value.replacePreview)) }
    }

    fun select(importId: String?) = operation {
        mutableUi.value = mutableUi.value.copy(selected = importId, replacePreview = false,
            evaluation = importId?.let { app.proposals.evaluate(it) })
    }

    /** Shows the evaluation of replacing the saved week, or returns to the normal one. Nothing is written here. */
    fun previewReplacement(on: Boolean) = operation {
        val id = mutableUi.value.selected ?: return@operation
        mutableUi.value = mutableUi.value.copy(replacePreview = on, evaluation = app.proposals.evaluate(id, replacing = on))
    }

    fun approve() = operation {
        val id = mutableUi.value.selected ?: return@operation
        val replacing = mutableUi.value.replacePreview
        val decision = app.proposals.approve(id, replaceWeek = replacing)
        val message = if (decision.replacedWeek)
            "Semana ${decision.weekId} reemplazada. Se borraron ${decision.deletedRecords} sesión(es) registrada(s) de esa semana."
        else "Propuesta aprobada. La semana ${decision.weekId} se añadió al programa; puedes entrenarla desde Programas."
        mutableUi.value = mutableUi.value.copy(evaluation = app.proposals.evaluate(id), replacePreview = false, message = message)
    }

    fun reject() = operation {
        val id = mutableUi.value.selected ?: return@operation
        app.proposals.reject(id)
        mutableUi.value = mutableUi.value.copy(message = "Propuesta rechazada. El programa no cambió.")
    }

    fun saveRules(rules: RuleSettings) = operation {
        app.proposals.updateRules(rules)
        mutableUi.value.selected?.let { mutableUi.value = mutableUi.value.copy(evaluation = app.proposals.evaluate(it)) }
        mutableUi.value = mutableUi.value.copy(message = "Reglas guardadas. Las propuestas se vuelven a validar con ellas.")
    }

    private fun operation(block: suspend () -> Unit) {
        if (ui.value.busy) return
        mutableUi.value = mutableUi.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { mutableUi.value = mutableUi.value.copy(message = e.message) }
            catch (_: Exception) { mutableUi.value = mutableUi.value.copy(message = "No se pudo leer o guardar la decisión. Tus datos se conservan. Reintenta.") }
            finally { mutableUi.value = mutableUi.value.copy(busy = false) }
        }
    }
}
