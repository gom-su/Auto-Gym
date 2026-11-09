package com.example.workoutpoc

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.data.WorkoutSerializer
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.planning.ProgramCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ENT-04 acceptance on a real WindowManager overlay: a saved program day is trained from Programas, and a set
 * without deviations is recorded with one overlay tap and is already on disk when the tap is reflected.
 */
@RunWith(AndroidJUnit4::class)
class PlannedSessionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = "com.example.workoutpoc"

    @Before fun prepare() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.pressHome()
        device.executeShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        // Synthetic fixture only; never touches a user's programs.
        val text = app.assets.open("examples/variable-program-v1.json").use { it.readBytes().decodeToString() }
            // Same synthetic identity as ProgramDeviceTest: importing it again is idempotent and adds no entry.
            .replace("fictional-program", "device-test-program")
        runBlocking {
            app.programs.restore()
            app.programs.importProgram(ProgramCodec.decode(text))
            app.repository.restore()
            app.repository.dispatch(app.repository.state.value.command(WorkoutEvent.ResetDemo))
        }
    }

    /** Leave the demo idle so later tests see neither a planned session nor a running rest timer. */
    @After fun reset() {
        runBlocking { app.repository.dispatch(app.repository.state.value.command(WorkoutEvent.ResetDemo)) }
        device.pressHome()
    }

    private fun find(selector: BySelector, what: String): UiObject2 =
        device.wait(Until.findObject(selector), 6_000) ?: run { fail("Missing: $what"); throw AssertionError() }

    private fun scrollTo(text: String): UiObject2 = device.scrollToText(text)

    private fun awaitState(message: String, condition: (WorkoutState) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 6_000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition(app.repository.state.value)) { device.waitForIdle(); return }
            SystemClock.sleep(50)
        }
        fail("$message: ${app.repository.state.value}")
    }

    @Test fun planned_day_set_is_recorded_from_overlay_with_one_tap_and_persisted() {
        device.executeShellCommand("cmd statusbar collapse")
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        device.awaitHome(); device.openTab("Plan")
        scrollTo("Programa ficticio variable").click()
        scrollTo("Semana 1").click()
        scrollTo("Día A").click()
        scrollTo("Entrenar este día").click()
        find(By.text("SESIÓN PLANIFICADA"), "planned session header")
        awaitState("Day loaded") { it.phase == Phase.Idle && it.source?.dayId == "day-one-a" }

        scrollTo("Iniciar — Burbuja flotante").click()
        awaitState("Started") { it.phase == Phase.ActiveSet }
        find(By.res(pkg, "overlay_title").text("Sentadilla ficticia"), "overlay exercise")
        find(By.res(pkg, "overlay_weight").text("20 kg"), "prefilled load")
        find(By.res(pkg, "overlay_reps").text("8 reps"), "prefilled reps")
        device.pressHome()
        device.waitForIdle()

        val before = app.repository.state.value
        find(By.res(pkg, "overlay_complete"), "complete").click() // the single tap
        awaitState("Recorded") { it.records.size == 1 }
        val record = app.repository.state.value.records.single()
        assertEquals(SetValues(200, 8, 3), record.values)
        assertEquals("set-one-squat-1", record.prescriptionId)
        assertEquals(before.revision + 1, app.repository.state.value.revision)

        // Already durable: the DataStore file holds the record, read independently of the in-memory state.
        val onDisk = runBlocking { WorkoutSerializer.readFrom(File(app.filesDir, "datastore/workout.json").inputStream()) }
        assertEquals(record, onDisk.records.single())
        assertEquals("day-one-a", onDisk.source!!.dayId)
        find(By.res(pkg, "overlay_title").text("Descanso"), "rest shown")
        find(By.res(pkg, "close"), "close overlay").click()
    }

    @Test fun overlay_postpones_the_first_set_of_an_exercise_only() {
        device.executeShellCommand("cmd statusbar collapse")
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        device.awaitHome(); device.openTab("Plan")
        scrollTo("Programa ficticio variable").click()
        scrollTo("Semana 2").click()
        scrollTo("Día C").click() // squat ×1, then press ×3
        scrollTo("Entrenar este día").click()
        awaitState("Day loaded") { it.phase == Phase.Idle && it.source?.dayId == "day-two-c" }
        scrollTo("Iniciar — Burbuja flotante").click()
        awaitState("Started") { it.phase == Phase.ActiveSet }
        find(By.res(pkg, "overlay_title").text("Sentadilla ficticia"), "overlay exercise")
        device.pressHome()
        device.waitForIdle()

        find(By.res(pkg, "overlay_postpone"), "postpone button").click()
        awaitState("Postponed") { it.plan.first().prescriptionId == "set-two-press-1" && it.plan.last().prescriptionId == "set-two-squat-1" }
        find(By.res(pkg, "overlay_title").text("Press ficticio"), "next exercise shown")
        val onDisk = runBlocking { WorkoutSerializer.readFrom(File(app.filesDir, "datastore/workout.json").inputStream()) }
        assertEquals(app.repository.state.value, onDisk)

        find(By.res(pkg, "overlay_complete"), "complete").click()
        awaitState("Press 1 recorded") { it.phase == Phase.Resting && it.records.size == 1 }
        find(By.res(pkg, "overlay_skip"), "skip rest").click()
        awaitState("Press 2 active") { it.phase == Phase.ActiveSet && it.index == 1 }
        find(By.res(pkg, "overlay_title").text("Press ficticio"), "press 2 shown")
        assertFalse("Postpone is only for an exercise's first set", device.hasObject(By.res(pkg, "overlay_postpone")))
        find(By.res(pkg, "close"), "close overlay").click()
    }
}
