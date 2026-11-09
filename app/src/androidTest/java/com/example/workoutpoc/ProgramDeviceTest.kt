package com.example.workoutpoc

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.planning.ProgramCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import java.io.File

/** Imports via Android's real document picker and navigates the resulting Compose hierarchy. */
@RunWith(AndroidJUnit4::class)
class ProgramDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)

    @After fun capture() {
        val directory = File(app.getExternalFilesDir(null), "ent-01-evidence").apply { mkdirs() }
        device.takeScreenshot(File(directory, "last-ui.png"))
        device.dumpWindowHierarchy(File(directory, "last-ui.xml"))
        device.executeShellCommand("mkdir -p /sdcard/Download/ent-01-evidence")
        device.executeShellCommand("cp ${directory.absolutePath}/last-ui.xml /sdcard/Download/ent-01-evidence/last-ui.xml")
        device.executeShellCommand("cp ${directory.absolutePath}/last-ui.png /sdcard/Download/ent-01-evidence/last-ui.png")
        device.setOrientationNatural()
        device.unfreezeRotation()
    }

    // Shared helpers: search both directions, re-find after the list settles, retry stale nodes.
    private fun click(text: String) = device.clickText(text)
    /** The system file picker treats slow drags as long presses (selection mode): tap directly, scrolling its own list. */
    private fun pickerClick(text: String) = device.pickerClick(text)

    private fun edit(text: String) {
        var input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 3_000)
        if (input == null) {
            val scroll = UiScrollable(UiSelector().scrollable(true))
            if (scroll.exists()) scroll.scrollToBeginning(20)
            input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        }
        assertNotNull(input)
        // Large texts may not apply on the first attempt: confirm the field holds the intended text before moving on.
        val probe = text.take(20)
        var applied = false
        for (attempt in 0 until 4) {
            try {
                device.findObject(By.clazz("android.widget.EditText"))?.text = text
                val end = android.os.SystemClock.elapsedRealtime() + 3_000
                while (android.os.SystemClock.elapsedRealtime() < end) {
                    val current = try { device.findObject(By.clazz("android.widget.EditText"))?.text } catch (_: StaleObjectException) { null }
                    if (current != null && current.startsWith(probe) && current.length >= minOf(text.length, 4000) / 2) { applied = true; break }
                    android.os.SystemClock.sleep(150)
                }
            } catch (_: StaleObjectException) { }
            if (applied) break
        }
        assertTrue("Text was not applied to the JSON field", applied)
        // The soft keyboard can cover the review button below the field; dismiss it once it has appeared.
        android.os.SystemClock.sleep(600)
        // Check the keyboard window itself: dumpsys can report a hidden keyboard as shown, and Back would then leave the screen.
        if (device.hasObject(By.pkg("com.google.android.inputmethod.latin"))) { device.pressBack(); device.waitForIdle() }
    }

    @Test fun importReviewBrowseCancelConflictAndRecreation() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("cmd statusbar collapse")
        // Synthetic fixtures only; never overwrite a user's existing program.
        val source = app.assets.open("examples/variable-program-v1.json").bufferedReader().use { it.readText() }
        val fixture = source.replace("fictional-program", "device-test-program")
        val file = File(app.getExternalFilesDir(null), "ent-01-device.json").apply { writeText(fixture) }
        device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/ent-01-device.json")
        // Index the copied file so the system picker lists it (after a reboot it can otherwise be missing).
        device.executeShellCommand("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/ent-01-device.json")
        android.os.SystemClock.sleep(1_000)
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.awaitHome(); device.openTab("Plan")
        click("Seleccionar archivo JSON")
        val roots = device.wait(Until.findObject(By.desc("Show roots")), 5_000)
        if (roots != null) roots.click()
        pickerClick("Downloads")
        pickerClick("ent-01-device.json")
        assertTrue(device.wait(Until.hasObject(By.text("Revisar importación")), 8_000))
        click("Semana 1 · 2 días")
        click("Día A · 1 ejercicio · 2 series")
        // More navigation entries precede the review; scroll instead of assuming the day fits above the fold.
        for (text in listOf("Sentadilla ficticia", "Serie 1 · 8 reps · RIR 3")) device.scrollToText(text)
        click("Guardar programa")
        assertTrue(device.wait(Until.hasObject(By.textContains("Programa guardado")), 5_000) ||
            device.hasObject(By.textContains("ya estaba importado")))
        // File import is idempotent and remains separate from the demo session.
        runBlocking { app.programs.restore() }
        assertEquals(1, app.programs.library.value.programs.count { it.id == "device-test-program" })
        val saved = app.programs.library.value.programs.single { it.id == "device-test-program" }
        assertEquals(ProgramCodec.decode(fixture), saved)
        click("Programa ficticio variable")
        click("Semana 2 · 1 día")
        click("Día C · 2 ejercicios · 4 series")
        device.scrollToText("Serie 1 · 5 reps · RIR 4")
        // The day view now leads with a train action; scroll rather than assume the set fits above the fold.
        device.scrollToText("0.0 kg · por lado")
        // Rotation recreates the Activity without discarding navigation or stored associations.
        device.setOrientationLeft()
        device.scrollToText("Día C")
        device.setOrientationNatural()
        device.unfreezeRotation()
        device.waitForIdle()
        click("Ver todos los programas")
        edit("{}")
        click("Revisar JSON")
        assertTrue(device.wait(Until.hasObject(By.textContains("JSON incompatible")), 5_000))
        edit(fixture)
        click("Revisar JSON")
        click("Cancelar importación")
        assertEquals(1, app.programs.library.value.programs.count { it.id == "device-test-program" })
        edit(fixture.replace("Programa ficticio variable", "Conflicto ficticio"))
        click("Revisar JSON")
        click("Guardar programa")
        assertTrue(device.wait(Until.hasObject(By.textContains("contenido diferente")), 5_000))
        assertEquals(saved, app.programs.library.value.programs.single { it.id == "device-test-program" })
        click("Cancelar importación")
        edit(fixture)
        click("Revisar JSON")
        click("Guardar programa")
        assertTrue(device.wait(Until.hasObject(By.textContains("ya estaba importado")), 5_000))
        device.openTab("Hoy")
        assertTrue(device.wait(Until.hasObject(By.desc("Ajustes")), 5_000))
    }
}
