package com.example.workoutpoc.body

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.workoutpoc.workoutApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class BodyUiState(val ready: Boolean = false, val busy: Boolean = false, val message: String? = null)

class BodyViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.workoutApp.measurements
    val log = repo.log
    private val mutableUi = MutableStateFlow(BodyUiState())
    val ui = mutableUi.asStateFlow()
    init { refresh() }
    fun refresh() = op { repo.restore(); mutableUi.value = mutableUi.value.copy(ready = true) }
    fun add(m: BodyMeasurement) = op { repo.add(m); mutableUi.value = mutableUi.value.copy(message = "Medición guardada en este dispositivo.") }
    fun remove(id: String) = op { repo.remove(id); mutableUi.value = mutableUi.value.copy(message = "Medición eliminada.") }
    private fun op(block: suspend () -> Unit) {
        if (ui.value.busy) return
        mutableUi.value = mutableUi.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { mutableUi.value = mutableUi.value.copy(message = e.message) }
            catch (_: Exception) { mutableUi.value = mutableUi.value.copy(message = "No se pudieron leer o guardar las mediciones. Tus datos se conservan. Reintenta.") }
            finally { mutableUi.value = mutableUi.value.copy(busy = false) }
        }
    }
}

private fun Double.fmt() = if (this == Math.floor(this)) toLong().toString() else "%.1f".format(this)
private val dayFormat get() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply { isLenient = false }

