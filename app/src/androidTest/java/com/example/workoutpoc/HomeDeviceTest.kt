package com.example.workoutpoc

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.workoutpoc.domain.Phase
import com.example.workoutpoc.domain.WorkoutEvent
import com.example.workoutpoc.domain.command
import com.example.workoutpoc.planning.ProgramCodec
import com.example.workoutpoc.planning.findDay
import com.example.workoutpoc.planning.toPlannedSets
import com.example.workoutpoc.settings.LoggingMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Hoy suggests the next day in order and trains it with one tap, in the default logging mode. */
@RunWith(AndroidJUnit4::class)
class HomeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = "com.example.workoutpoc"

    @Before fun prepare() {
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard"); device.pressHome()
        device.executeShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        // Its own synthetic program; the latest planned session makes it the program Hoy is about.
        val program = ProgramCodec.decode(app.assets.open("examples/variable-program-v1.json").use { it.readBytes().decodeToString() }
            .replace("fictional-program", "home-device-program").replace("Programa ficticio variable", "Programa ficticio de Hoy"))
        runBlocking {
            app.programs.restore(); app.programs.importProgram(program)
            app.settings.restore(); app.settings.setLoggingMode(LoggingMode.NOTIFICATION)
            app.outlines.restore()
            val repo = app.repository
            repo.restore(); repo.dispatch(repo.state.value.command(WorkoutEvent.ResetDemo))
            // Day A done (one set), then closed: archived, so Day B is next.
            val a = app.programs.library.value.programs.single { it.id == "home-device-program" }.findDay("week-one", "day-one-a")!!
            repo.load(a.day.toPlannedSets(), a.source)
            repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
            repo.dispatch(repo.state.value.command(WorkoutEvent.CompleteSet))
            repo.dispatch(repo.state.value.command(WorkoutEvent.FinishWorkout))
            repo.dispatch(repo.state.value.command(WorkoutEvent.ResetDemo))
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    @After fun reset() {
        runBlocking { app.repository.dispatch(app.repository.state.value.command(WorkoutEvent.ResetDemo)) }
        device.pressHome()
    }

    @Test fun next_day_is_suggested_and_trained_with_one_tap() {
        device.awaitHome()
        org.junit.Assume.assumeTrue("Another plan is active on this device; Hoy follows it", app.outlines.outlines.value.active.isEmpty())
        assertNotNull(device.scrollToText("Siguiente: Día B"))
        assertNotNull(device.scrollToText("Programa ficticio de Hoy · Semana 1"))
        device.clickText("Entrenar Día B")
        val end = SystemClock.elapsedRealtime() + 6_000
        while (SystemClock.elapsedRealtime() < end && app.repository.state.value.let { it.phase != Phase.ActiveSet || it.source?.dayId != "day-one-b" })
            SystemClock.sleep(50)
        val s = app.repository.state.value
        if (s.phase != Phase.ActiveSet || s.source?.dayId != "day-one-b") fail("Day B did not start: $s")
        assertTrue(device.wait(Until.hasObject(By.text("SESIÓN PLANIFICADA")), 5_000))
        // Leaving the session keeps it running; Hoy leads back to it.
        device.pressBack()
        assertNotNull(device.scrollToText("Volver a la sesión"))
    }
}
