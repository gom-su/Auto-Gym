package com.example.workoutpoc.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.workoutpoc.ui.AppIcons
import com.example.workoutpoc.ui.ChipGroup
import com.example.workoutpoc.ui.ChoiceChip

/** The four sections of the app, always one tap away in the bottom bar. */
enum class Tab(val label: String, val icon: ImageVector) {
    Hoy("Hoy", AppIcons.Home), Plan("Plan", AppIcons.Calendar), Progreso("Progreso", AppIcons.Chart), Agente("Agente", AppIcons.Agent)
}

/** Screens opened over the tabs. The bottom bar is hidden while one is open; Back closes it. */
enum class Destination { Session, Settings, Plans, Proposals, Questions, Exchange, Migration, Stagnation, Generate }

/** What the Progreso tab shows; switched with chips at the top of the tab. */
enum class ProgressSection(val label: String) {
    Ejercicios("Ejercicios"), Sesiones("Sesiones"), Adherencia("Adherencia"), Cuerpo("Cuerpo")
}

/**
 * Where the user is: the selected tab, the screens opened over it (last is on top) and the Progreso section.
 * Saved across rotation and process death as plain names.
 */
@Stable
class AppNavigator(tab: Tab, stack: List<Destination>, section: ProgressSection) {
    var tab by mutableStateOf(tab); private set
    var stack by mutableStateOf(stack); private set
    var section by mutableStateOf(section)
    val top: Destination? get() = stack.lastOrNull()
    /** False only on Hoy with nothing open: Back then leaves the app. */
    val canGoBack: Boolean get() = stack.isNotEmpty() || tab != Tab.Hoy

    fun select(tab: Tab) { this.tab = tab; stack = emptyList() }

    /** Opens [destination] on top; opening one already in the stack brings it to the top instead of duplicating it. */
    fun open(destination: Destination) { stack = stack.filter { it != destination } + destination }

    /** The session always sits directly over Hoy, so leaving it lands on Hoy while it keeps running. */
    fun openSession() { tab = Tab.Hoy; stack = listOf(Destination.Session) }

    fun back() {
        when {
            stack.isNotEmpty() -> stack = stack.dropLast(1)
            tab != Tab.Hoy -> tab = Tab.Hoy
        }
    }

    /** Where Back from the top screen goes, for the Up arrow's label. */
    fun backLabel(): String = "Volver a " + when (val below = stack.dropLast(1).lastOrNull()) {
        null -> tab.label
        else -> below.title
    }

    companion object {
        val Saver = listSaver<AppNavigator, String>(
            save = { listOf(it.tab.name, it.section.name) + it.stack.map { d -> d.name } },
            // Names saved by an older version (a removed section or screen) fall back instead of failing to restore.
            restore = { AppNavigator(Tab.valueOf(it[0]), it.drop(2).mapNotNull { d -> Destination.entries.find { e -> e.name == d } },
                ProgressSection.entries.find { s -> s.name == it[1] } ?: ProgressSection.Ejercicios) })
    }
}

val Destination.title: String get() = when (this) {
    Destination.Session -> "Sesión"; Destination.Settings -> "Ajustes"; Destination.Plans -> "Planes de periodización"
    Destination.Proposals -> "Propuestas de semana"; Destination.Questions -> "Preguntas de planificación"
    Destination.Exchange -> "Intercambio JSON"; Destination.Migration -> "Migrar desde Obsidian"
    Destination.Stagnation -> "Señales de estancamiento"; Destination.Generate -> "Generar siguiente semana"
}

@Composable
fun rememberAppNavigator(): AppNavigator =
    rememberSaveable(saver = AppNavigator.Saver) { AppNavigator(Tab.Hoy, emptyList(), ProgressSection.Ejercicios) }

@Composable
fun AppBottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar {
        Tab.entries.forEach { tab ->
            NavigationBarItem(selected = tab == selected, onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) }, label = { Text(tab.label) })
        }
    }
}

/** The gear every tab root shows in its top bar. */
@Composable
fun SettingsAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(AppIcons.Settings, contentDescription = "Ajustes") }
}

@Composable
fun ProgressSections(selected: ProgressSection, onSelect: (ProgressSection) -> Unit) {
    ChipGroup(null) {
        ProgressSection.entries.forEach { section ->
            ChoiceChip(section == selected, { onSelect(section) }, { Text(section.label) })
        }
    }
}
