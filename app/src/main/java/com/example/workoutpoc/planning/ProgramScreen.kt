package com.example.workoutpoc.planning

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular

@Composable
fun ProgramScreen(
    library: ProgramLibrary, ui: ProgramUiState, onBack: () -> Unit, onFile: () -> Unit,
    onText: (String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onExchange: () -> Unit,
    onMigration: () -> Unit, onTrainDay: (TrainingProgram, String, String) -> Unit = { _, _, _ -> },
    onCatalog: () -> Unit = {}, onProposals: () -> Unit = {},
    annotations: WeekAnnotations = WeekAnnotations(), onDeload: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onBody: () -> Unit = {}, onAdherence: () -> Unit = {}, onQuestions: () -> Unit = {}, onStagnation: () -> Unit = {},
    onHistory: () -> Unit = {}
) {
    var text by remember { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = ui.preview ?: library.programs.find { it.id == selectedId }
    var weekId by rememberSaveable(selectedId, ui.preview?.id) { mutableStateOf<String?>(null) }
    var dayId by rememberSaveable(selectedId, ui.preview?.id, weekId) { mutableStateOf<String?>(null) }
    // System Back climbs one level of the browser before leaving the screen.
    BackHandler(dayId != null || weekId != null || (ui.preview == null && selectedId != null)) {
        when { dayId != null -> dayId = null; weekId != null -> weekId = null; else -> selectedId = null }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val level = listOf(selectedId, weekId, dayId, ui.preview?.id)
    var shownLevel by rememberSaveable { mutableStateOf(level.toString()) }
    LaunchedEffect(level) {
        // Item 0 is the tool grid; what follows it is the current level of the library.
        if (shownLevel != level.toString()) { shownLevel = level.toString(); listState.scrollToItem(1) }
    }
    AppScreen("Programas", "Volver al entrenamiento", onBack, busy = ui.busy, message = ui.message, listState = listState) {
        // A compact grid grouped by purpose, so the program library stays close to the top of the screen.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ToolGroup("Planificación con IA", listOf(
                    Triple("Propuestas de semana", AppIcons.Bolt, onProposals), Triple("Preguntas de planificación", AppIcons.Question, onQuestions),
                    Triple("Intercambio JSON", AppIcons.Swap, onExchange)))
                ToolGroup("Datos y análisis", listOf(
                    Triple("Historial de sesiones", AppIcons.History, onHistory), Triple("Catálogo de ejercicios", AppIcons.List, onCatalog),
                    Triple("Adherencia y objetivos", AppIcons.Target, onAdherence), Triple("Señales de estancamiento", AppIcons.Trend, onStagnation),
                    Triple("Peso y composición corporal", AppIcons.Scale, onBody), Triple("Migrar desde Obsidian", AppIcons.Upload, onMigration)))
            }
        }
        if (!ui.ready && !ui.busy) item { RetryAction("Reintentar biblioteca", onRetry, enabled = !ui.busy) }
        if (ui.preview != null) {
            item { SectionHeader("Revisar importación", supporting = "Revisa las semanas antes de guardar. Importar conserva la planificación; no inicia ni activa un entrenamiento.") }
            ui.merge?.let { merge ->
                item {
                    Banner(merge.summary(), tone = when (merge.kind) {
                        MergeKind.CONFLICT -> Tone.Error; MergeKind.IDENTICAL -> Tone.Info; else -> Tone.Success })
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    PrimaryAction(if (ui.merge?.kind == MergeKind.ADDS_WEEKS) "Añadir semanas nuevas" else "Guardar programa", onSave,
                        enabled = ui.ready && !ui.busy && ui.merge?.kind != MergeKind.CONFLICT, icon = AppIcons.Check)
                    SecondaryAction("Cancelar importación", onCancel, enabled = !ui.busy)
                }
            }
        } else if (selected == null) {
            item { SectionHeader("Importar programa") }
            item {
                ContentCard {
                    SupportingText("Importa un programa JSON v1 desde un archivo o pega su contenido. Puedes variar días, ejercicios y series en cada semana.")
                    PrimaryAction("Seleccionar archivo JSON", onFile, enabled = ui.ready && !ui.busy, icon = AppIcons.Upload)
                    OutlinedTextField(value = text, onValueChange = { if (it.length <= ProgramCodec.MAX_BYTES) text = it },
                        label = { Text("Contenido JSON del programa") }, modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp))
                    SecondaryAction("Revisar JSON", { onText(text) }, enabled = ui.ready && !ui.busy && text.isNotBlank())
                }
            }
            item { SectionHeader("Tus programas · ${library.programs.size}") }
            if (library.programs.isEmpty()) item { EmptyState("Aún no hay programas guardados.", icon = AppIcons.Library) }
            items(library.programs, key = { it.id }) { program ->
                val weeks = program.blocks.sumOf { it.weeks.size }
                NavRow(program.name, { selectedId = program.id }, icon = AppIcons.Library,
                    supporting = "${plural(program.blocks.size, "bloque", "bloques")} · ${plural(weeks, "semana", "semanas")}")
            }
        }
        if (selected != null) programBrowser(selected, weekId, dayId, if (ui.preview == null) ({ selectedId = null }) else null,
            { weekId = it }, { dayId = it },
            // Only saved programs can be trained; a preview must be saved first.
            if (ui.preview == null && ui.ready && !ui.busy) onTrainDay else null,
            annotations, if (ui.preview == null && ui.ready && !ui.busy) onDeload else null)
    }
}

