package com.example.workoutpoc.catalog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.planning.WeekAnnotations
import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun Double.fmt() = if (this == Math.floor(this)) toLong().toString() else "%.1f".format(this)

/**
 * Line chart of one unit/basis group. Points are evenly spaced in time order; a missing value breaks the line instead
 * of dropping to zero; deload exposures are drawn hollow on a shaded band so a lighter week does not read as a regression.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PerformanceChart(group: ExposureGroup, annotations: WeekAnnotations) {
    var metric by remember(group.unit, group.basis) { mutableStateOf(ChartMetric.TOP_LOAD) }
    val points = remember(group, metric, annotations) { chartSeries(group, metric, annotations) }
    val values = points.mapNotNull { it.value }
    val format = remember { SimpleDateFormat("MM-dd", Locale.getDefault()) }
    val line = MaterialTheme.colorScheme.primary
    val band = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
    val deloadColor = MaterialTheme.colorScheme.tertiary
    val axis = MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        ChipGroup(null) {
            ChartMetric.entries.forEach { m -> ChoiceChip(metric == m, { metric = m }, { Text(m.label) }) }
        }
        val unit = if (metric == ChartMetric.TOP_LOAD || metric == ChartMetric.VOLUME) " (${unitLabel(group.unit)})" else ""
        if (values.isEmpty()) {
            EmptyState("Sin valores para ${metric.label.lowercase()}: no hay series de trabajo completadas con ese dato.", icon = AppIcons.Chart)
            return@Column
        }
        val min = values.min(); val max = values.max()
        val description = "${metric.label}$unit: " + points.joinToString("; ") { p ->
            "${format.format(Date(p.startedAtMillis))} ${p.value?.fmt() ?: "sin dato"}${if (p.deload) " descarga" else ""}"
        }
        Text("${metric.label}$unit · mín. ${min.fmt()} · máx. ${max.fmt()}", style = MaterialTheme.typography.labelLarge.tabular())
        Canvas(Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = description }) {
            val pad = 12.dp.toPx()
            val w = size.width - 2 * pad; val h = size.height - 2 * pad
            val step = if (points.size > 1) w / (points.size - 1) else 0f
            fun x(i: Int) = pad + (if (points.size > 1) i * step else w / 2)
            fun y(v: Double) = pad + h - (if (max == min) 0.5f else ((v - min) / (max - min)).toFloat()) * h
            // Light dashed guides at the top, middle and bottom of the value range.
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            listOf(0f, 0.5f).forEach { f -> drawLine(grid, Offset(pad, pad + h * f), Offset(pad + w, pad + h * f), 1.dp.toPx(), pathEffect = dash) }
            drawLine(axis, Offset(pad, pad + h), Offset(pad + w, pad + h), 1.dp.toPx())
            points.forEachIndexed { i, p ->
                if (p.deload) drawRect(band, Offset(x(i) - maxOf(step, 16.dp.toPx()) / 2, pad), Size(maxOf(step, 16.dp.toPx()), h))
            }
            for (i in 1 until points.size) {
                val a = points[i - 1].value; val b = points[i].value
                if (a != null && b != null) drawLine(line, Offset(x(i - 1), y(a)), Offset(x(i), y(b)), 3.dp.toPx(), cap = StrokeCap.Round)
            }
            points.forEachIndexed { i, p ->
                val v = p.value ?: return@forEachIndexed
                if (p.deload) drawCircle(deloadColor, 5.dp.toPx(), Offset(x(i), y(v)), style = Stroke(2.dp.toPx()))
                else { drawCircle(surface, 6.dp.toPx(), Offset(x(i), y(v))); drawCircle(line, 4.dp.toPx(), Offset(x(i), y(v))) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(format.format(Date(points.first().startedAtMillis)), style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (points.size > 1) Text(format.format(Date(points.last().startedAtMillis)), style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("● sesión normal · ○ semana de descarga (franja) · un hueco es una sesión sin series de trabajo con dato, no un cero.",
            style = MaterialTheme.typography.bodySmall, color = if (points.any { it.deload || it.value == null }) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
