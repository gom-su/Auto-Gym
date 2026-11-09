package com.example.workoutpoc

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.planning.ProgramCodec
import com.example.workoutpoc.planning.findDay
import com.example.workoutpoc.planning.toPlannedSets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * INT-03 through the real screen. Recorded sessions on the emulator all fall within one day, so the honest result is
 * "insufficient evidence" with its reason — never an alert; alert content is covered by StagnationTest.
 */
@RunWith(AndroidJUnit4::class)
class StagnationDeviceTest {
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
            val repo = app.repository
            repo.restore()
            repo.dispatch(repo.state.value.command(WorkoutEvent.ResetDemo))
            val day = program.findDay("week-one", "day-one-a")!!
            repo.load(day.day.toPlannedSets(), day.source)
            repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
            repo.dispatch(repo.state.value.command(WorkoutEvent.CompleteSet))
            repo.dispatch(repo.state.value.command(WorkoutEvent.FinishWorkout))
            repo.dispatch(repo.state.value.command(WorkoutEvent.ResetDemo))
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.awaitHome()
    }

    @Test fun insufficient_evidence_is_reported_with_its_reason() {
        device.openTab("Progreso")
        device.clickText("Estancamiento")
        assertNotNull(device.scrollToText("Alertas ("))
        assertNotNull(device.scrollToText("Sin datos suficientes ("))
        assertNotNull(device.scrollToText("se necesitan", contains = true))
        device.pressHome()
    }
}
