package com.example.workoutpoc

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** One Importar in Plan: an agent file pasted there opens Intercambio JSON's review, and nothing is saved by routing. */
@RunWith(AndroidJUnit4::class)
class ImportRoutingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)

    @Before fun prepare() {
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard"); device.pressHome()
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    @After fun leave() { device.pressHome() }

    @Test fun agent_file_pasted_in_plan_opens_the_exchange_review() {
        val plan = app.assets.open("examples/plan-outline-v1.json").use { it.readBytes().decodeToString() }
        kotlinx.coroutines.runBlocking { app.exchanges.restore() }
        val before = app.exchanges.archive.value.packages.size
        device.awaitHome()
        device.openTab("Plan")
        // Importar follows the program list: bring its button, and so the field just above it, into view.
        device.scrollToText("Revisar contenido")
        device.setFieldText(plan)
        device.clickText("Revisar contenido")
        // The save button exists only in the review; it may sit below the fold.
        device.scrollToText("Guardar intercambio")
        assertTrue(device.wait(Until.hasObject(By.desc("Volver a Plan")), 3_000))
        // Routing only opens the review; saving is still the user's explicit step there.
        kotlinx.coroutines.runBlocking { app.exchanges.restore() }
        assertTrue(app.exchanges.archive.value.packages.size == before)
        device.clickText("Cerrar revisión")
        device.clickText("Volver a Plan")
    }
}
