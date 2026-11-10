package com.example.workoutpoc.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.data.activePlan
import com.example.workoutpoc.domain.Phase
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.planning.DayAdherence
import com.example.workoutpoc.planning.DayStatus
import com.example.workoutpoc.planning.SessionPlan
import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.Spacing

/** Where Hoy's rows lead; MainActivity maps each to a tab or screen. */
enum class HomeLink { Plan, Proposals, Questions, Exchange, Body, Generate }

/**
 * The Hoy tab: what to do now. A loaded or running session comes first. Otherwise the next day of the current week,
 * in order, with one button to train it; the week strip picks another day. Below: what is waiting for you
 * (Pendiente) and this week's progress. The demo is never offered here (it lives in Ajustes).
 */
@Composable
fun HomeScreen(
    state: WorkoutState, ready: Boolean, error: String?, home: HomeUiState, onRetry: () -> Unit,
    onSession: () -> Unit, onTrain: (programId: String, weekId: String, dayId: String) -> Unit, onLink: (HomeLink) -> Unit
) {
    val source = state.source
    // Only a running session takes over the card. A finished or prepared one is a row: the next day still comes first.
    val loaded = state.phase == Phase.ActiveSet || state.phase == Phase.Resting
    val week = home.summary?.week
    var chosen by rememberSaveable(week?.week?.id) { mutableStateOf<String?>(null) }
    val shown: SessionPlan? = week?.days?.find { it.day.id == chosen }?.day ?: week?.next
    AppScreen("Hoy", "", {}, busy = !ready || !home.ready) {
        error?.let { item { Banner(it, tone = Tone.Error, action = { TextButton(onClick = onRetry) { Text("Reintentar") } }) } }
        if (home.failed) item { Banner("No se pudo leer el resumen de hoy. Tus datos se conservan.", tone = Tone.Error) }
        home.summary?.cycleNotice?.let { notice -> item {
            Banner(notice, title = if (home.summary.cycleComplete) "Ciclo terminado" else "Última semana", tone = Tone.Warning)
        } }
        if (loaded) item { SessionCard(state, ready, onSession) }
        else when {
            state.phase == Phase.Completed -> item { NavRow("Ver resumen de ${source?.dayName ?: "la demo"}", onSession, icon = AppIcons.CheckCircle,
                supporting = source?.let { "${it.programName} · ${it.weekName} · terminada" } ?: "Demo terminada") }
            source != null -> item { NavRow("Sesión preparada: ${source.dayName}", onSession, icon = AppIcons.Fitness,
                supporting = "${source.programName} · ${source.weekName} · sin empezar") }
        }
        if (!loaded && week != null) item {
            ContentCard {
                Eyebrow(listOfNotNull("SEMANA ${week.number}" + (week.planWeeks?.let { " DE $it" } ?: ""), week.block.name.uppercase(),
                    if (week.deload) "DESCARGA" else null).joinToString(" · "))
                if (shown == null) {
                    Text("Semana registrada", style = MaterialTheme.typography.titleLarge)
                    SupportingText("Todos los días tienen registro. Elige un día para repetirlo o pide la semana siguiente al agente.")
                } else {
                    Text((if (shown == week.next) "Siguiente: " else "") + shown.name, style = MaterialTheme.typography.titleLarge)
                    SupportingText("${week.program.name} · ${week.week.name}")
                    SupportingText("${plural(shown.exercises.size, "ejercicio", "ejercicios")} · ${plural(shown.exercises.sumOf { it.sets.size }, "serie", "series")}")
                    PrimaryAction("Entrenar ${shown.name}", { onTrain(week.program.id, week.week.id, shown.id) }, enabled = ready, icon = AppIcons.Play, tall = true)
                }
            }
        } else if (!loaded && home.ready) item {
            val toPlan = home.summary?.weekToPlan
            ContentCard {
                if (home.summary?.cycleComplete == true) {
                    Text("Genera tu siguiente plan de 12 semanas", style = MaterialTheme.typography.titleLarge)
                    SupportingText("El agente evalúa el ciclo anterior con tus entrenamientos y mediciones. Puedes responder sus preguntas y revisar el plan antes de activarlo.")
                    PrimaryAction("Generar nuevo plan de 12 semanas", { onLink(HomeLink.Generate) }, icon = AppIcons.Agent)
                } else if (toPlan != null && (home.summary.outline != null || home.summary.program != null)) {
                    Eyebrow("SEMANA $toPlan")
                    Text("Semana $toPlan sin planificar", style = MaterialTheme.typography.titleLarge)
                    // In-app generation follows the active plan; a program without one still goes through an external agent.
                    if (home.summary.outline != null) {
                        SupportingText("Todas las semanas guardadas tienen registros. Genera la semana $toPlan aquí, o prepara el contexto para un agente externo.")
                        PrimaryAction("Generar siguiente semana", { onLink(HomeLink.Generate) }, icon = AppIcons.Agent)
                        QuietAction("Preparar contexto para un agente externo", { onLink(HomeLink.Exchange) }, icon = AppIcons.Swap, fill = true)
                    } else {
                        SupportingText("Todas las semanas guardadas tienen registros. Prepara el contexto y pide la semana $toPlan al agente.")
                        PrimaryAction("Preparar contexto para el agente", { onLink(HomeLink.Exchange) }, icon = AppIcons.Agent)
                    }
                } else {
                    Text("Aún no hay programa", style = MaterialTheme.typography.titleLarge)
                    SupportingText("Importa un programa en Plan, o activa un plan de varias semanas y pide la semana 1 al agente.")
                    PrimaryAction("Ir a Plan", { onLink(HomeLink.Plan) }, icon = AppIcons.Calendar)
                }
            }
        }
        if (week != null) item { WeekStrip(week.days, shown?.id, week.next?.id, enabled = !loaded) { chosen = it } }
        val pending = buildList {
            if (home.summary?.cycleComplete == true)
                add(Triple("Generar nuevo plan de 12 semanas", AppIcons.Agent, HomeLink.Generate))
            if (home.pendingProposals > 0) add(Triple(plural(home.pendingProposals, "propuesta por revisar", "propuestas por revisar"), AppIcons.Bolt, HomeLink.Proposals))
            if (home.openQuestions > 0) add(Triple(plural(home.openQuestions, "pregunta del agente sin responder", "preguntas del agente sin responder"), AppIcons.Question, HomeLink.Questions))
            if (!loaded && week == null && home.summary?.weekToPlan != null && home.summary.program != null)
                add(if (home.summary.outline != null) Triple("Generar la semana ${home.summary.weekToPlan} con el agente", AppIcons.Agent, HomeLink.Generate)
                    else Triple("Enviar contexto al agente para la semana ${home.summary.weekToPlan}", AppIcons.Swap, HomeLink.Exchange))
            val days = home.daysSinceMeasurement
            if (home.ready && (days == null || days >= MEASUREMENT_REMINDER_DAYS))
                add(Triple(if (days == null) "Registra tu primera medición corporal" else "Toca medir: última medición hace $days días", AppIcons.Scale, HomeLink.Body))
        }
        if (pending.isNotEmpty()) {
            item { SectionHeader("Pendiente") }
            pending.forEach { (text, icon, link) -> item { NavRow(text, { onLink(link) }, icon = icon) } }
        }
        home.progress?.let { p ->
            item { SectionHeader("Esta semana") }
            item {
                MetricRow(listOf(
                    (p.sessionsGoal?.let { "${p.sessions} / $it" } ?: "${p.sessions}") to "sesiones",
                    (p.cardioGoal?.let { "${p.cardioMinutes} / $it" } ?: "${p.cardioMinutes}") to "min de cardio"))
            }
        }
    }
}

