package com.example.workoutpoc.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.workoutpoc.data.activePlan
import com.example.workoutpoc.domain.Phase
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.ui.*

/**
 * The Hoy tab: what to do now. A loaded or running session comes first; with none, the way to pick a day in Plan.
 * The demo is never offered here (it lives in Ajustes).
 */
@Composable
fun HomeScreen(state: WorkoutState, ready: Boolean, error: String?, onRetry: () -> Unit, onSession: () -> Unit, onPlan: () -> Unit) {
    val source = state.source
    val running = state.phase == Phase.ActiveSet || state.phase == Phase.Resting
    // An idle demo is just the default state, not something the user prepared; a running or finished one is shown.
    val loaded = running || source != null || state.phase == Phase.Completed
    AppScreen("Hoy", "", {}, busy = !ready) {
        error?.let { item { Banner(it, tone = Tone.Error, action = { androidx.compose.material3.TextButton(onClick = onRetry) { Text("Reintentar") } }) } }
        if (loaded) item {
            val plan = state.activePlan()
            ContentCard {
                Eyebrow(when {
                    running -> if (source == null) "DEMO EN CURSO" else "SESIÓN EN CURSO"
                    state.phase == Phase.Completed -> if (source == null) "DEMO TERMINADA" else "SESIÓN TERMINADA"
                    else -> "SESIÓN PREPARADA"
                })
                Text(source?.dayName ?: "Demo", style = MaterialTheme.typography.titleLarge)
                source?.let { SupportingText("${it.programName} · ${it.weekName}") }
                if (running) SupportingText("Serie ${(state.index + 1).coerceAtMost(plan.size)} de ${plan.size}" +
                    if (state.phase == Phase.Resting) " · descansando" else "")
                PrimaryAction(when {
                    running -> "Volver a la sesión"
                    state.phase == Phase.Completed -> "Ver resumen"
                    else -> "Abrir sesión"
                }, onSession, enabled = ready, icon = AppIcons.Play, tall = true)
            }
        } else {
            item { EmptyState("No hay ninguna sesión preparada. Elige un día de tu programa para entrenar.", icon = AppIcons.Fitness) }
            item { PrimaryAction("Elegir un día en Plan", onPlan, enabled = ready, icon = AppIcons.Calendar) }
        }
    }
}
