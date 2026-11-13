package com.example.workoutpoc

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.workoutpoc.domain.SessionComment
import com.example.workoutpoc.planning.*
import com.example.workoutpoc.proposal.WeekApprovalSection
import com.example.workoutpoc.proposal.buildReviewedWeek
import com.example.workoutpoc.ui.theme.AutoGymTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Requirement 2: real Compose rendering of repeated exercises, anchored comments and a cardio-only day. */
@RunWith(AndroidJUnit4::class)
class WeekApprovalSurfaceDeviceTest {
    @Test fun completeWeekShowsBothExerciseOccurrencesAndTheirOwnComments() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as WorkoutApplication
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        val work = SetPrescription("a-set", 8, 2, PrescribedLoad(30.0, LoadUnit.KG, LoadBasis.TOTAL), 75)
        val approach = SetPrescription("b-set", 12, 1, PrescribedLoad(20.0, LoadUnit.KG, LoadBasis.TOTAL), 60, SetKind.APPROACH)
        val week = WeekPlan("review-week", "Semana de revisión", 1, listOf(
            SessionPlan("day-a", "Día A", listOf(ExercisePrescription("a-row", "row", "Remo", listOf(work)))),
            SessionPlan("day-b", "Día B", listOf(ExercisePrescription("b-row", "row", "Remo", listOf(approach)))),
            SessionPlan("day-d", "Día D · solo cardio", emptyList(), CardioPrescription("Bicicleta", 1800))))
        val comments = listOf(
            SessionComment("c-day", "Día de técnica", 1, "program", dayId = "day-a"),
            SessionComment("c-ex", "Ajustar agarre en B", 2, "program", exerciseId = "row", dayId = "day-b"),
            SessionComment("c-set", "Mantener esta aproximación", 3, "program", exerciseId = "row", setIndex = 0, dayId = "day-b"))
        val reviewed = buildReviewedWeek(week, "program", comments, emptyList())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    AutoGymTheme {
                        Surface {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) { WeekApprovalSection(reviewed) }
                        }
                    }
                }
            }
            assertNotNull(device.wait(Until.findObject(By.text("Semana completa")), 6_000))
            freshTree()
            val dayA = device.findObject(By.text("Día A"))!!
            val commentA = device.findObject(By.text("Comentario del día: Día de técnica"))!!
            val dayB = device.findObject(By.text("Día B"))!!
            val commentB = device.findObject(By.text("Comentario: Ajustar agarre en B"))!!
            val commentSet = device.findObject(By.text("Comentario de la serie: Mantener esta aproximación"))!!
            val dayD = device.findObject(By.text("Día D · solo cardio"))!!
            assertTrue(dayA.visibleBounds.top < commentA.visibleBounds.top)
            assertTrue(commentA.visibleBounds.top < dayB.visibleBounds.top)
            assertTrue(dayB.visibleBounds.top < commentB.visibleBounds.top)
            assertTrue(commentB.visibleBounds.top < commentSet.visibleBounds.top)
            assertTrue(commentSet.visibleBounds.top < dayD.visibleBounds.top)
            assertEquals(2, device.findObjects(By.text("Remo")).size)
            assertEquals(1, device.findObjects(By.text("Comentario: Ajustar agarre en B")).size)
            assertTrue(device.findObject(By.textStartsWith("Serie 1 · Trabajo"))!!.text.contains("RIR 2"))
            assertFalse(device.findObject(By.textStartsWith("Serie 1 · Aproximación"))!!.text.contains("RIR"))
            assertNotNull(device.findObject(By.text("Cardio · Bicicleta · 30 min")))
            val image = File(app.getExternalFilesDir(null), "approval-review.png")
            assertTrue(device.takeScreenshot(image))
            device.executeShellCommand("cp ${image.absolutePath} /sdcard/Download/codex-week-approval.png")
        }
    }
}
