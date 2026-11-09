package com.example.workoutpoc

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.planning.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** ANA-04 through the real UI: statuses are shown separately, a day can be declared not performed, goals persist. */
@RunWith(AndroidJUnit4::class)
class AdherenceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)
    private val programId = "adherence-device-program-v2"

    @Before fun prepare() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        // Its own synthetic program, so marks never affect other tests.
        val program = ProgramCodec.decode(app.assets.open("examples/variable-program-v1.json").use { it.readBytes().decodeToString() }
            .replace("fictional-program", programId).replace("Programa ficticio variable", "Programa ficticio de adherencia").replace("\"Semana 3\"", "\"Semana 3 adherencia\""))
        runBlocking {
            app.programs.restore(); app.programs.importProgram(program)
            app.weekAnnotations.restore()
            app.weekAnnotations.setNotPerformed(DayRef(programId, "week-three", "day-three-a"), false)
            app.weekAnnotations.setGoals(Goals())
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.awaitHome()
    }

    @Test fun not_performed_is_explicit_and_goals_persist() {
        device.openTab("Progreso")
        device.clickText("Adherencia")
        device.scrollToText("Programa ficticio de adherencia · Semana 3 adherencia")
        device.clickText("Marcar Semana 3 adherencia · Día A como no realizado")
        val end = SystemClock.elapsedRealtime() + 6_000
        while (!app.weekAnnotations.annotations.value.isNotPerformed(programId, "week-three", "day-three-a") && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue(app.weekAnnotations.annotations.value.isNotPerformed(programId, "week-three", "day-three-a"))
        assertNotNull(device.scrollToText("Quitar «no realizado» de Semana 3 adherencia · Día A"))

        val fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), 5_000)
            ?: run { device.scrollToText("Objetivos semanales"); device.findObjects(By.clazz("android.widget.EditText")) }
        assertTrue(fields.size >= 2)
        fields[0].text = "3"; fields[1].text = "90"
        SystemClock.sleep(600)
        if (device.hasObject(By.pkg("com.google.android.inputmethod.latin"))) { device.pressBack(); device.waitForIdle() }
        device.clickText("Guardar objetivos")
        val end2 = SystemClock.elapsedRealtime() + 6_000
        while (app.weekAnnotations.annotations.value.goals != Goals(3, 90) && SystemClock.elapsedRealtime() < end2) SystemClock.sleep(100)
        val saved = runBlocking { File(app.filesDir, "datastore/week-annotations-v1.json").inputStream().use { WeekAnnotationsSerializer.readFrom(it) } }
        assertEquals(Goals(3, 90), saved.goals)
        assertTrue(saved.isNotPerformed(programId, "week-three", "day-three-a"))
        device.pressHome()
    }
}
