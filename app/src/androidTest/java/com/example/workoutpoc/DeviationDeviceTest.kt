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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** ENT-05 on the real Activity: omit, add an extra set and edit a recorded set with a note; the prescription stays intact. */
@RunWith(AndroidJUnit4::class)
class DeviationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)

    @Before fun prepare() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        // Same synthetic identity as ProgramDeviceTest: idempotent, adds no library entry.
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
        device.openSessionFromHoy()
    }

    @After fun reset() {
        runBlocking { app.repository.dispatch(app.repository.state.value.command(WorkoutEvent.ResetDemo)) }
        device.pressHome()
    }

    private fun click(text: String) { device.scrollToText(text).click(); device.waitForIdle() }

    private fun awaitState(message: String, condition: (WorkoutState) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 6_000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition(app.repository.state.value)) { device.waitForIdle(); return }
            SystemClock.sleep(50)
        }
        fail("$message: ${app.repository.state.value}")
    }

    @Test fun omit_add_extra_and_edit_with_note_keep_prescription_separate() {
        val prescription = app.repository.state.value.plan
        click("Omitir serie")
        awaitState("Omitted") { it.records.singleOrNull()?.status == SetStatus.SKIPPED && it.index == 1 }
        click("Completar serie")
        awaitState("Completed") { it.phase == Phase.Completed && it.records.size == 2 }
        click("Añadir serie extra de Sentadilla ficticia")
        awaitState("Extra added") { it.phase == Phase.ActiveSet && it.plan.size == 3 && it.plan[2].extra }
        click("Completar serie")
        awaitState("Extra recorded") { it.phase == Phase.Completed && it.records.size == 3 }

        click("Editar serie 2")
        val notes = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        assertNotNull(notes); notes!!.text = "Nota ficticia de dispositivo"
        click("Guardar cambios")
        awaitState("Edited") { it.records[1].notes == "Nota ficticia de dispositivo" && it.records[1].editedAtMillis != null }

        val onDisk = runBlocking { WorkoutSerializer.readFrom(File(app.filesDir, "datastore/workout.json").inputStream()) }
        assertEquals(app.repository.state.value, onDisk)
        assertEquals(listOf(SetStatus.SKIPPED, SetStatus.COMPLETED, SetStatus.COMPLETED), onDisk.records.map { it.status })
        assertNull(onDisk.records[2].prescriptionId)
        assertEquals(prescription, onDisk.plan.take(2))
        assertTrue(device.hasObject(By.textStartsWith("Nota: Nota ficticia")) ||
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().textStartsWith("Nota: Nota ficticia")))
    }

    @Test fun postpone_on_first_set_moves_exercise_after_the_next_and_persists() {
        // Día C: squat ×1, then press ×3. Day A (loaded by prepare) has a single exercise, so it offers no postpone.
        assertFalse(device.hasObject(By.textStartsWith("Posponer")))
        runBlocking {
            val program = app.programs.library.value.programs.first { it.id == "device-test-program" }
            app.repository.dispatch(app.repository.state.value.command(WorkoutEvent.ResetDemo))
            val day = program.findDay("week-two", "day-two-c")!!
            app.repository.load(day.day.toPlannedSets(), day.source)
            app.repository.dispatch(app.repository.state.value.command(WorkoutEvent.StartWorkout))
        }
        click("Posponer Sentadilla ficticia (hacer antes Press ficticio)")
        val order = listOf("set-two-press-1", "set-two-press-2", "set-two-press-3", "set-two-squat-1")
        awaitState("Postponed") { s -> s.plan.map { it.prescriptionId } == order && s.index == 0 && s.records.isEmpty() }
        val onDisk = runBlocking { WorkoutSerializer.readFrom(File(app.filesDir, "datastore/workout.json").inputStream()) }
        assertEquals(app.repository.state.value, onDisk)
        assertEquals(SetValues(30, 12, null), onDisk.values)

        click("Completar serie")
        awaitState("Press 1 recorded") { it.records.singleOrNull()?.prescriptionId == "set-two-press-1" }
        click("Saltar descanso")
        awaitState("Press 2 active") { it.phase == Phase.ActiveSet && it.index == 1 }
        // The press has started, so it can no longer be postponed.
        assertFalse(device.hasObject(By.textStartsWith("Posponer")))
    }
}
