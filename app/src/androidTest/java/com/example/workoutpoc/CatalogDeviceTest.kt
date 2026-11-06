package com.example.workoutpoc

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.catalog.ExerciseCatalogSerializer
import com.example.workoutpoc.catalog.exerciseHistory
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

/** ENT-08 through the real catalog screen: renaming an exercise keeps its recorded history by ID. */
@RunWith(AndroidJUnit4::class)
class CatalogDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)
    private val newName = "Sentadilla renombrada ${SystemClock.elapsedRealtime() % 100000}"

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
            repo.dispatch(repo.state.value.command(WorkoutEvent.ResetDemo)) // archives the synthetic session
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    private fun scrollTo(text: String): UiObject2 = device.scrollToText(text)
    private fun click(text: String) { scrollTo(text).click(); device.waitForIdle() }

    @Test fun rename_keeps_history_by_id() {
        val historyBefore = exerciseHistory("squat", app.repository.archived.value.sessions).size
        assertTrue(historyBefore >= 1)
        click("Programas")
        click("Catálogo de ejercicios")
        click("ID squat")
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        assertNotNull(field); field!!.text = newName
        // A shown soft keyboard covers the lower half of the form; dismiss it before looking for the save button.
        if (device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) { device.pressBack(); device.waitForIdle() }
        click("Guardar ejercicio")
        // Poll the persisted catalog, read independently of the app's in-memory state.
        val file = File(app.filesDir, "datastore/exercises-v1.json")
        val end = SystemClock.elapsedRealtime() + 6_000
        var squat = runBlocking { file.inputStream().use { ExerciseCatalogSerializer.readFrom(it) } }.exercises.single { it.id == "squat" }
        while (squat.name != newName && SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(100)
            squat = runBlocking { file.inputStream().use { ExerciseCatalogSerializer.readFrom(it) } }.exercises.single { it.id == "squat" }
        }
        assertEquals(newName, squat.name)
        assertTrue(squat.aliases.isNotEmpty())
        // The history list on the same screen still shows every set, labelled with the name it was recorded under.
        scrollTo("Historial · $historyBefore series")
        assertNotNull(scrollTo("Registrada como"))
        device.pressHome()
    }
}
