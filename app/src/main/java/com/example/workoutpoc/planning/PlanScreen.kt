package com.example.workoutpoc.planning

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.workoutpoc.exchange.ExchangeType
import com.example.workoutpoc.exchange.toSessionResult
import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.Spacing
import com.example.workoutpoc.workoutApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class OutlineStatus(val label: String, val tone: Tone) {
    ACTIVE("Activo", Tone.Success), PENDING("Pendiente", Tone.Warning), REPLACED("Reemplazado", Tone.Info), INACTIVE("Inactivo", Tone.Info)
}

/** An imported plan (archived in Intercambio JSON) with what activating it would mean. */
data class OutlineEntry(val importId: String, val outline: PlanOutline, val status: OutlineStatus, val conflicts: List<String>)

data class PlanUiState(
    val ready: Boolean = false, val busy: Boolean = false, val message: String? = null,
    val entries: List<OutlineEntry> = emptyList(), val briefs: Map<String, NextWeekBrief> = emptyMap(), val selected: String? = null,
    /** The plan the user asked to delete, and what deleting it would remove, while the confirmation is open. */
    val deleteTarget: OutlineEntry? = null, val deleteCounts: PlanDeletionCounts? = null
)

class PlanViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.workoutApp
    private val mutableUi = MutableStateFlow(PlanUiState())
    val ui = mutableUi.asStateFlow()
    init { refresh() }

    private val deletion get() = PlanDeletion(app.outlines, app.programs, app.weekAnnotations, app.proposals, app.comments,
        app.exchanges, app.repository)

    fun refresh() = operation { load() }

    /** Opens the confirmation with the exact counts of what would be removed. Nothing is written here. */
    fun askDelete(entry: OutlineEntry) {
        if (ui.value.busy) return
        viewModelScope.launch {
            try {
                load()
                // Only the confirmation needs these stores, so a read failure here cannot hide the plans list.
                app.weekAnnotations.restore(); app.proposals.restore(); app.comments.restore()
                mutableUi.value = mutableUi.value.copy(deleteTarget = entry, deleteCounts = deletion.preview(entry.outline.programId), message = null)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableUi.value = mutableUi.value.copy(message = "No se pudo leer los datos del plan. Reintenta.") }
        }
    }

    fun cancelDelete() { mutableUi.value = mutableUi.value.copy(deleteTarget = null, deleteCounts = null) }

    fun confirmDelete() = operation {
        val target = ui.value.deleteTarget ?: return@operation
        mutableUi.value = mutableUi.value.copy(deleteTarget = null, deleteCounts = null)
        val removed = deletion.delete(target.outline.programId)
        load()
        mutableUi.value = mutableUi.value.copy(selected = null, message = "Plan «${target.outline.name}» borrado: ${removed.outlineVersions} versión(es), " +
            "${removed.weeks} semana(s), ${removed.sessions} sesión(es), ${removed.weekAnnotations} anotación(es) de semana, " +
            "${removed.proposalRecords} registro(s) de propuesta, ${removed.comments} comentario(s) y ${removed.exchangePackages} paquete(s) importado(s).")
    }

    private suspend fun load() {
        app.outlines.restore(); app.exchanges.restore(); app.programs.restore(); app.repository.restore()
        val store = app.outlines.outlines.value
        val programs = app.programs.library.value.programs
        val sessions = (app.repository.archived.value.sessions + app.repository.state.value).distinctBy { it.sessionId }.mapNotNull { it.toSessionResult() }
        val imported = app.exchanges.archive.value.packages.filter { it.type == ExchangeType.PlanOutline }
        // Active plans first, even when they were activated from an export that is no longer archived.
        val entries = (store.active.map { a -> imported.find { it.outline == a }?.importId to a } +
            imported.filter { p -> store.active.none { it == p.outline } }.map { it.importId to it.outline!! } +
            // Set aside by activating another plan, even if its export is no longer archived.
            store.inactive.filter { o -> imported.none { it.outline == o } }.map { "inactivo-${it.id}-${it.version}" to it })
            .map { (importId, o) ->
                val status = when { o in store.active -> OutlineStatus.ACTIVE; o in store.replaced -> OutlineStatus.REPLACED
                    o in store.inactive -> OutlineStatus.INACTIVE; else -> OutlineStatus.PENDING }
                OutlineEntry(importId ?: "activo-${o.id}", o, status, PlanOutlines.conflicts(o, programs.find { it.id == o.programId }))
            }
        mutableUi.value = mutableUi.value.copy(ready = true, entries = entries,
            briefs = store.active.associate { o -> o.id to PlanOutlines.brief(o, programs.find { it.id == o.programId }, sessions) })
    }

    fun select(importId: String?) { if (!ui.value.busy) mutableUi.value = mutableUi.value.copy(selected = importId, message = null) }

    fun activate(entry: OutlineEntry) = operation {
        app.programs.restore()
        val result = app.outlines.activate(entry.outline, app.programs.library.value.programs.find { it.id == entry.outline.programId })
        // Inside an operation, so the list is reloaded directly: refresh() would be ignored while busy.
        load()
        mutableUi.value = mutableUi.value.copy(message = when (result) {
            ActivationResult.ACTIVATED -> "Plan activado; es el único activo y Hoy lo sigue. Prepara el contexto en Intercambio JSON: incluye el plan y la próxima semana para el agente."
            ActivationResult.REPLACED -> "Versión ${entry.outline.version} activada. La anterior se conserva como reemplazada; las semanas guardadas no cambian."
            ActivationResult.ALREADY_ACTIVE -> "Este plan ya estaba activo. No se cambió nada."
        })
    }

    private fun operation(block: suspend () -> Unit) {
        if (ui.value.busy) return
        mutableUi.value = mutableUi.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { mutableUi.value = mutableUi.value.copy(message = e.message) }
            // Refusals written for the user (for example, a session of this plan still in progress).
            catch (e: IllegalStateException) { mutableUi.value = mutableUi.value.copy(message = e.message) }
            catch (_: Exception) { mutableUi.value = mutableUi.value.copy(message = "No se pudo leer o guardar el plan. Tus datos se conservan. Reintenta.") }
            finally { mutableUi.value = mutableUi.value.copy(busy = false) }
        }
    }
}

