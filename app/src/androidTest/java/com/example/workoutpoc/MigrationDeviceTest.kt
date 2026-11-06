package com.example.workoutpoc

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.migration.ObsidianParser
import com.example.workoutpoc.migration.MigrationCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MigrationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)
    private fun scrollTop() {
        val scroll = UiScrollable(UiSelector().scrollable(true))
        // Compose can remove scroll semantics when the new, shorter page fits the viewport.
        try { if (scroll.exists()) scroll.scrollToBeginning(50) }
        catch (_: UiObjectNotFoundException) { }
    }
    private fun click(text: String) {
        if (!device.wait(Until.hasObject(By.text(text)), 1_000)) {
            scrollTop()
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text))
        }
        val node = device.wait(Until.findObject(By.text(text)), 8_000)
        assertNotNull("Missing: $text", node)
        node!!.click(); device.waitForIdle()
    }
    private fun open() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("cmd statusbar collapse")
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click("Programas"); click("Migrar desde Obsidian")
    }
    private fun pick(name: String) {
        click("Seleccionar archivo Markdown")
        device.wait(Until.findObject(By.desc("Show roots")), 3_000)?.click()
        click("Downloads")
        click(name)
    }
    private fun publish(name: String, bytes: ByteArray) {
        val file = File(app.getExternalFilesDir(null), name).apply { writeBytes(bytes) }
        device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/$name")
        // Markdown can lack a registered MIME mapping; index the synthetic test document explicitly.
        device.executeShellCommand("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/$name")
    }
    @After fun capture() {
        val directory = File(app.getExternalFilesDir(null), "ent-03-evidence").apply { mkdirs() }
        device.takeScreenshot(File(directory, "last-ui.png"))
        device.dumpWindowHierarchy(File(directory, "last-ui.xml"))
        device.executeShellCommand("mkdir -p /sdcard/Download/ent-03-evidence")
        device.executeShellCommand("cp ${directory.absolutePath}/last-ui.png /sdcard/Download/ent-03-evidence/last-ui.png")
        device.executeShellCommand("cp ${directory.absolutePath}/last-ui.xml /sdcard/Download/ent-03-evidence/last-ui.xml")
        device.setOrientationNatural(); device.unfreezeRotation()
    }

    @Test fun fileReviewRotationCancelSaveAcknowledgeAndIdempotency() {
        val fixture = app.assets.open("examples/obsidian-week-v3-w2.md").bufferedReader().use { it.readText() } +
            "\n<!-- synthetic device run ${System.nanoTime()} -->"
        val expected = ObsidianParser.parse(fixture, "ent-03-device.md")
        publish("ent-03-device.md", fixture.toByteArray(Charsets.UTF_8))
        runBlocking { app.migrations.restore(); app.programs.restore(); app.exchanges.restore(); app.repository.restore() }
        val before = app.migrations.archive.value
        val workout = app.repository.state.value
        val programs = app.programs.library.value
        val exchanges = app.exchanges.archive.value
        open()
        click("Seleccionar archivo Markdown"); device.pressBack()
        assertEquals(before, app.migrations.archive.value)
        pick("ent-03-device.md")
        assertTrue(device.wait(Until.hasObject(By.textContains("Revisión sin guardar")), 8_000))
        click("Día A — Fuerza · 3 ejercicios")
        assertTrue(device.wait(Until.hasObject(By.textContains("Press ficticio · línea")), 5_000))
        click("Ver todos los días")
        click("Advertencias (${expected.issues.size})")
        scrollTop()
        val first = expected.issues.first()
        val checkDescription = "Advertencia de la línea ${first.line}: ${first.message}"
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().description(checkDescription))
        val check = device.wait(Until.findObject(By.desc(checkDescription)), 5_000)
        assertNotNull(check); check!!.click(); device.waitForIdle()
        val saveText = "Guardar con pendientes (${expected.issues.size - 1})"
        click("Texto original con líneas")
        device.setOrientationLeft(); device.waitForIdle()
        click(saveText) // reviewed ViewModel survives Activity recreation, including acknowledged issue.
        scrollTop()
        assertTrue(device.wait(Until.hasObject(By.text("Migración guardada en este dispositivo.")), 8_000))
        assertEquals(expected.copy(issues = expected.issues.map { if (it.id == first.id) it.copy(acknowledged = true) else it }),
            app.migrations.archive.value.documents.single { it.id == expected.id })
        device.setOrientationNatural(); device.waitForIdle()
        click("Ver migraciones")
        pick("ent-03-device.md")
        click("Guardar con pendientes (${expected.issues.size})")
        scrollTop()
        assertTrue(device.wait(Until.hasObject(By.textContains("ya estaba guardada")), 8_000))
        assertEquals(1, app.migrations.archive.value.documents.count { it.id == expected.id })
        assertTrue(app.migrations.archive.value.documents.single { it.id == expected.id }.issues.first().acknowledged)
        click("Ver migraciones")
        pick("ent-03-device.md")
        click("Cancelar importación")
        assertEquals(before.documents.size + 1, app.migrations.archive.value.documents.size)
        // Saved review updates use the repository's atomic write, rather than modifying the immutable parser data.
        val pending = expected.issues.size - 1
        click("Importación ${before.documents.size + 1} · ${expected.title} · $pending pendientes")
        click("Advertencias (${expected.issues.size})")
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().description(checkDescription))
        val savedCheckbox = device.wait(Until.findObject(By.desc(checkDescription)), 5_000)
        assertNotNull(savedCheckbox); savedCheckbox!!.click()
        assertTrue(device.wait(Until.hasObject(By.text("Pendiente de revisión")), 5_000))
        runBlocking { app.migrations.restore() }
        assertFalse(app.migrations.archive.value.documents.single { it.id == expected.id }.issues.first().acknowledged)
        assertEquals(workout, app.repository.state.value)
        assertEquals(programs, app.programs.library.value)
        assertEquals(exchanges, app.exchanges.archive.value)
        scrollTop() // screenshot captures the review header and counters, using synthetic data only.
    }

    @Test fun invalidUtf8OversizeAndPastedUnsupportedMarkdownNeverDiscardArchive() {
        publish("ent-03-invalid.md", byteArrayOf(0xc3.toByte(), 0x28))
        publish("ent-03-large.md", ByteArray(MigrationCodec.MAX_BYTES + 1) { 65 })
        runBlocking { app.migrations.restore() }
        val before = app.migrations.archive.value
        open()
        pick("ent-03-invalid.md")
        assertTrue(device.wait(Until.hasObject(By.text("El archivo no es UTF-8 válido.")), 8_000))
        pick("ent-03-large.md")
        assertTrue(device.wait(Until.hasObject(By.text("El archivo supera 1 MiB.")), 8_000))
        scrollTop()
        val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        assertNotNull(input)
        input!!.text = "# Semana antigua\n### Martes\nTexto sin formato Workout v3."
        device.waitForIdle()
        click("Revisar Markdown")
        assertTrue(device.wait(Until.hasObject(By.textContains("Revisión sin guardar")), 8_000))
        click("Advertencias (2)")
        assertTrue(device.wait(Until.hasObject(By.textContains("UNKNOWN_SECTION")), 5_000))
        click("Cancelar importación")
        assertEquals(before, app.migrations.archive.value)
        scrollTop()
    }
}
