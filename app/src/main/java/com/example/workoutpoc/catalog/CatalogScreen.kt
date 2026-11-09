package com.example.workoutpoc.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.domain.SetStatus
import com.example.workoutpoc.domain.loadLabel
import com.example.workoutpoc.domain.rirLabel
import com.example.workoutpoc.planning.LoadBasis
import java.text.SimpleDateFormat
import androidx.activity.compose.BackHandler
import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.ui.theme.tabular
import java.util.Date
import java.util.Locale

fun Equipment.label() = when (this) {
    Equipment.BARBELL -> "barra"; Equipment.DUMBBELL -> "mancuernas"; Equipment.MACHINE -> "máquina"; Equipment.CABLE -> "polea"
    Equipment.BODYWEIGHT -> "peso corporal"; Equipment.KETTLEBELL -> "pesa rusa"; Equipment.BAND -> "banda"; Equipment.OTHER -> "otro"
}
private fun LoadBasis.label() = when (this) {
    LoadBasis.TOTAL -> "total"; LoadBasis.PER_SIDE -> "por lado"; LoadBasis.PER_HAND -> "por mano"
    LoadBasis.BODYWEIGHT -> "peso corporal"; LoadBasis.MACHINE_SETTING -> "posición de máquina"
}

@Composable
fun CatalogScreen(catalog: ExerciseCatalog, ui: CatalogUiState, onBack: () -> Unit, onRetry: () -> Unit, onSave: (CatalogExercise) -> Unit,
                  annotations: com.example.workoutpoc.planning.WeekAnnotations = com.example.workoutpoc.planning.WeekAnnotations(),
                  /** Current stagnation alerts (INT-03); each listed exercise with one carries a badge and its explanation. */
                  alerts: List<StagnationAlert> = emptyList(), onStagnation: (() -> Unit)? = null) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = catalog.exercises.find { it.id == selectedId }
    val ambiguous = remember(catalog) { CatalogCodec.ambiguous(catalog) }
    BackHandler(selected != null && !ui.busy) { selectedId = null }
    AppScreen("Catálogo de ejercicios", if (selected != null) "Ver todos los ejercicios" else "Volver a Programas",
        { if (selected != null) selectedId = null else onBack() }, backEnabled = !ui.busy, busy = ui.busy, message = ui.message,
        listState = rememberViewListState(selected?.id), drillUp = selected != null) {
        if (!ui.ready && !ui.busy) item { RetryAction("Reintentar catálogo", onRetry, enabled = !ui.busy) }
        if (selected == null) {
            onStagnation?.let { open ->
                val stalled = alerts.map { it.exerciseId }.distinct().size
                item { NavRow("Señales de estancamiento", open, icon = AppIcons.Trend,
                    supporting = if (stalled == 0) "Ninguna alerta con la evidencia actual · umbrales y datos insuficientes"
                        else "${plural(stalled, "ejercicio sin progreso", "ejercicios sin progreso")} frente a su mejor marca · umbrales y evidencia",
                    badge = if (stalled > 0) ({ StatusPill("$stalled", Tone.Warning) }) else null) }
            }
            item { SupportingText("Cada ejercicio tiene un ID estable. Cambiar su nombre no altera su historial; variantes con distinto equipo o configuración son ejercicios distintos.") }
            if (catalog.exercises.isEmpty()) item { EmptyState("Aún no hay ejercicios. Se registran al guardar programas o entrenar sesiones planificadas.", icon = AppIcons.List) }
            items(catalog.exercises, key = { it.id }) { e ->
                val count = remember(ui.sessions, e.id) { exerciseHistory(e.id, ui.sessions).size }
                NavRow(listOfNotNull(e.name, e.variant, e.equipment?.label()).joinToString(" · "), { selectedId = e.id },
                    supporting = "ID ${e.id} · ${plural(count, "serie registrada", "series registradas")}" + if (e.id in ambiguous) " · nombre ambiguo" else "",
                    icon = AppIcons.Fitness, badge = rowBadges(e.id in ambiguous, alerts.any { it.exerciseId == e.id }))
            }
        } else {
            item { Text(selected.name, style = MaterialTheme.typography.headlineSmall) }
            alerts.filter { it.exerciseId == selected.id }.forEach { alert ->
                item { Banner(alert.explanation, tone = Tone.Warning, title = "Señal de estancamiento",
                    action = onStagnation?.let { open -> { TextButton(onClick = open) { Text("Ver evidencia") } } }) }
            }
            item { ContentCard { ExerciseEditor(selected, selected.id in ambiguous, !ui.busy && ui.ready, onSave) } }
            item { PhaseComparisonSection(selected.id, ui.sessions, ui.programs, annotations) }
            val history = exerciseHistory(selected.id, ui.sessions)
            val groups = exposures(selected.id, ui.sessions)
            item { SectionHeader("Historial · ${plural(history.size, "serie", "series")}",
                supporting = if (groups.isEmpty()) null else "Comparación por exposición: cada sesión resume solo series de trabajo completadas. Unidades y bases distintas se muestran en grupos separados y nunca se mezclan.") }
            if (groups.isEmpty()) item { EmptyState("Sin series registradas para este ID.", icon = AppIcons.Chart) }
            groups.forEach { group ->
                item { Text("${unitLabel(group.unit)} · ${basisLabel(group.basis)} · ${plural(group.exposures.size, "exposición", "exposiciones")}",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.sm)) }
                item { ContentCard { PerformanceChart(group, annotations) } }
                items(group.exposures, key = { "${group.unit}-${group.basis}-${it.sessionId}" }) { e ->
                    val format = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
                    val deload = annotations.isDeload(e.programId, e.weekId)
                    ContentCard {
                        Text("${format.format(Date(e.startedAtMillis))} · ${e.label}" + if (deload) " · descarga" else "",
                            style = MaterialTheme.typography.titleSmall)
                        Text(listOfNotNull(
                            e.topLoadTenths?.let { "máx. ${loadLabel(it, group.unit)} × ${e.bestRepsAtTopLoad}" },
                            "${plural(e.workSets, "serie", "series")} · ${e.totalReps} reps",
                            e.minRir?.let { "RIR mín. ${rirLabel(it)}" }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium.tabular())
                        e.sets.forEach { set ->
                            Text((if (set.skipped) "Omitida" else "${loadLabel(set.values.weightTenths, group.unit)} × ${set.values.reps} @ RIR ${rirLabel(set.values.rir)}") +
                                (if (set.warmup) " · aproximación" else if (set.extra) " · extra" else "") +
                                (if (set.recordedName != selected.name) " · registrada como \"${set.recordedName}\"" else ""),
                                style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseEditor(exercise: CatalogExercise, ambiguous: Boolean, enabled: Boolean, onSave: (CatalogExercise) -> Unit) {
    // Saveable: the editor is a lazy-list item and must survive scrolling to the history and back.
    var name by rememberSaveable(exercise) { mutableStateOf(exercise.name) }
    var movement by rememberSaveable(exercise) { mutableStateOf(exercise.movement.orEmpty()) }
    var variant by rememberSaveable(exercise) { mutableStateOf(exercise.variant.orEmpty()) }
    var equipment by rememberSaveable(exercise) { mutableStateOf(exercise.equipment) }
    var basis by rememberSaveable(exercise) { mutableStateOf(exercise.loadBasis) }
    var setup by rememberSaveable(exercise) { mutableStateOf(exercise.setup.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("ID estable: ${exercise.id}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Save sits at the top so it stays reachable above the keyboard and the long attribute list.
        PrimaryAction("Guardar ejercicio", { onSave(exercise.copy(name = name, movement = movement, variant = variant, equipment = equipment,
            loadBasis = basis, setup = setup)) }, enabled = enabled && name.isNotBlank(), icon = AppIcons.Check)
        if (ambiguous) Banner("Otro ejercicio tiene el mismo nombre, variante y equipo. Añade una variante o equipo para distinguirlos.", tone = Tone.Warning)
        OutlinedTextField(name, { if (it.length <= 200) name = it }, label = { Text("Nombre visible") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (exercise.aliases.isNotEmpty()) SupportingText("Nombres anteriores: ${exercise.aliases.joinToString(", ")}", small = true)
        OutlinedTextField(movement, { if (it.length <= 200) movement = it }, label = { Text("Movimiento (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(variant, { if (it.length <= 200) variant = it }, label = { Text("Variante (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ChipGroup("Equipo") { (listOf(null) + Equipment.entries).forEach { o -> ChoiceChip(equipment == o, { equipment = o }, { Text(o?.label() ?: "sin indicar") }) } }
        ChipGroup("Base de carga habitual (informativa)") { (listOf(null) + LoadBasis.entries).forEach { o -> ChoiceChip(basis == o, { basis = o }, { Text(o?.label() ?: "sin indicar") }) } }
        OutlinedTextField(setup, { if (it.length <= 1000) setup = it }, label = { Text("Configuración de máquina o montaje") }, modifier = Modifier.fillMaxWidth())
    }
}

private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

/** Status is spelled out, never colour alone: "estancado" for a current alert, "revisar" for an ambiguous name. */
private fun rowBadges(ambiguous: Boolean, stalled: Boolean): (@Composable () -> Unit)? =
    if (!ambiguous && !stalled) null else ({
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (stalled) StatusPill("estancado", Tone.Warning)
            if (ambiguous) StatusPill("revisar", Tone.Warning)
        }
    })
