package com.example.workoutpoc.catalog

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                item { TextButton(onClick = onBack, enabled = !ui.busy) { Text("Volver a Programas") } }
                item { Text("Señales de estancamiento", style = MaterialTheme.typography.headlineMedium) }
                if (ui.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                ui.message?.let { item { Text(it) } }
                if (!ui.ready) item { Button(onClick = onRetry, enabled = !ui.busy) { Text("Reintentar") } }
                item {
                    Text("Una señal aparece cuando, en las últimas exposiciones comparables (misma unidad y base, sin descargas), ninguna supera la mejor marca anterior en carga máxima o repeticiones a esa carga. Sin datos suficientes no se emite ninguna.",
                        style = MaterialTheme.typography.bodySmall)
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Exposiciones en la ventana", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (3..6).forEach { n -> FilterChip(ui.settings.exposures == n, { onSettings(ui.settings.copy(exposures = n)) }, { Text("$n") }, enabled = !ui.busy) }
                        }
                        Text("Duración mínima de la ventana", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(7, 14, 21, 28).forEach { d -> FilterChip(ui.settings.minSpanDays == d, { onSettings(ui.settings.copy(minSpanDays = d)) }, { Text("$d días") }, enabled = !ui.busy) }
                        }
                    }
                }
                item { Text("Alertas (${ui.report.alerts.size})", style = MaterialTheme.typography.titleLarge) }
                if (ui.report.alerts.isEmpty()) item { Text("Sin señales de estancamiento con los datos y umbrales actuales.") }
                items(ui.report.alerts, key = { "${it.exerciseId}-${it.unit}-${it.basis}" }) { a ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${a.exerciseName} · ${unitLabel(a.unit)} · ${basisLabel(a.basis)}", style = MaterialTheme.typography.titleMedium)
                            Text("Período: ${format.format(Date(a.fromMillis))} – ${format.format(Date(a.toMillis))}", style = MaterialTheme.typography.titleSmall)
                            Text(a.explanation)
                            Text("Datos usados", style = MaterialTheme.typography.labelLarge)
                            (listOfNotNull(a.reference?.let { "Referencia" to it }) + a.window.map { "Ventana" to it }).forEach { (role, p) ->
                                Text("$role · ${format.format(Date(p.startedAtMillis))} · ${p.label}: ${loadLabel(p.topLoadTenths, a.unit)} × ${p.repsAtTop}" +
                                    " · RIR mín. ${rirLabel(p.minRir)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Limitaciones", style = MaterialTheme.typography.labelLarge)
                            a.limitations.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                if (ui.report.insufficient.isNotEmpty()) {
                    item { Text("Sin datos suficientes (${ui.report.insufficient.size})", style = MaterialTheme.typography.titleMedium) }
                    items(ui.report.insufficient, key = { "ins-${it.exerciseId}-${it.unit}-${it.basis}" }) { i ->
                        Text("${i.exerciseName} · ${unitLabel(i.unit)} · ${basisLabel(i.basis)}: ${i.reason}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
