package com.example.workoutpoc.catalog

import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.AppTheme
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular
import androidx.compose.ui.Alignment
import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.workoutpoc.domain.loadLabel
import com.example.workoutpoc.domain.rirLabel
import com.example.workoutpoc.workoutApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StagnationUiState(val ready: Boolean = false, val busy: Boolean = false, val message: String? = null,
                             val settings: StagnationSettings = StagnationSettings(), val report: StagnationReport = StagnationReport(emptyList(), emptyList()))

class StagnationViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.workoutApp
    private val mutableUi = MutableStateFlow(StagnationUiState())
    val ui = mutableUi.asStateFlow()
    init { refresh() }

    fun refresh() = op { load(mutableUi.value.settings) }
    fun setSettings(s: StagnationSettings) = op { load(s) }

    private suspend fun load(settings: StagnationSettings) {
        app.catalog.restore(); app.programs.restore(); app.repository.restore(); app.weekAnnotations.restore()
        val sessions = app.repository.archived.value.sessions + app.repository.state.value
        app.catalog.sync(exerciseReferences(app.programs.library.value.programs, sessions))
        val names = app.catalog.catalog.value.exercises.associate { it.id to it.name }
        mutableUi.value = mutableUi.value.copy(ready = true, settings = settings,
            report = stagnation(names, sessions, app.weekAnnotations.annotations.value, settings))
    }
    private fun op(block: suspend () -> Unit) {
        if (ui.value.busy) return
        mutableUi.value = mutableUi.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableUi.value = mutableUi.value.copy(message = "No se pudo calcular el análisis. Tus datos se conservan. Reintenta.") }
            finally { mutableUi.value = mutableUi.value.copy(busy = false) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StagnationScreen(ui: StagnationUiState, onBack: () -> Unit, onRetry: () -> Unit, onSettings: (StagnationSettings) -> Unit) {
    val format = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    AppScreen("Señales de estancamiento", "Volver a Programas", onBack, backEnabled = !ui.busy, busy = ui.busy, message = ui.message) {
        if (!ui.ready && !ui.busy) item { RetryAction("Reintentar", onRetry, enabled = !ui.busy) }
        item {
            SupportingText("Una señal aparece cuando, en las últimas exposiciones comparables (misma unidad y base, sin descargas), ninguna supera la mejor marca anterior en carga máxima o repeticiones a esa carga. Sin datos suficientes no se emite ninguna.")
        }
        item {
            ContentCard {
                Text("Umbrales", style = MaterialTheme.typography.titleMedium)
                SegmentedChoice("Exposiciones en la ventana", (3..6).toList(), ui.settings.exposures, { "$it" },
                    { onSettings(ui.settings.copy(exposures = it)) }, enabled = !ui.busy)
                SegmentedChoice("Duración mínima de la ventana", listOf(7, 14, 21, 28), ui.settings.minSpanDays, { "$it días" },
                    { onSettings(ui.settings.copy(minSpanDays = it)) }, enabled = !ui.busy)
            }
        }
        item { SectionHeader("Alertas (${ui.report.alerts.size})") }
        if (ui.report.alerts.isEmpty()) item { EmptyState("Sin señales de estancamiento con los datos y umbrales actuales.", icon = AppIcons.CheckCircle) }
        items(ui.report.alerts, key = { "${it.exerciseId}-${it.unit}-${it.basis}" }) { a ->
            val x = AppTheme.extended
            ContentCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Icon(AppIcons.Warning, contentDescription = null, tint = x.warning, modifier = Modifier.size(20.dp))
                    Text("${a.exerciseName} · ${unitLabel(a.unit)} · ${basisLabel(a.basis)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                Text("Período: ${format.format(Date(a.fromMillis))} – ${format.format(Date(a.toMillis))}", style = MaterialTheme.typography.titleSmall.tabular())
                Text(a.explanation, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider(Modifier.padding(vertical = Spacing.xs), color = MaterialTheme.colorScheme.outlineVariant)
                Text("Datos usados", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                (listOfNotNull(a.reference?.let { "Referencia" to it }) + a.window.map { "Ventana" to it }).forEach { (role, p) ->
                    Text("$role · ${format.format(Date(p.startedAtMillis))} · ${p.label}: ${loadLabel(p.topLoadTenths, a.unit)} × ${p.repsAtTop}" +
                        " · RIR mín. ${rirLabel(p.minRir)}", style = MaterialTheme.typography.bodySmall.tabular())
                }
                Text("Limitaciones", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                a.limitations.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (ui.report.insufficient.isNotEmpty()) {
            item { SectionHeader("Sin datos suficientes (${ui.report.insufficient.size})") }
            items(ui.report.insufficient, key = { "ins-${it.exerciseId}-${it.unit}-${it.basis}" }) { i ->
                Text("${i.exerciseName} · ${unitLabel(i.unit)} · ${basisLabel(i.basis)}: ${i.reason}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

}
