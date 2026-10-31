package com.example.workoutpoc

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.exchange.ExchangeCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in acceptance: run only on a separate newly installed test application ID. */
@RunWith(AndroidJUnit4::class)
class ExchangeFreshInstallTest {
    @Test fun exportedFileImportsOnCleanInstallation() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext.applicationContext as WorkoutApplication
        val fileName = InstrumentationRegistry.getArguments().getString("exchangeExportFile")
        assumeTrue(fileName != null && app.packageName.endsWith(".exchangeqa"))
        val device = UiDevice.getInstance(inst)
        val exported = device.executeShellCommand("cat /sdcard/Download/$fileName")
        val expected = ExchangeCodec.decode(exported)
        app.exchanges.restore()
        assertTrue("Installation must start with an empty exchange archive", app.exchanges.archive.value.packages.isEmpty())
        app.programs.restore(); app.repository.restore()
        val programs = app.programs.library.value
        val workout = app.repository.state.value
        fun click(text: String) {
            if (!device.wait(Until.hasObject(By.text(text)), 1000))
                UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text))
            val node = device.wait(Until.findObject(By.text(text)), 8000)
            assertNotNull("Missing: $text", node); node!!.click(); device.waitForIdle()
        }
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard"); device.executeShellCommand("cmd statusbar collapse")
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click("Programas"); click("Intercambio JSON"); click("Importar archivo de intercambio")
        device.wait(Until.findObject(By.desc("Show roots")), 3000)?.click()
        click("Downloads"); click(fileName!!)
        click("Guardar intercambio")
        assertTrue(device.wait(Until.hasObject(By.textContains("Intercambio guardado")), 8000))
        assertEquals(listOf(expected), app.exchanges.archive.value.packages)
        assertEquals(programs, app.programs.library.value); assertEquals(workout, app.repository.state.value)
        val evidence = File(app.getExternalFilesDir(null), "ent-02-fresh-evidence").apply { mkdirs() }
        device.takeScreenshot(File(evidence, "imported.png"))
        File(evidence, "expected.json").writeText(ExchangeCodec.encode(expected))
    }
}
