package com.example.workoutpoc

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.migration.MigrationConversion
import com.example.workoutpoc.migration.ObsidianParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** ENT-03 follow-up through the real screen: a reviewed saved migration is converted into a program week. */
@RunWith(AndroidJUnit4::class)
class MigrationConversionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)
    private val programId = "obsidian-programa-ficticio-de-conversion"
    private var existed = false

    @Before fun prepare() {
        // Earlier tests may leave the floating workout panel up; it intercepts taps over this screen.
        app.stopService(Intent(app, com.example.workoutpoc.overlay.WorkoutOverlayService::class.java))
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        // Its own program name and title, so it never collides with other migration tests.
        val text = app.assets.open("examples/obsidian-week-v3-w2.md").use { it.readBytes().decodeToString() }
            .replace("program: Programa ficticio Faro", "program: Programa ficticio de conversión")
            .replace("# Semana 2 — Bloque 1 (ejemplo ficticio)", "# Semana de conversión ficticia")
        val document = ObsidianParser.parse(text, "conversion-device.md")
        runBlocking {
            app.migrations.restore(); app.programs.restore()
            app.migrations.save(document)
            document.issues.forEach { app.migrations.acknowledge(document.id, it.id, true) }
            existed = app.programs.library.value.programs.any { it.id == programId }
        }
        assertEquals(programId, MigrationConversion.convert(app.migrations.archive.value.documents.single { it.id == document.id }).program?.id)
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertNotNull(device.wait(Until.findObject(By.text("Programas")), 10_000))
    }

    @Test fun reviewed_migration_becomes_a_program_week() {
        device.clickText("Programas")
        device.clickText("Migrar desde Obsidian")
        val entry = device.scrollToText("Semana de conversión ficticia · 0 pendientes", contains = true)
        assertNotNull(entry)
        entry!!.visibleBounds.let { device.click(it.centerX(), it.centerY()) }
        device.clickText("Convertir en programa")
        assertNotNull(device.scrollToText("Programa ficticio de conversión · Bloque 1 · Semana 2 · 2 días · 4 ejercicios"))
        assertNotNull(device.scrollToText("no exacta o sin unidad", contains = true))
        device.clickText("Crear o ampliar programa")
        val end = SystemClock.elapsedRealtime() + 8_000
        while (app.programs.library.value.programs.none { it.id == programId } && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        val program = app.programs.library.value.programs.single { it.id == programId }
        assertEquals(listOf("Semana 2"), program.blocks.single().weeks.map { it.name })
        assertNotNull(device.scrollToText(if (existed) "ya estaba convertida" else "Programa «Programa ficticio de conversión» creado", contains = true))
        device.pressHome()
    }
}
