package com.example.workoutpoc.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.workoutpoc.exchange.ExchangeType
import com.example.workoutpoc.planning.GoalProgress
import com.example.workoutpoc.planning.goalProgress
import com.example.workoutpoc.questions.PlanningQuestions
import com.example.workoutpoc.workoutApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Measurements are monthly: Hoy reminds only after this long without one. */
const val MEASUREMENT_REMINDER_DAYS = 30

data class HomeUiState(
    val ready: Boolean = false, val summary: HomeSummary? = null,
    /** Week proposals saved but not yet approved or rejected. */
    val pendingProposals: Int = 0,
    val openQuestions: Int = 0,
    val progress: GoalProgress? = null,
    /** Whole days since the latest body measurement; null when there is none. */
    val daysSinceMeasurement: Int? = null,
    val failed: Boolean = false
)

/** Reads, never writes: everything on Hoy is derived from the other stores. */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.workoutApp
    private val mutableUi = MutableStateFlow(HomeUiState())
    val ui = mutableUi.asStateFlow()
    private var job: Job? = null
    init { refresh() }

    /** A newer request replaces one still loading, so Hoy always ends on the latest data. */
    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                app.programs.restore(); app.outlines.restore(); app.repository.restore(); app.weekAnnotations.restore()
                app.exchanges.restore(); app.proposals.restore(); app.planningAnswers.restore(); app.catalog.restore(); app.measurements.restore()
                val sessions = (app.repository.archived.value.sessions + app.repository.state.value).distinctBy { it.sessionId }
                val annotations = app.weekAnnotations.annotations.value
                val decided = app.proposals.state.value.decisions.map { it.importId }.toSet()
                val pending = app.exchanges.archive.value.packages.count { it.type == ExchangeType.WeekProposal && it.importId !in decided }
                val questions = PlanningQuestions.open(PlanningQuestions.questions(app.catalog.catalog.value), app.planningAnswers.answers.value)
                val now = System.currentTimeMillis()
                val latest = app.measurements.log.value.measurements.maxOfOrNull { it.measuredAtMillis }
                mutableUi.value = HomeUiState(true,
                    HomePlanner.summary(app.programs.library.value.programs, app.outlines.outlines.value.active, sessions, annotations),
                    pending, questions.size, goalProgress(sessions, annotations.goals, now),
                    latest?.let { ((now - it) / 86_400_000L).toInt().coerceAtLeast(0) })
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableUi.value = mutableUi.value.copy(ready = true, failed = true) }
        }
    }
}