@Composable
fun BodyScreen(log: MeasurementLog, ui: BodyUiState, onBack: () -> Unit, onRetry: () -> Unit,
               onAdd: (BodyMeasurement) -> Unit, onRemove: (String) -> Unit) {
    val groups = remember(log) { trends(log) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                item { TextButton(onClick = onBack, enabled = !ui.busy) { Text("Volver a Programas") } }
                item { Text("Peso y composición corporal", style = MaterialTheme.typography.headlineMedium) }
                if (ui.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                ui.message?.let { item { Text(it) } }
                if (!ui.ready) item { Button(onClick = onRetry, enabled = !ui.busy) { Text("Reintentar mediciones") } }
                item { MeasurementForm(enabled = ui.ready && !ui.busy, onAdd) }
                item { Text("Tendencias", style = MaterialTheme.typography.titleLarge) }
                if (groups.isEmpty()) item { Text("Aún no hay mediciones.") }
                groups.forEach { g ->
                    item { Text(g.label ?: g.metric.label, style = MaterialTheme.typography.titleMedium) }
                    if (g.mixedMethods || g.mixedUnits) item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(buildString {
                                if (g.mixedMethods) append("Hay mediciones con métodos distintos (${g.methods.joinToString { it.label }}). No son directamente comparables: cada método se muestra como una serie separada. ")
                                if (g.mixedUnits) append("También hay unidades distintas (${g.units.joinToString { it.label }}); no se convierten.")
                            }.trim(), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    g.series.forEach { s ->
                        item { SeriesCard(s, onRemove, !ui.busy) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesCard(s: MeasurementSeries, onRemove: (String) -> Unit, enabled: Boolean) {
    val first = s.points.first(); val last = s.points.last()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${s.method.label} · ${s.unit.label} · ${s.points.size} mediciones", style = MaterialTheme.typography.titleSmall)
            Text("Última: ${last.value.fmt()} ${s.unit.label} (${dayFormat.format(Date(last.measuredAtMillis))})" +
                if (s.points.size > 1) " · cambio desde la primera: ${(last.value - first.value).let { (if (it > 0) "+" else "") + it.fmt() }} ${s.unit.label}" else "")
            if (s.points.size > 1) TrendChart(s)
            s.points.asReversed().forEach { m ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("${dayFormat.format(Date(m.measuredAtMillis))} · ${m.value.fmt()} ${m.unit.label}" +
                        listOfNotNull(m.fasted?.let { if (it) "en ayunas" else "sin ayuno" }, m.timeOfDay, m.device, m.notes).joinToString("") { " · $it" },
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onRemove(m.id) }, enabled = enabled) { Text("Eliminar") }
                }
            }
        }
    }
}

@Composable
private fun TrendChart(s: MeasurementSeries) {
    val values = s.points.map { it.value }
    val min = values.min(); val max = values.max()
    val line = MaterialTheme.colorScheme.primary; val axis = MaterialTheme.colorScheme.outline
    val start = s.points.first().measuredAtMillis; val span = (s.points.last().measuredAtMillis - start).coerceAtLeast(1)
    val description = "${s.method.label}: " + s.points.joinToString("; ") { "${dayFormat.format(Date(it.measuredAtMillis))} ${it.value.fmt()} ${s.unit.label}" }
    // X is proportional to time, so irregular measurement intervals are not shown as evenly spaced.
    Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description }) {
        val pad = 10.dp.toPx(); val w = size.width - 2 * pad; val h = size.height - 2 * pad
        fun x(t: Long) = pad + (t - start).toFloat() / span * w
        fun y(v: Double) = pad + h - (if (max == min) 0.5f else ((v - min) / (max - min)).toFloat()) * h
        drawLine(axis, Offset(pad, pad + h), Offset(pad + w, pad + h), 1.dp.toPx())
        s.points.zipWithNext { a, b -> drawLine(line, Offset(x(a.measuredAtMillis), y(a.value)), Offset(x(b.measuredAtMillis), y(b.value)), 2.dp.toPx()) }
        s.points.forEach { drawCircle(line, 4.dp.toPx(), Offset(x(it.measuredAtMillis), y(it.value))) }
    }
    Text("mín. ${min.fmt()} · máx. ${max.fmt()} ${s.unit.label}", style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeasurementForm(enabled: Boolean, onAdd: (BodyMeasurement) -> Unit) {
    // Saveable: lazy-list items drop plain remembered state when scrolled off screen, which would wipe the form.
    var metric by rememberSaveable { mutableStateOf(BodyMetric.BODY_MASS) }
    var unit by rememberSaveable(metric) { mutableStateOf(metric.units.first()) }
    var method by rememberSaveable { mutableStateOf<MeasureMethod?>(null) }
    var value by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(dayFormat.format(Date())) }
    var label by rememberSaveable { mutableStateOf("") }
    var fasted by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    val parsedValue = value.trim().replace(',', '.').toDoubleOrNull()
    val parsedDate = runCatching { dayFormat.parse(date.trim())?.time }.getOrNull()
    val valid = parsedValue != null && parsedDate != null && method != null && (metric != BodyMetric.OTHER || label.isNotBlank())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Nueva medición", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { BodyMetric.entries.forEach { m -> FilterChip(metric == m, { metric = m }, { Text(m.label) }) } }
        if (metric == BodyMetric.OTHER) OutlinedTextField(label, { if (it.length <= 100) label = it }, label = { Text("Nombre de la medida") }, singleLine = true)
        OutlinedTextField(value, { value = it }, label = { Text("Valor") }, singleLine = true, isError = value.isNotBlank() && parsedValue == null)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { metric.units.forEach { u -> FilterChip(unit == u, { unit = u }, { Text(u.label) }) } }
        Text("Método (obligatorio: no se compara entre métodos)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { MeasureMethod.entries.forEach { m -> FilterChip(method == m, { method = m }, { Text(m.label) }) } }
        OutlinedTextField(date, { date = it }, label = { Text("Fecha (AAAA-MM-DD)") }, singleLine = true, isError = parsedDate == null)
        Text("Ayuno (opcional)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(null to "sin indicar", true to "en ayunas", false to "sin ayuno").forEach { (v, l) -> FilterChip(fasted == v, { fasted = v }, { Text(l) }) }
        }
        OutlinedTextField(notes, { if (it.length <= 1000) notes = it }, label = { Text("Notas (opcional)") })
        Button(enabled = enabled && valid, onClick = {
            onAdd(BodyMeasurement(UUID.randomUUID().toString(), parsedDate!!, metric, parsedValue!!, unit, method!!,
                label.trim().ifBlank { null }?.takeIf { metric == BodyMetric.OTHER }, fasted, null, null, notes.trim().ifBlank { null }))
            value = ""; notes = ""
        }) { Text("Guardar medición") }
    }
}
