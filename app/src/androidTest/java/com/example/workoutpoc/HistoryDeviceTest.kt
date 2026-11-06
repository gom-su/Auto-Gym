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

/** Session history through the real screen: an archived synthetic session is listed and its sets are shown. */
@RunWith(AndroidJUnit4::class)
class HistoryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)

    @Before fun prepare() {
        // Earlier tests may leave the floating workout panel up; it intercepts taps over this screen.
        app.stopService(Intent(app, com.example.workoutpoc.overlay.WorkoutOverlayService::class.java))
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        val program = ProgramCodec.decode(app.assets.open("examples/variable-program-v1.json").use { it.readBytes().decodeToString() }
            .replace("fictional-program", "history-device-program").replace("Programa ficticio variable", "Programa ficticio de historial"))
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
        assertNotNull(device.wait(Until.findObject(By.text("Programas")), 10_000))
    }

    @Test fun archived_session_is_listed_with_its_sets() {
        device.clickText("Programas")
        device.clickText("Historial de sesiones")
        // Newest first: the session just archived is at the top of the list.
        val title = device.wait(Until.findObject(By.textContains("Programa ficticio de historial · Semana 1 · Día A")), 10_000)
        assertNotNull(title)
        assertTrue(device.hasObject(By.textContains("Parcial (terminada antes) · 1 completadas · 0 omitidas · 1 sin registro")))
        title!!.visibleBounds.let { device.click(it.centerX(), it.centerY()) }
        assertNotNull(device.wait(Until.findObject(By.text("Series")), 5_000))
        assertNotNull(device.scrollToText("Sentadilla ficticia · serie 1"))
        assertNotNull(device.scrollToText(" × 8 @ RIR ", contains = true))
        device.clickText("Ver todas las sesiones")
        assertNotNull(device.wait(Until.findObject(By.textContains("Programa ficticio de historial")), 5_000))
        device.pressHome()
    }
}
