package com.example.workoutpoc.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.domain.MAX_NOTE_LENGTH
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.ui.*
import com.example.workoutpoc.ui.theme.Spacing

/**
 * Comment written after a planned session (contract §7). It is saved for the program and the week of the session; a
 * comment can be general or tied to one exercise of the session.
 */
@Composable
fun SessionCommentCard(state: WorkoutState, onSave: (text: String, exerciseId: String?) -> Unit) {
    val options = remember(state.plan) { state.plan.mapNotNull { p -> p.exerciseId?.let { it to p.exercise } }.distinct() }
    var text by remember(state.sessionId) { mutableStateOf("") }
    // Index into options; null means a general comment for the whole session.
    var target by remember(state.sessionId) { mutableStateOf<Int?>(null) }
    var saved by remember(state.sessionId) { mutableStateOf(false) }
    FocusCard {
        Text("Comentario para el agente", style = MaterialTheme.typography.titleMedium)
        RadioRow("General de la sesión", selected = target == null) { target = null }
        options.forEachIndexed { i, (_, name) -> RadioRow(name, selected = target == i) { target = i } }
        OutlinedTextField(text, { if (it.length <= MAX_NOTE_LENGTH) { text = it; saved = false } },
            label = { Text("Qué quieres que tenga en cuenta") }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
        Button(enabled = text.isNotBlank(), onClick = {
            onSave(text, target?.let { options[it].first })
            text = ""; saved = true
        }) { Text("Guardar comentario") }
        if (saved) SupportingText("Comentario guardado. Se incluirá en las próximas generaciones mientras siga vigente.", small = true)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(Spacing.sm))
        Text(label)
    }
}
