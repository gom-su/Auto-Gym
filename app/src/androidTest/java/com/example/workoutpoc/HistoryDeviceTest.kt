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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Session history through the real screen: an archived synthetic session is listed and its sets and edit trail are shown. */
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
            // One edit, so the detail shows the replaced version (audit trail).
            repo.state.value.let { repo.edit(it.sessionId, it.revision, 0, SetStatus.COMPLETED, SetValues(210, 7, 2), "Nota de historial") }
            repo.dispatch(repo.state.value.command(WorkoutEvent.FinishWorkout))
            repo.dispatch(repo.state.value.command(WorkoutEvent.ResetDemo))
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.awaitHome()
    }

    @Test fun archived_session_is_listed_with_its_sets_and_edits() {
        device.openTab("Progreso")
        device.clickText("Sesiones")
        // Newest first: the session just archived is at the top of the list.
        val title = device.wait(Until.findObject(By.textContains("Programa ficticio de historial · Semana 1 · Día A")), 10_000)
        assertNotNull(title)
        assertTrue(device.hasObject(By.textContains("Parcial (terminada antes) · 1 completadas · 0 omitidas · 1 sin registro")))
        title!!.visibleBounds.let { device.click(it.centerX(), it.centerY()) }
        assertNotNull(device.wait(Until.findObject(By.text("Series")), 5_000))
        assertNotNull(device.scrollToText("Sentadilla ficticia · serie 1"))
        assertNotNull(device.scrollToText(" × 7 @ RIR 2 · editada", contains = true))
        assertNotNull(device.scrollToText("Nota: Nota de historial"))
        assertNotNull(device.scrollToText("Versiones anteriores (1)"))
        assertNotNull(device.scrollToText(" × 8 @ RIR ", contains = true))
        device.clickText("Ver todas las sesiones")
        assertNotNull(device.wait(Until.findObject(By.textContains("Programa ficticio de historial")), 5_000))
        device.pressHome()
    }

    /** The picker redraws while it loads its roots, so nodes can go stale between finding and tapping: retry. */
    private fun retry(block: () -> Unit) {
        repeat(5) { try { block(); return } catch (_: StaleObjectException) { SystemClock.sleep(300) } catch (_: NullPointerException) { SystemClock.sleep(300) } }
        block()
    }
    private fun pickerTap(selector: BySelector, required: Boolean = true): Boolean {
        repeat(5) {
            try {
                val node = device.wait(Until.findObject(selector), 3_000) ?: return@repeat
                node.click(); device.waitForIdle(); return true
            } catch (_: StaleObjectException) { SystemClock.sleep(300) }
        }
        if (required) fail("Missing in picker: $selector")
        return false
    }

    @Test fun csv_export_writes_the_recorded_sets_through_the_save_dialog() {
        device.openTab("Progreso")
        device.clickText("Sesiones")
        device.clickText("Exportar CSV")
        // The save picker may open on a location that cannot hold files (e.g. Recent): choose Downloads explicitly.
        pickerTap(By.desc("Show roots"), required = false)
        pickerTap(By.text("Downloads"), required = false)
        val outputName = "history-export-${System.currentTimeMillis()}.csv"
        retry { device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)!!.text = outputName }
        if (!pickerTap(By.res("com.google.android.documentsui", "button1"), required = false)) pickerTap(By.text("SAVE"))
        assertNotNull(device.scrollToText("Historial exportado en CSV."))
        val path = device.executeShellCommand("find /sdcard/Download -name $outputName").trim()
        assertTrue(path.isNotEmpty())
        val csv = device.executeShellCommand("cat $path")
        assertTrue(csv.startsWith("session_id,started_at,ended_at,program,"))
        assertTrue(csv.contains("Programa ficticio de historial,Semana 1,Día A,PARTIAL (ENDED_EARLY),set,Sentadilla ficticia,"))
        assertTrue(csv.contains(",Nota de historial,"))
        device.executeShellCommand("rm $path")
        device.pressHome()
    }
}
