package com.example.workoutpoc.body

import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.AppTheme
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular
import androidx.compose.ui.Alignment
import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
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
    AppScreen("Peso y composición corporal", "Volver a Programas", onBack, backEnabled = !ui.busy, busy = ui.busy, message = ui.message) {
        if (!ui.ready && !ui.busy) item { RetryAction("Reintentar mediciones", onRetry, enabled = !ui.busy) }
        item { ContentCard { MeasurementForm(enabled = ui.ready && !ui.busy, onAdd) } }
        item { SectionHeader("Tendencias") }
        if (groups.isEmpty()) item { EmptyState("Aún no hay mediciones.", icon = AppIcons.Scale) }
        groups.forEach { g ->
            item { Text(g.label ?: g.metric.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.xs)) }
            if (g.mixedMethods || g.mixedUnits) item {
                Banner(buildString {
                    if (g.mixedMethods) append("Hay mediciones con métodos distintos (${g.methods.joinToString { it.label }}). No son directamente comparables: cada método se muestra como una serie separada. ")
                    if (g.mixedUnits) append("También hay unidades distintas (${g.units.joinToString { it.label }}); no se convierten.")
                }.trim(), tone = Tone.Warning)
            }
            g.series.forEach { s ->
                item { SeriesCard(s, onRemove, !ui.busy) }
            }
        }
    }

}

@Composable
private fun SeriesCard(s: MeasurementSeries, onRemove: (String) -> Unit, enabled: Boolean) {
    val first = s.points.first(); val last = s.points.last()
    var removing by remember { mutableStateOf<BodyMeasurement?>(null) }
    ContentCard {
        Text("${s.method.label} · ${s.unit.label} · ${s.points.size} mediciones", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("${last.value.fmt()} ${s.unit.label}", style = MaterialTheme.typography.headlineMedium.tabular())
            if (s.points.size > 1) {
                val delta = last.value - first.value
                Text((if (delta > 0) "+" else "") + delta.fmt() + " ${s.unit.label}", style = MaterialTheme.typography.titleSmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        Text("Última: ${last.value.fmt()} ${s.unit.label} (${dayFormat.format(Date(last.measuredAtMillis))})" +
            if (s.points.size > 1) " · cambio desde la primera: ${(last.value - first.value).let { (if (it > 0) "+" else "") + it.fmt() }} ${s.unit.label}" else "",
            style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.points.size > 1) TrendChart(s)
        HorizontalDivider(Modifier.padding(vertical = Spacing.xs), color = MaterialTheme.colorScheme.outlineVariant)
        s.points.asReversed().forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${dayFormat.format(Date(m.measuredAtMillis))} · ${m.value.fmt()} ${m.unit.label}" +
                    listOfNotNull(m.fasted?.let { if (it) "en ayunas" else "sin ayuno" }, m.timeOfDay, m.device, m.notes).joinToString("") { " · $it" },
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.tabular())
                IconButton(onClick = { removing = m }, enabled = enabled) {
                    Icon(AppIcons.Delete, contentDescription = "Eliminar medición del ${dayFormat.format(Date(m.measuredAtMillis))}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    removing?.let { m ->
        AlertDialog(onDismissRequest = { removing = null }, icon = { Icon(AppIcons.Delete, contentDescription = null) },
            title = { Text("¿Eliminar medición?") },
            text = { Text("${dayFormat.format(Date(m.measuredAtMillis))} · ${m.value.fmt()} ${m.unit.label} (${s.method.label}). No se puede deshacer.") },
            confirmButton = { TextButton(onClick = { removing = null; onRemove(m.id) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancelar") } })
    }
}

@Composable
private fun TrendChart(s: MeasurementSeries) {
    val values = s.points.map { it.value }
    val min = values.min(); val max = values.max()
    val line = MaterialTheme.colorScheme.primary; val axis = MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.outlineVariant; val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val start = s.points.first().measuredAtMillis; val span = (s.points.last().measuredAtMillis - start).coerceAtLeast(1)
    val description = "${s.method.label}: " + s.points.joinToString("; ") { "${dayFormat.format(Date(it.measuredAtMillis))} ${it.value.fmt()} ${s.unit.label}" }
    // X is proportional to time, so irregular measurement intervals are not shown as evenly spaced.
    Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description }) {
        val pad = 10.dp.toPx(); val w = size.width - 2 * pad; val h = size.height - 2 * pad
        fun x(t: Long) = pad + (t - start).toFloat() / span * w
        fun y(v: Double) = pad + h - (if (max == min) 0.5f else ((v - min) / (max - min)).toFloat()) * h
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        listOf(0f, 0.5f).forEach { f -> drawLine(grid, Offset(pad, pad + h * f), Offset(pad + w, pad + h * f), 1.dp.toPx(), pathEffect = dash) }
        drawLine(axis, Offset(pad, pad + h), Offset(pad + w, pad + h), 1.dp.toPx())
        s.points.zipWithNext { a, b -> drawLine(line, Offset(x(a.measuredAtMillis), y(a.value)), Offset(x(b.measuredAtMillis), y(b.value)), 3.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round) }
        s.points.forEach { val c = Offset(x(it.measuredAtMillis), y(it.value)); drawCircle(surface, 6.dp.toPx(), c); drawCircle(line, 4.dp.toPx(), c) }
    }
    Text("mín. ${min.fmt()} · máx. ${max.fmt()} ${s.unit.label}", style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("Nueva medición", style = MaterialTheme.typography.titleLarge)
        ChipGroup("Medida") { BodyMetric.entries.forEach { m -> ChoiceChip(metric == m, { metric = m }, { Text(m.label) }) } }
        if (metric == BodyMetric.OTHER) OutlinedTextField(label, { if (it.length <= 100) label = it }, label = { Text("Nombre de la medida") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
            OutlinedTextField(value, { value = it }, label = { Text("Valor") }, singleLine = true, isError = value.isNotBlank() && parsedValue == null,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                modifier = Modifier.weight(1f))
            OutlinedTextField(date, { date = it }, label = { Text("Fecha (AAAA-MM-DD)") }, singleLine = true, isError = parsedDate == null,
                modifier = Modifier.weight(1.4f))
        }
        SegmentedChoice("Unidad", metric.units, unit, { it.label }, { unit = it })
        ChipGroup("Método (obligatorio: no se compara entre métodos)") { MeasureMethod.entries.forEach { m -> ChoiceChip(method == m, { method = m }, { Text(m.label) }) } }
        SegmentedChoice("Ayuno (opcional)", listOf(null, true, false), fasted,
            { when (it) { null -> "sin indicar"; true -> "en ayunas"; false -> "sin ayuno" } }, { fasted = it })
        OutlinedTextField(notes, { if (it.length <= 1000) notes = it }, label = { Text("Notas (opcional)") }, modifier = Modifier.fillMaxWidth())
        if (method == null) SupportingText("Elige un método para poder guardar.", small = true)
        Button(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled && valid, onClick = {
            onAdd(BodyMeasurement(UUID.randomUUID().toString(), parsedDate!!, metric, parsedValue!!, unit, method!!,
                label.trim().ifBlank { null }?.takeIf { metric == BodyMetric.OTHER }, fasted, null, null, notes.trim().ifBlank { null }))
            value = ""; notes = ""
        }) { Text("Guardar medición") }
    }
}
