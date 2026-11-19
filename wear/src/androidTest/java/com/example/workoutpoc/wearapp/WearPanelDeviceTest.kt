package com.example.workoutpoc.wearapp

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.workoutpoc.wearprotocol.PanelAction
import com.example.workoutpoc.wearprotocol.PanelControl
import com.example.workoutpoc.wearprotocol.PanelMode
import com.example.workoutpoc.wearprotocol.PanelSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WearPanelDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun setControlsScrollIntoViewAndRespectPhoneGating() {
        val snapshot = PanelSnapshot(
            sessionId = "workout",
            index = 1,
            revision = 4,
            mode = PanelMode.SET,
            title = "Remo sentado",
            subtitle = "Trabajo · serie 2/3",
            weight = "35 kg",
            reps = 8,
            completionHint = "Selecciona el RIR",
            controls = listOf(
                PanelControl(PanelAction.COMPLETE_SET, "Completar serie", enabled = false),
                PanelControl(PanelAction.WEIGHT_DOWN, "Reducir peso"),
                PanelControl(PanelAction.WEIGHT_UP, "Aumentar peso"),
                PanelControl(PanelAction.REPS_DOWN, "Reducir repeticiones"),
                PanelControl(PanelAction.REPS_UP, "Aumentar repeticiones")
            ) + (0..4).map { PanelControl(PanelAction.SELECT_RIR, if (it == 4) "4+" else it.toString(), argument = it) }
        )
        val controlCalls = mutableListOf<Pair<PanelControl, PanelSnapshot>>()
        compose.setContent {
            AutoGymWatchPanel(
                state = WearPanelState(snapshot = snapshot, connected = true),
                onAction = { control, displayed -> controlCalls.add(control to displayed) },
                onRefresh = {},
                onLoadSummary = {},
                onClose = {}
            )
        }

        val panel = compose.onNodeWithTag("watch_panel")
        compose.onNodeWithText("Remo sentado").assertIsDisplayed()
        panel.performScrollToNode(hasText("Completar serie"))
        compose.onNodeWithText("Completar serie").assertIsNotEnabled()
        val beforeRotation = panel.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        panel.performRotaryScrollInput { rotateToScrollVertically(80f) }
        compose.waitUntil(2_000) {
            panel.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > beforeRotation
        }
        panel.performScrollToNode(hasContentDescription("RIR 4+"))
        compose.onNodeWithContentDescription("RIR 4+").assertIsDisplayed().performClick()

        compose.runOnIdle {
            assertEquals(snapshot.controls.last() to snapshot, controlCalls.single())
        }
    }

    @Test
    fun disconnectedRestIsReadOnlyAndReconnectRestoresConfirmation() {
        val confirm = PanelControl(PanelAction.REST_OK, "OK")
        val rest = PanelSnapshot(
            sessionId = "workout",
            revision = 8,
            restSegmentId = "one-rest",
            mode = PanelMode.REST,
            title = "Descanso",
            alert = true,
            notice = "Siguiente · Remo",
            controls = listOf(confirm)
        )
        val state = mutableStateOf(WearPanelState(snapshot = rest, connected = false, timerText = "00:00"))
        val controlCalls = mutableListOf<Pair<PanelControl, PanelSnapshot>>()
        compose.setContent {
            AutoGymWatchPanel(
                state = state.value,
                onAction = { control, displayed -> controlCalls.add(control to displayed) },
                onRefresh = {},
                onLoadSummary = {},
                onClose = {}
            )
        }

        val panel = compose.onNodeWithTag("watch_panel")
        panel.performScrollToNode(hasText("OK"))
        compose.onNodeWithText("OK").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(connected = true) }
        panel.performScrollToNode(hasText("OK"))
        compose.onNodeWithText("OK").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(confirm to rest, controlCalls.single()) }
    }
}
