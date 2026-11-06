package com.example.workoutpoc.catalog

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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Comparación por semana y fase", style = MaterialTheme.typography.titleLarge)
        Text("Cada fila resume una semana planificada (series de trabajo completadas). Los cambios se comparan con la semana comparable anterior; las semanas de descarga se muestran pero no se comparan.",
            style = MaterialTheme.typography.bodySmall)
        Text("Programa", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(programId == null, { programId = null }, { Text("Todos") })
            programsSeen.forEach { (id, name) -> FilterChip(programId == id, { programId = id }, { Text(name) }) }
        }
        Text("Bloque (fase)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(blockId == null, { blockId = null }, { Text("Todos") })
            blocksSeen.forEach { (id, name) -> FilterChip(blockId == id, { blockId = id }, { Text(name) }) }
        }
        FilterChip(includeDeloads, { includeDeloads = !includeDeloads }, { Text(if (includeDeloads) "Descargas visibles" else "Descargas ocultas") })
        if (groups.isEmpty()) Text("Sin semanas con este filtro.")
        groups.forEach { g ->
            Text("${unitLabel(g.unit)} · ${basisLabel(g.basis)}", style = MaterialTheme.typography.titleMedium)
            g.rows.forEach { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${r.programName} · ${r.blockName} · ${r.weekName}" + if (r.deload) " · descarga" else "", style = MaterialTheme.typography.titleSmall)
                        Text(listOfNotNull(r.topLoadTenths?.let { "máx. ${loadLabel(it, g.unit)}" }, "${r.workSets} series", "${r.reps} reps",
                            "volumen ${weightLabel((r.volumeTenths).toInt())}").joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        Text(when {
                            r.deload -> "Semana de descarga: no se compara como progreso ni regresión."
                            r.comparedWith == null -> "Primera semana comparable con este filtro."
                            else -> "Frente a ${r.comparedWith}: carga " + (r.deltaTopLoadTenths?.let { d -> signed(d.toLong()) { loadLabel(it.toInt(), g.unit) } } ?: "sin dato") +
                                " · volumen " + (r.deltaVolumeTenths?.let { d -> signed(d) { weightLabel(it.toInt()) } } ?: "sin dato")
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
