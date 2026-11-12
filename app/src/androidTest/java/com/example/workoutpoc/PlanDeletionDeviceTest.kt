package com.example.workoutpoc

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.workoutpoc.exchange.ExchangeCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Deleting a plan asks first, shows what it removes, and removes the plan only after the confirmation. */
@RunWith(AndroidJUnit4::class)
class PlanDeletionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)
    private val outline = ExchangeCodec.decode(app.assets.open("examples/plan-outline-v1.json").use { it.readBytes().decodeToString() }).outline!!

    @Before fun prepare() {
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard"); device.pressHome()
        runBlocking { app.outlines.restore(); app.outlines.activate(outline, null) }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.awaitHome()
    }

    private fun activeCount(): Int = runBlocking { app.outlines.restore(); app.outlines.outlines.value.active.count { it.programId == outline.programId } }

    @Test fun deleting_the_plan_asks_first_and_removes_it_only_after_confirmation() {
        device.openTab("Plan")
        device.scrollToText("Planes de periodización")
        device.clickText("Planes de periodización")
        device.clickText("${outline.name} v${outline.version}")
        device.scrollToText("Borrar plan y entrenamientos")
        device.clickText("Borrar plan y entrenamientos")
        assertTrue(device.wait(Until.hasObject(By.text("¿Borrar el plan y sus entrenamientos?")), 3_000))

        device.clickText("Cancelar")
        assertTrue("Cancelar must keep the plan", activeCount() == 1)

        device.scrollToText("Borrar plan y entrenamientos")
        device.clickText("Borrar plan y entrenamientos")
        assertTrue(device.wait(Until.hasObject(By.text("¿Borrar el plan y sus entrenamientos?")), 3_000))
        device.clickText("Borrar")
        val end = SystemClock.elapsedRealtime() + 8_000
        while (activeCount() != 0 && SystemClock.elapsedRealtime() < end) SystemClock.sleep(200)
        assertTrue("the plan must be gone after confirmation", activeCount() == 0)
        assertTrue(device.wait(Until.hasObject(By.textContains("borrado")), 3_000))
    }
}
