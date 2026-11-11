package com.example.workoutpoc.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.workoutpoc.ui.theme.Spacing

/** No selection and an empty custom response are unanswered, never an implicit default choice. */
fun questionAnswer(options: List<String>, selected: Int, other: String): String? = when {
    selected in options.indices -> options[selected]
    selected == options.size -> other.trim().ifBlank { null }
    else -> null
}

/** The same four radio choices for predefined and AI questions. The caller owns saveable form state. */
@Composable
fun QuestionAnswerChoices(
    options: List<String>, selected: Int, onSelect: (Int) -> Unit,
    other: String, onOther: (String) -> Unit, enabled: Boolean,
    recommendedFirst: Boolean = false
) {
    require(options.size == 3) { "Cada pregunta debe tener tres opciones y Otra." }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        (options + "Otra").forEachIndexed { index, label ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(selected == index, enabled = enabled, role = Role.RadioButton) { onSelect(index) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RadioButton(selected == index, onClick = null, enabled = enabled)
                Text(if (recommendedFirst && index == 0) "$label (recomendada)" else label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (selected == options.size) OutlinedTextField(
        other, { if (it.length <= 1000) onOther(it) }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text("Otra respuesta") }, supportingText = { Text("Escribe tu respuesta para continuar.") }
    )
}