private fun range(lo: Int?, hi: Int?) = when { lo == null && hi == null -> null; lo == hi -> "$lo"; else -> "${lo ?: "—"}–${hi ?: "—"}" }

fun WeekTarget.describe(): String = listOfNotNull(
    sets?.let { "$it series" }, range(repsMin, repsMax)?.let { "$it reps" }, range(rirMin, rirMax)?.let { "RIR $it" }
).joinToString(" · ").ifBlank { "sin cifras" }

fun CardioTarget.describe(): String = listOfNotNull(modality, minutes?.let { "$it min" }, sessions?.let { "×$it" },
    range(rpeMin, rpeMax)?.let { "RPE $it" }).joinToString(" · ")

@Composable
fun PlanScreen(ui: PlanUiState, onBack: () -> Unit, onRetry: () -> Unit, onSelect: (String?) -> Unit, onActivate: (OutlineEntry) -> Unit,
               onAskDelete: (OutlineEntry) -> Unit, onCancelDelete: () -> Unit, onConfirmDelete: () -> Unit) {
    val entry = ui.entries.find { it.importId == ui.selected }
    BackHandler(entry != null && !ui.busy) { onSelect(null) }
    val target = ui.deleteTarget; val counts = ui.deleteCounts
    if (target != null && counts != null) AlertDialog(onDismissRequest = onCancelDelete,
        icon = { Icon(AppIcons.Delete, contentDescription = null) },
        title = { Text("¿Borrar el plan y sus entrenamientos?") },
        text = { Text("Se borrará «${target.outline.name} v${target.outline.version}» y, del programa ${target.outline.programId}: " +
            "${counts.outlineVersions} versión(es) del plan, ${counts.weeks} semana(s), ${counts.sessions} sesión(es), " +
            "${counts.weekAnnotations} anotación(es) de semana, ${counts.proposalRecords} registro(s) de propuesta, " +
            "${counts.comments} comentario(s) y ${counts.exchangePackages} paquete(s) importado(s). " +
            "No se puede deshacer. Otros programas, medidas, objetivos y reglas no cambian.") },
        confirmButton = { TextButton(onClick = onConfirmDelete,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Borrar") } },
        dismissButton = { TextButton(onClick = onCancelDelete) { Text("Cancelar") } })
    AppScreen("Planes de periodización", if (entry != null) "Ver todos los planes" else "Volver a Programas",
        { if (entry != null) onSelect(null) else onBack() }, backEnabled = !ui.busy, busy = ui.busy, message = ui.message,
        listState = rememberViewListState(ui.selected), drillUp = entry != null) {
        if (!ui.ready && !ui.busy) item { RetryAction("Reintentar planes", onRetry, enabled = !ui.busy) }
        if (entry == null) {
            item {
                SupportingText("La estructura general del ciclo (por ejemplo, 12 semanas): bloques, fases, descargas, objetivos de series, " +
                    "repeticiones y RIR, cardio y distribución de días. El agente la escribe; la importas en Intercambio JSON y la activas aquí. " +
                    "Cada semana, el contexto exportado le indica al agente qué semana toca y con qué objetivos.")
            }
            if (ui.entries.isEmpty()) item { EmptyState("No hay planes importados.", icon = AppIcons.Calendar) }
            items(ui.entries, key = { it.importId }) { e ->
                val brief = ui.briefs[e.outline.id]?.takeIf { e.status == OutlineStatus.ACTIVE }
                NavRow("${e.outline.name} v${e.outline.version}", { onSelect(e.importId) }, enabled = !ui.busy, icon = AppIcons.Calendar,
                    supporting = "${e.outline.programName} · ${e.outline.weeks.size} semanas" + (brief?.let { " · " + it.short() } ?: ""),
                    badge = { StatusPill(e.status.label, e.status.tone) })
            }
        } else {
            val o = entry.outline
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text("${o.name} · v${o.version}", style = MaterialTheme.typography.headlineSmall)
                    SupportingText("Programa ${o.programName} (${o.programId}) · ${o.weeks.size} semanas · ${o.blocks.size} bloques · ${o.days.size} días")
                }
            }
            ui.briefs[o.id]?.takeIf { entry.status == OutlineStatus.ACTIVE }?.let { b ->
                item { Banner(b.message, title = "Próxima semana", tone = if (b.status == BriefStatus.READY) Tone.Success else Tone.Info) }
            }
            if (entry.status == OutlineStatus.PENDING || entry.status == OutlineStatus.INACTIVE) item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (entry.conflicts.isNotEmpty()) Banner(entry.conflicts.joinToString("\n") { "• $it" }, title = "No coincide con el programa guardado", tone = Tone.Error)
                    PrimaryAction("Activar plan", { onActivate(entry) }, enabled = !ui.busy && entry.conflicts.isEmpty(), icon = AppIcons.Check)
                    SupportingText("Activar no crea ni cambia semanas: cada semana llega como propuesta y solo se añade cuando la apruebas.", small = true)
                }
            }
            item {
                TextButton(onClick = { onAskDelete(entry) }, enabled = !ui.busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(AppIcons.Delete, contentDescription = null); Text("Borrar plan y entrenamientos")
                }
            }
            if (o.goals.isNotEmpty() || o.rules.isNotEmpty()) item {
                ContentCard {
                    o.goals.forEach { Text("Objetivo: $it", style = MaterialTheme.typography.bodyMedium) }
                    o.rules.forEach { Text("Regla: $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            item { SectionHeader("Semanas") }
            items(o.weeks, key = { "w${it.number}" }) { w -> WeekCard(o, w) }
            item { SectionHeader("Distribución de días") }
            items(o.days, key = { "d${it.id}" }) { d ->
                ContentCard {
                    Text("${d.name}" + (d.focus?.let { " — $it" } ?: ""), style = MaterialTheme.typography.titleSmall)
                    d.exercises.forEach { e ->
                        Text("• ${e.name}" + (e.sets?.let { " · $it series" } ?: "") +
                            (e.restSeconds?.let { " · ${if (it == 0) "sin descanso" else "descanso $it s"}" } ?: "") + (e.notes?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun NextWeekBrief.short() = when (status) {
    BriefStatus.READY -> "toca planificar la semana $weekNumber"
    BriefStatus.ALREADY_PLANNED -> "semana $weekNumber pendiente de registrar"
    BriefStatus.CYCLE_COMPLETE -> "ciclo terminado"
}

@Composable
private fun WeekCard(o: PlanOutline, w: OutlineWeek) {
    val block = o.blocks.first { it.id == w.blockId }
    val (inBlock, size) = PlanOutlines.weekInBlock(o, w.number)
    ContentCard {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("Semana ${w.number} · ${block.name} ($inBlock/$size)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (w.deload) StatusPill("descarga", Tone.Info)
        }
        Text("${w.phase} · ${w.microcycle}", style = MaterialTheme.typography.bodyMedium)
        w.targets.forEach { t ->
            Text("• ${t.label}: ${t.describe()}" + (if (t.exerciseIds.isNotEmpty()) " (${t.exerciseIds.size} ejercicios)" else ""),
                style = MaterialTheme.typography.bodySmall)
        }
        w.cardio?.let { Text("• Cardio: ${it.describe()}", style = MaterialTheme.typography.bodySmall) }
        w.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}


