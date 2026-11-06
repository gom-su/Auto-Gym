package com.example.workoutpoc

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.planning.ProgramCodec
import com.example.workoutpoc.planning.findDay
import com.example.workoutpoc.planning.toPlannedSets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** ANA-02 through the real UI: mark a week as deload in Programas, then the exercise chart shows those exposures as deload. */
@RunWith(AndroidJUnit4::class)
class PerformanceChartDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)
    private val programId = "chart-device-program"

    @Before fun prepare() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        // Its own synthetic program, so deload marks never affect other tests.
        val program = ProgramCodec.decode(app.assets.open("examples/variable-program-v1.json").use { it.readBytes().decodeToString() }
            .replace("fictional-program", programId).replace("Programa ficticio variable", "Programa ficticio de gráficas"))
        runBlocking {
            app.programs.restore(); app.programs.importProgram(program)
            app.weekAnnotations.restore(); app.weekAnnotations.setDeload(programId, "week-one", false)
            val repo = app.repository
            repo.restore()
            repo.dispatch(repo.state.value.command(WorkoutEvent.ResetDemo))
            val day = program.findDay("week-one", "day-one-a")!!
            repo.load(day.day.toPlannedSets(), day.source)
            repo.dispatch(repo.state.value.command(WorkoutEvent.StartWorkout))
            repo.dispatch(repo.state.value.command(WorkoutEvent.CompleteSet))
            repo.dispatch(repo.state.value.command(WorkoutEvent.FinishWorkout))
            repo.dispatch(repo.state.value.command(WorkoutEvent.ResetDemo)) // archive it
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        // Wait for the first screen: dragging before it is drawn would drag the launcher instead.
        assertNotNull(device.wait(Until.findObject(By.text("Programas")), 10_000))
    }

    @After fun capture() {
        val file = java.io.File(app.getExternalFilesDir(null), "chart-last-ui.xml")
        device.dumpWindowHierarchy(file)
        device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/chart-last-ui.xml")
    }

    @Test fun deload_mark_is_shown_in_the_exercise_chart() {
        device.clickText("Programas")
        device.clickText("Programa ficticio de gráficas")
        device.clickText("Semana 1 ·")
        device.clickText("Semana de descarga")
        val end = SystemClock.elapsedRealtime() + 6_000
        while (!app.weekAnnotations.annotations.value.isDeload(programId, "week-one") && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue(app.weekAnnotations.annotations.value.isDeload(programId, "week-one"))
        // Back would leave Programas entirely; the catalog entry is on the same screen.
        device.clickText("Catálogo de ejercicios")
        device.clickText("ID squat")
        // ANA-05: the week comparison lists the deload week without comparing it.
        assertNotNull(device.scrollToText("Comparación por semana y fase"))
        assertNotNull(device.scrollToText("Semana de descarga: no se compara"))
        device.scrollToText("kg · total")
        assertNotNull(device.scrollToText("Carga máxima (kg)"))
        assertNotNull(device.wait(Until.findObject(By.descContains("descarga")), 5_000))
        // The exposure card from the marked week carries the deload label.
        assertNotNull(device.scrollToText("Programa ficticio de gráficas · Semana 1 · Día A · descarga", contains = true))
        device.pressHome()
    }
}
