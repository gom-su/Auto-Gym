package com.example.workoutpoc.wearapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Picker
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.rememberPickerState
import com.example.workoutpoc.wearprotocol.WatchLoadUnit
import com.example.workoutpoc.wearprotocol.WatchSet

internal val loadSteps = listOf(0, 5, 10, 25, 50)

/** Retain the starting load even if it is off-grid, and expose both limits. */
internal class LoadOptions(current: Int, private val step: Int, private val maximum: Int) {
    private val firstInterior = (current % step).takeUnless { it == 0 } ?: step
    private val interiorCount = if (firstInterior < maximum) (maximum - 1 - firstInterior) / step + 1 else 0
    val size = if (maximum == 0) 1 else interiorCount + 2
    val selectedIndex = when (current) {
        0 -> 0
        maximum -> size - 1
        else -> 1 + (current - firstInterior) / step
    }

    operator fun get(index: Int): Int = when (index) {
        0 -> 0
        size - 1 -> maximum
        else -> firstInterior + (index - 1) * step
    }
}

@Composable
internal fun WatchNumberPicker(
    title: String,
    subtitle: String?,
    count: Int,
    initial: Int,
    enabled: Boolean,
    confirmLabel: String,
    label: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    val state = rememberPickerState(count, initial, shouldRepeatOptions = false)
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    SelectorLayout {
        WatchHeading(title, subtitle)
        Picker(
            state = state,
            contentDescription = { "$title · ${label(state.selectedOptionIndex)}" },
            modifier = Modifier.fillMaxWidth(0.78f).height(80.dp).testTag("number_picker").focusRequester(focus),
            gradientColor = watchColors.background,
        ) { option ->
            val selected = option == state.selectedOptionIndex
            Text(
                label(option),
                modifier = Modifier.fillMaxWidth().height(35.dp).padding(top = 4.dp),
                color = if (selected) watchColors.primary else watchColors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
        FaceButton(confirmLabel, enabled && !state.isScrollInProgress, Modifier.fillMaxWidth(0.58f)) {
            onSelect(state.selectedOptionIndex)
        }
    }
}

@Composable
internal fun WatchRirPicker(suggested: Int?, enabled: Boolean, onSelect: (Int) -> Unit) {
    SelectorLayout {
        WatchHeading("Confirma el RIR", "Repeticiones en reserva")
        listOf(0..2, 3..4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { option ->
                    val label = rirText(option)
                    FaceButton(
                        label, enabled,
                        Modifier.width(46.dp).semantics { selected = option == suggested; contentDescription = "RIR $label" },
                        primary = option == suggested,
                        onClick = { onSelect(option) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectorLayout(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(watchColors.background).verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) { content() }
}

internal fun rirText(rir: Int?): String = when (rir) {
    null -> "—"
    4 -> "4+"
    else -> rir.toString()
}

internal fun WatchSet.loadStepLabel(tenths: Int): String = when (loadUnit) {
    WatchLoadUnit.MACHINE_SETTING -> tenthsLabel(tenths)
    WatchLoadUnit.LB -> "${tenthsLabel(tenths)} lb"
    else -> "${tenthsLabel(tenths)} kg"
}

internal fun WatchSet.loadLabel(tenths: Int): String = when (loadUnit) {
    WatchLoadUnit.MACHINE_SETTING -> "Posición ${tenthsLabel(tenths)}"
    WatchLoadUnit.BODYWEIGHT -> if (tenths == 0) "Peso corporal" else "PC + ${loadStepLabel(tenths)}"
    else -> loadStepLabel(tenths)
}

internal fun WatchSet.loadCaption(tenths: Int): String = when (loadUnit) {
    WatchLoadUnit.KG -> "kg"
    WatchLoadUnit.LB -> "lb"
    WatchLoadUnit.MACHINE_SETTING -> "posición"
    WatchLoadUnit.BODYWEIGHT -> if (tenths == 0) "peso corporal" else "kg extra"
}

internal fun tenthsLabel(tenths: Int): String =
    if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10},${tenths % 10}"
