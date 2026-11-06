package com.example.workoutpoc

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.data.WorkoutSerializer
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.planning.ProgramCodec
import com.example.workoutpoc.planning.findDay
import com.example.workoutpoc.planning.toPlannedSets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ENT-06 on the real Activity: warm-up, a recorded set, cardio through the dialog, pause and resume, pause again.
 * It deliberately leaves the synthetic session paused so scripts/verify_session_restore.py can force-stop the app.
 * Later tests reset the session in their own setup.
 */
@RunWith(AndroidJUnit4::class)
class SessionLifecycleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)

    @Before fun prepare() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        val program = ProgramCodec.decode(app.assets.open("examples/variable-program-v1.json").use { it.readBytes().decodeToString() }
            .replace("fictional-program", "device-test-program"))
        runBlocking {
            app.programs.restore(); app.programs.importProgram(program)
            app.repository.restore()
            app.repository.dispatch(app.repository.state.value.command(WorkoutEvent.ResetDemo))
            val day = program.findDay("week-one", "day-one-a")!!
            app.repository.load(day.day.toPlannedSets(), day.source)
            app.repository.dispatch(app.repository.state.value.command(WorkoutEvent.StartWorkout))
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    private fun scrollTo(text: String): UiObject2 {
        if (!device.wait(Until.hasObject(By.textStartsWith(text)), 1_500))
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().textStartsWith(text))
        return device.wait(Until.findObject(By.textStartsWith(text)), 6_000) ?: run { fail("Missing: $text"); throw AssertionError() }
    }
    private fun click(text: String) { scrollTo(text).click(); device.waitForIdle() }

    private fun awaitState(message: String, condition: (WorkoutState) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 6_000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition(app.repository.state.value)) { device.waitForIdle(); return }
            SystemClock.sleep(50)
        }
        fail("$message: ${app.repository.state.value}")
    }

    private fun type(label: String, value: String) {
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText").hasDescendant(By.text(label))), 3_000)
            ?: device.findObjects(By.clazz("android.widget.EditText")).firstOrNull { f -> f.findObject(By.text(label)) != null }
        assertNotNull("Missing field $label", field)
        field!!.text = value
    }

    @Test fun warmup_cardio_pause_and_resume_through_the_ui_keep_everything() {
        click("Añadir aproximación de Sentadilla ficticia")
        awaitState("Warm-up") { it.plan[0].warmup && it.index == 0 }
        click("Completar serie")
        awaitState("Warm-up recorded") { it.records.size == 1 && it.phase == Phase.Resting }

        click("Añadir cardio")
        type("Modalidad", "Bicicleta ficticia")
        type("Duración (min, opcional)", "12")
        type("Distancia (opcional)", "4,5")
        click("Guardar cardio")
        awaitState("Cardio saved") { it.cardio.singleOrNull()?.let { c -> c.modality == "Bicicleta ficticia" && c.durationSeconds == 720 && c.distanceTenths == 45 } == true }

        click("Pausar sesión")
        awaitState("Paused") { it.paused != null }
        assertNotNull(device.wait(Until.findObject(By.text("Sesión en pausa")), 4_000))
        val before = app.repository.state.value
        SystemClock.sleep(1_500)
        click("Reanudar sesión")
        awaitState("Resumed") { it.paused == null && it.pausedMillis >= 1_000 }
        val resumed = app.repository.state.value
        assertEquals(before.records, resumed.records); assertEquals(before.cardio, resumed.cardio)
        assertEquals(resumed.pausedMillis, resumed.rest!!.elapsedMillis - before.rest!!.elapsedMillis)

        click("Pausar sesión")
        awaitState("Paused again") { it.paused != null }
        val onDisk = runBlocking { WorkoutSerializer.readFrom(File(app.filesDir, "datastore/workout.json").inputStream()) }
        assertEquals(app.repository.state.value, onDisk)
        device.pressHome()
    }
}
