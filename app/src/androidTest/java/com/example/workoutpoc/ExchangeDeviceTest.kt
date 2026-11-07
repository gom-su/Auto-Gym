package com.example.workoutpoc

import android.content.Intent
import androidx.datastore.core.DataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExchangeDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(inst)
    // Shared helper: searches both directions and taps by position, robust to Compose node refreshes.
    private fun click(text: String) = device.clickText(text)
    /** Earlier tests may leave the floating workout panel up; it sits over this screen and intercepts taps and drags. */
    @org.junit.Before fun closeOverlay() {
        app.stopService(Intent(app, com.example.workoutpoc.overlay.WorkoutOverlayService::class.java))
        device.waitForIdle()
    }
    /** The system file picker treats slow drags as long presses (selection mode): tap directly, scrolling its own list. */
    private fun pickerClick(text: String) = device.pickerClick(text)
    @org.junit.After fun capture() {
        val file = File(app.getExternalFilesDir(null), "exchange-last-ui.xml")
        device.dumpWindowHierarchy(file)
        device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/exchange-last-ui.xml")
    }
    @Test fun contextPrivacyClipboardAndLocalGeneration() = runBlocking {
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard"); device.executeShellCommand("cmd statusbar collapse")
        val source = app.assets.open("examples/training-context-v1.json").bufferedReader().use { it.readText() }
        val fixture = ExchangeCodec.decode(source)
        val original = fixture.copy(importId = "device-privacy-context", context = fixture.context!!.copy(
            measurements = listOf(BodyMeasurement("fictional-mass", "2026-10-30T12:00:00Z", "mass", 70.5, "KG"))))
        app.exchanges.save(original)
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click("Programas"); click("Intercambio JSON")
        click("TrainingContext · device-privacy-context")
        click("Copiar JSON")
        assertNotNull(device.scrollToText("JSON copiado al portapapeles del sistema."))
        val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)
        val redacted = ExchangeCodec.decode(clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(redacted.context!!.measurements.isEmpty())
        assertNotEquals(original.importId, redacted.importId)
        // The status message above the review pushed it down: read fresh bounds, and confirm the tap took effect.
        device.waitForIdle(); freshTree()
        val checkbox = device.wait(Until.findObject(By.clazz("android.widget.CheckBox")), 5000)
        assertNotNull("Measurement checkbox", checkbox); checkbox!!.click()
        device.waitForIdle(); freshTree()
        assertTrue("Measurement checkbox not checked", device.wait(Until.hasObject(By.clazz("android.widget.CheckBox").checked(true)), 3000))
        click("Copiar JSON")
        device.waitForIdle()
        // Copying is asynchronous: wait until the clipboard holds the new copy rather than the earlier redacted one.
        fun clip() = ExchangeCodec.decode(clipboard.primaryClip!!.getItemAt(0).text.toString())
        val copiedBy = android.os.SystemClock.elapsedRealtime() + 5_000
        while (clip().importId != original.importId && android.os.SystemClock.elapsedRealtime() < copiedBy) android.os.SystemClock.sleep(100)
        val complete = clip()
        assertEquals(original, complete)
        assertEquals(original, app.exchanges.archive.value.packages.single { it.importId == original.importId })
        click("Cerrar revisión")
        click("Preparar contexto local")
        assertTrue(device.wait(Until.hasObject(By.text("Revisar intercambio")), 5000))
        click("Cerrar revisión")
        click("Preparar resultados locales")
        assertTrue(device.wait(Until.hasObject(By.text("Revisar intercambio")), 5000))
        click("Cerrar revisión")
        click("Volver a programas"); click("Volver al entrenamiento")
    }
    @Test fun documentExportReimportIntoCleanStoreAndPreserveSession() = runBlocking {
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard"); device.executeShellCommand("cmd statusbar collapse")
        val p = ProgramCodec.decode(app.assets.open("examples/variable-program-v1.json").bufferedReader().use { it.readText() })
        val data = ExchangePackage(1, ExchangeType.WeekProposal, "device-exchange-proposal", "2026-10-30T12:00:00Z",
            proposal = WeekProposal("proposal-device", 1, p.id, p.blocks[0].id, "Fuente ficticia", p.blocks[0].weeks[0], listOf("Cambio ficticio"), listOf("Razón ficticia")))
        val file = File(app.getExternalFilesDir(null), "ent-02-input.json").apply { writeText(ExchangeCodec.encode(data)) }
        // Remove this test's own earlier synthetic exports so the picker list stays short.
        device.executeShellCommand("ls /sdcard/Download").lines().map { it.trim() }
            .filter { it.startsWith("ent-02-export-") && it.endsWith(".json") }
            .forEach { device.executeShellCommand("rm /sdcard/Download/$it") }
        device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/ent-02-input.json")
        // Index the copied file so the system picker lists it (otherwise it may be missing from Downloads).
        device.executeShellCommand("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/ent-02-input.json")
        android.os.SystemClock.sleep(1_000)
        app.repository.restore(); app.programs.restore()
        val before = app.repository.state.value
        val programs = app.programs.library.value
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        click("Programas"); click("Intercambio JSON"); click("Importar archivo de intercambio")
        device.wait(Until.findObject(By.desc("Show roots")), 3000)?.click()
        pickerClick("Downloads"); pickerClick("ent-02-input.json")
        assertTrue(device.wait(Until.hasObject(By.text("Revisar intercambio")), 5000))
        click("Guardar intercambio")
        assertTrue(device.wait(Until.hasObject(By.textContains("Intercambio guardado")), 5000) || device.hasObject(By.textContains("ya estaba guardado")))
        click("WeekProposal · device-exchange-proposal")
        device.setOrientationLeft(); device.waitForIdle()
        assertTrue(device.wait(Until.hasObject(By.text("Revisar intercambio")), 5000))
        device.setOrientationNatural(); device.unfreezeRotation(); device.waitForIdle()
        click("Exportar archivo JSON")
        val name = device.wait(Until.findObject(By.res("com.google.android.documentsui", "title")), 3000)
            ?: device.wait(Until.findObject(By.clazz("android.widget.EditText")), 3000)
        assertNotNull(name)
        // The save picker may open on a location that cannot hold files (e.g. Recent): choose Downloads explicitly.
        device.wait(Until.findObject(By.desc("Show roots")), 3_000)?.click()
        if (device.wait(Until.hasObject(By.text("Downloads")), 3_000)) pickerClick("Downloads")
        val outputName = "ent-02-export-${System.currentTimeMillis()}.json"
        val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 3000)
        assertNotNull(input); input!!.text = outputName
        val save = device.wait(Until.findObject(By.res("com.google.android.documentsui", "button1")), 3000)
            ?: device.wait(Until.findObject(By.text("SAVE")), 3000)
        assertNotNull(save); save!!.click()
        assertNotNull(device.scrollToText("JSON exportado correctamente."))
        val exportedPath = device.executeShellCommand("find /sdcard/Download -name $outputName").trim()
        assertTrue(exportedPath.isNotEmpty())
        val exported = device.executeShellCommand("cat $exportedPath")
        assertEquals(data, ExchangeCodec.decode(exported))
        // A clean independent DataStore exercises the import side without deleting app/user data.
        val freshFile = File(app.cacheDir, "ent-02-fresh-${System.nanoTime()}.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val fresh = ExchangeRepository(DataStoreFactory.create(ExchangeArchiveSerializer, scope = scope) { freshFile })
            fresh.restore(); assertTrue(fresh.archive.value.packages.isEmpty())
            assertTrue(fresh.save(ExchangeCodec.decode(exported)))
            assertEquals(listOf(data), fresh.archive.value.packages)
            assertFalse(fresh.save(ExchangeCodec.decode(exported)))
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join() }
        assertEquals(before, app.repository.state.value); assertEquals(programs, app.programs.library.value)
        click("Cerrar revisión")
        val edit = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 3000)
        assertNotNull(edit); edit!!.text = "{}"
        click("Revisar intercambio")
        assertTrue(device.wait(Until.hasObject(By.textContains("JSON incompatible")), 5000))
        val evidence = File(app.getExternalFilesDir(null), "ent-02-evidence").apply { mkdirs() }
        device.takeScreenshot(File(evidence, "last-ui.png")); device.dumpWindowHierarchy(File(evidence, "last-ui.xml"))
        click("Volver a programas"); click("Volver al entrenamiento")
    }
}
