package com.example.workoutpoc.catalog

import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.AppTheme
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.domain.loadLabel
import com.example.workoutpoc.domain.weightLabel
import com.example.workoutpoc.planning.TrainingProgram
import com.example.workoutpoc.planning.WeekAnnotations

private fun signed(tenths: Long, text: (Long) -> String) = (if (tenths > 0) "+" else if (tenths < 0) "−" else "±") + text(kotlin.math.abs(tenths))

/** Week-by-week comparison of one exercise, filterable by program and block (phase), with deloads kept apart. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhaseComparisonSection(exerciseId: String, sessions: List<WorkoutState>, programs: List<TrainingProgram>, annotations: WeekAnnotations) {
    var programId by rememberSaveable(exerciseId) { mutableStateOf<String?>(null) }
    var blockId by rememberSaveable(exerciseId, programId) { mutableStateOf<String?>(null) }
    var includeDeloads by rememberSaveable(exerciseId) { mutableStateOf(true) }
    val all = remember(sessions, exerciseId, programs, annotations) { phaseComparison(exerciseId, sessions, programs, annotations, PhaseFilter()) }
    val groups = remember(sessions, exerciseId, programs, annotations, programId, blockId, includeDeloads) {
        phaseComparison(exerciseId, sessions, programs, annotations, PhaseFilter(programId, blockId, includeDeloads))
    }
    if (all.isEmpty()) return
    val programsSeen = all.flatMap { g -> g.rows.map { it.programId to it.programName } }.distinct()
    val blocksSeen = all.flatMap { g -> g.rows.filter { programId == null || it.programId == programId }.map { it.blockId to it.blockName } }.distinct()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader("Comparación por semana y fase",
            supporting = "Cada fila resume una semana planificada (series de trabajo completadas). Los cambios se comparan con la semana comparable anterior; las semanas de descarga se muestran pero no se comparan.")
        ChipGroup("Programa") {
            ChoiceChip(programId == null, { programId = null }, { Text("Todos") })
            programsSeen.forEach { (id, name) -> ChoiceChip(programId == id, { programId = id }, { Text(name) }) }
        }
        ChipGroup("Bloque (fase)") {
            ChoiceChip(blockId == null, { blockId = null }, { Text("Todos") })
            blocksSeen.forEach { (id, name) -> ChoiceChip(blockId == id, { blockId = id }, { Text(name) }) }
        }
        ChoiceChip(includeDeloads, { includeDeloads = !includeDeloads }, { Text(if (includeDeloads) "Descargas visibles" else "Descargas ocultas") })
        if (groups.isEmpty()) EmptyState("Sin semanas con este filtro.")
        groups.forEach { g ->
            Text("${unitLabel(g.unit)} · ${basisLabel(g.basis)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.xs))
            g.rows.forEach { r ->
                val up = (r.deltaTopLoadTenths ?: 0) > 0 || (r.deltaVolumeTenths ?: 0) > 0
                ContentCard(color = if (r.deload) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text("${r.programName} · ${r.blockName} · ${r.weekName}" + if (r.deload) " · descarga" else "",
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (!r.deload && r.comparedWith != null && up) Icon(AppIcons.Trend, contentDescription = "Progreso", tint = AppTheme.extended.success, modifier = Modifier.size(18.dp))
                    }
                    Text(listOfNotNull(r.topLoadTenths?.let { "máx. ${loadLabel(it, g.unit)}" }, "${r.workSets} series", "${r.reps} reps",
                        "volumen ${weightLabel((r.volumeTenths).toInt())}").joinToString(" · "), style = MaterialTheme.typography.bodyMedium.tabular())
                    Text(when {
                        r.deload -> "Semana de descarga: no se compara como progreso ni regresión."
                        r.comparedWith == null -> "Primera semana comparable con este filtro."
                        else -> "Frente a ${r.comparedWith}: carga " + (r.deltaTopLoadTenths?.let { d -> signed(d.toLong()) { loadLabel(it.toInt(), g.unit) } } ?: "sin dato") +
                            " · volumen " + (r.deltaVolumeTenths?.let { d -> signed(d) { weightLabel(it.toInt()) } } ?: "sin dato")
                    }, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