@Composable
private fun SessionCard(state: WorkoutState, ready: Boolean, onSession: () -> Unit) {
    val source = state.source
    val plan = state.activePlan()
    ContentCard {
        Eyebrow(if (source == null) "DEMO EN CURSO" else "SESIÓN EN CURSO")
        Text(source?.dayName ?: "Demo", style = MaterialTheme.typography.titleLarge)
        source?.let { SupportingText("${it.programName} · ${it.weekName}") }
        SupportingText("Serie ${(state.index + 1).coerceAtMost(plan.size)} de ${plan.size}" +
            if (state.phase == Phase.Resting) " · descansando" else "")
        PrimaryAction("Volver a la sesión", onSession, enabled = ready, icon = AppIcons.Play, tall = true)
    }
}

/** The current week's days in order; status is spelled out, never shown by colour alone. Tapping picks the day. */
@Composable
private fun WeekStrip(days: List<DayAdherence>, shown: String?, next: String?, enabled: Boolean, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        days.forEach { d ->
            val selected = d.day.id == shown
            val status = when (d.status) {
                DayStatus.COMPLETED -> "Hecho"; DayStatus.PARTIAL -> if (d.inProgress) "En curso" else "Parcial"
                DayStatus.NOT_PERFORMED -> "No realizado"; DayStatus.NO_RECORD -> if (d.day.id == next) "Siguiente" else "Pendiente"
            }
            Surface(onClick = { onPick(d.day.id) }, enabled = enabled,
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp)
                    .semantics { role = Role.Tab; this.selected = selected },
                shape = MaterialTheme.shapes.medium,
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
                Column(Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.sm), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text(d.day.name, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2)
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}

private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
