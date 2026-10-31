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

    private fun click(text: String) {
        if (!device.wait(Until.hasObject(By.text(text)), 1_000))
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text))
        val node = device.wait(Until.findObject(By.text(text)), 8_000)
        assertNotNull("Missing: $text", node)
        node!!.click()
        device.waitForIdle()
    }

    private fun edit(text: String) {
        var input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 3_000)
        if (input == null) {
            val scroll = UiScrollable(UiSelector().scrollable(true))
            if (scroll.exists()) scroll.scrollToBeginning(20)
            input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        }
        assertNotNull(input)
        input!!.text = text
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
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click("Programas")
        click("Seleccionar archivo JSON")
        val roots = device.wait(Until.findObject(By.desc("Show roots")), 5_000)
        if (roots != null) roots.click()
        click("Downloads")
        click("ent-01-device.json")
        assertTrue(device.wait(Until.hasObject(By.text("Revisar importación")), 8_000))
        click("Semana 1 · 2 días")
        click("Día A · 1 ejercicio · 2 series")
        assertTrue(device.wait(Until.hasObject(By.text("Sentadilla ficticia")), 5_000))
        assertTrue(device.hasObject(By.text("Serie 1 · 8 reps · RIR 3")))
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
        assertTrue(device.wait(Until.hasObject(By.text("Serie 1 · 5 reps · RIR 4")), 5_000))
        assertTrue(device.hasObject(By.text("0.0 kg · por lado")))
        // Rotation recreates the Activity without discarding navigation or stored associations.
        device.setOrientationLeft()
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("Día C"))
        assertTrue(device.wait(Until.hasObject(By.text("Día C")), 5_000))
        device.setOrientationNatural()
        device.unfreezeRotation()
        device.waitForIdle()
        UiScrollable(UiSelector().scrollable(true)).scrollToBeginning(20)
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
        click("Volver al entrenamiento")
        assertTrue(device.wait(Until.hasObject(By.text("Iniciar demo — Notificación")), 5_000))
    }
}