private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

private fun LazyListScope.programBrowser(
    program: TrainingProgram, weekId: String?, dayId: String?, onAllPrograms: (() -> Unit)?,
    onWeek: (String?) -> Unit, onDay: (String?) -> Unit,
    onTrainDay: ((TrainingProgram, String, String) -> Unit)?,
    annotations: WeekAnnotations, onDeload: ((String, String, Boolean) -> Unit)?
) {
    val week = program.blocks.flatMap { it.weeks }.find { it.id == weekId }
    val day = week?.days?.find { it.id == dayId }
    // One wrapping trail of every level above the current one, instead of a stack of separate back links.
    val trail = listOfNotNull(onAllPrograms?.let { "Ver todos los programas" to it },
        if (week != null) "Semanas" to { onWeek(null) } else null, if (day != null) "Días" to { onDay(null) } else null)
    if (trail.isNotEmpty()) item { Breadcrumbs(trail) }
    if (week == null) {
        item { Text(program.name, style = MaterialTheme.typography.headlineSmall) }
        program.blocks.forEach { block ->
            item { Text(block.name, Modifier.padding(top = Spacing.sm).semantics { heading() }, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary) }
            items(block.weeks, key = { "week-${it.id}" }) { w ->
                NavRow("${w.name} · ${w.days.size} ${if (w.days.size == 1) "día" else "días"}", { onWeek(w.id) }, icon = AppIcons.Calendar,
                    supporting = "versión ${w.version}",
                    badge = if (annotations.isDeload(program.id, w.id)) ({ StatusPill("descarga") }) else null)
            }
        }
    } else {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SupportingText(program.name, small = true)
                Text("${week.name} · versión ${week.version}", style = MaterialTheme.typography.headlineSmall)
            }
        }
        if (onDeload != null) item {
            val deload = annotations.isDeload(program.id, week.id)
            // The whole row toggles, so the label is a tap target and is announced with the switch state.
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().toggleable(deload, role = Role.Switch) { onDeload(program.id, week.id, it) }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Column(Modifier.weight(1f)) {
                        Text("Semana de descarga" + if (deload) " (marcada)" else "", style = MaterialTheme.typography.bodyLarge)
                        Text("Las gráficas y comparaciones no la tratan como regresión.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(deload, null)
                }
            }
        }
        if (day == null) {
            item { Eyebrow("DÍAS", Modifier.padding(top = Spacing.sm)) }
            items(week.days, key = { "day-${it.id}" }) { d ->
                NavRow("${d.name} · ${d.exercises.size} ${if (d.exercises.size == 1) "ejercicio" else "ejercicios"} · ${d.exercises.sumOf { it.sets.size }} series",
                    { onDay(d.id) }, icon = AppIcons.Fitness)
            }
        } else {
            item { Text(day.name, style = MaterialTheme.typography.titleLarge) }
            if (onTrainDay != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    PrimaryAction("Entrenar este día", { onTrainDay(program, week.id, day.id) }, icon = AppIcons.Play, tall = true)
                    SupportingText("Carga el día como próxima sesión. Una sesión en curso no se reemplaza; una terminada se archiva antes.", small = true)
                }
            }
            items(day.exercises, key = { "exercise-${it.id}" }) { exercise ->
                ContentCard {
                    Text(exercise.name, style = MaterialTheme.typography.titleMedium)
                    exercise.sets.forEachIndexed { index, set ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SetLine(index, set)
                    }
                }
            }
        }
    }
}

@Composable
private fun SetLine(index: Int, set: SetPrescription) {
    Column(Modifier.padding(vertical = Spacing.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Serie ${index + 1} · ${set.reps?.let { "$it reps" } ?: "reps sin indicar"} · ${set.rir?.let { "RIR $it" } ?: "RIR sin indicar"}",
            style = MaterialTheme.typography.bodyLarge.tabular())
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(set.load?.let { "${it.value} ${it.unit.label()} · ${it.basis.label()}" } ?: "Carga sin indicar",
                style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(set.restSeconds?.let { "Descanso: $it s" } ?: "Descanso sin indicar",
                style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun LoadUnit.label() = when (this) {
    LoadUnit.KG -> "kg"; LoadUnit.LB -> "lb"; LoadUnit.MACHINE_SETTING -> "posición"; LoadUnit.BODYWEIGHT -> "peso corporal"
}
private fun LoadBasis.label() = when (this) {
    LoadBasis.TOTAL -> "total"; LoadBasis.PER_SIDE -> "por lado"; LoadBasis.PER_HAND -> "por mano"
    LoadBasis.BODYWEIGHT -> "peso corporal"; LoadBasis.MACHINE_SETTING -> "configuración de máquina"
}

/** Labelled group of tool tiles, three per row with equal heights. */
@Composable
private fun ToolGroup(title: String, entries: List<Triple<String, ImageVector, () -> Unit>>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Eyebrow(title.uppercase())
        entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                row.forEach { (label, icon, action) -> ToolTile(label, icon, action, Modifier.weight(1f).fillMaxHeight()) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ToolTile(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 88.dp), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.md), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            IconBadge(icon, size = 36.dp)
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 3)
        }
    }
}
