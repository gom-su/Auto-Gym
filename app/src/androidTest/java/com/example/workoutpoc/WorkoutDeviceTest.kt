package com.example.workoutpoc

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises actual SystemUI RemoteViews and WindowManager windows, not injected broadcasts. */
@RunWith(AndroidJUnit4::class)
class WorkoutDeviceTest {
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
        runBlocking { app.repository.restore(); app.repository.dispatch(app.repository.state.value.command(WorkoutEvent.ResetDemo)) }
        launch()
    }

    private fun launch() {
        device.executeShellCommand("cmd statusbar collapse")
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        assertNotNull(device.wait(Until.findObject(By.text("Iniciar demo — Notificación")), 8_000))
    }

    private fun button(text: String) {
        val found = device.wait(Until.findObject(By.text(text)), 4_000)
        assertNotNull("Missing button: $text", found)
        found!!.click()
        device.waitForIdle()
    }

    private fun control(id: String) {
        val found = device.wait(Until.findObject(By.res(pkg, id)), 4_000)
        assertNotNull("Missing real UI control: $id", found)
        found!!.click()
        device.waitForIdle()
    }

    private fun awaitState(message: String, condition: (WorkoutState) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition(app.repository.state.value)) { device.waitForIdle(); return }
            SystemClock.sleep(50)
        }
        fail("$message: ${app.repository.state.value}")
    }

    private fun shade() {
        device.openNotification()
        if (!device.wait(Until.hasObject(By.res(pkg, "weight_plus")), 1_000) &&
            !device.hasObject(By.res(pkg, "countdown"))) {
            toggleWorkoutNotification()
        }
    }

    private fun toggleWorkoutNotification() {
        var node: UiObject2? = device.findObject(By.res(pkg, "compact_title"))
            ?: device.findObject(By.text("Workout Notification PoC"))
        while (node != null) {
            val expand = node.findObject(By.res("android", "expand_button"))
            if (expand != null) { expand.click(); device.waitForIdle(); return }
            node = node.parent
        }
    }

    @After fun captureFinalUi() {
        screenshot("last-test-ui")
        val file = File(File(app.getExternalFilesDir(null), "evidence"), "last-test-ui.xml")
        device.dumpWindowHierarchy(file)
        device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/workout-poc-evidence/last-test-ui.xml")
    }

    private fun screenshot(name: String) {
        val dir = File(app.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        val file = File(dir, "$name.png")
        assertTrue(device.takeScreenshot(file))
        device.executeShellCommand("mkdir -p /sdcard/Download/workout-poc-evidence")
        device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/workout-poc-evidence/$name.png")
    }

    @Test fun completeJourneyThroughRealNotificationAndOverlay() {
        button("Iniciar demo — Notificación")
        awaitState("Session started") { it.phase == Phase.ActiveSet }
        device.executeShellCommand("am start -a android.settings.SETTINGS")
        shade()
        control("weight_plus")
        awaitState("Notification weight") { it.values.weightTenths == 325 }
        control("reps_minus")
        awaitState("Notification reps") { it.values.reps == 7 }
        control("rir1")
        awaitState("Notification RIR") { it.values.rir == 1 }
        screenshot("02-notification-edited")
        control("complete")
        awaitState("Complete notification") { it.phase == Phase.Resting && it.records.size == 1 }
        screenshot("03-notification-rest")
        val deadline = app.repository.state.value.rest!!.elapsedMillis
        control("add_rest")
        awaitState("Rest extended") { it.rest?.elapsedMillis == deadline + 10_000 }
        assertTrue(app.repository.state.value.rest!!.remainingMillis(com.example.workoutpoc.timer.clockSnapshot(app)) > 10_000)
        control("skip_rest")
        awaitState("Next set") { it.phase == Phase.ActiveSet && it.index == 1 }
        assertEquals(325, app.repository.state.value.values.weightTenths)
        // Edit opens an Activity PendingIntent directly (Android 12+ forbids receiver trampolines).
        toggleWorkoutNotification()
        control("compact_edit")
        assertNotNull(device.wait(Until.findObject(By.text("Registro rápido")), 4_000))
        device.pressBack()
        button("Iniciar demo — Burbuja flotante")
        assertNotNull(device.wait(Until.findObject(By.res(pkg, "overlay_title")), 4_000))
        device.executeShellCommand("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.google.android.deskclock")
        device.waitForIdle()
        control("overlay_weight_plus")
        awaitState("Overlay weight") { it.values.weightTenths == 350 }
        control("overlay_rir3")
        awaitState("Overlay RIR") { it.values.rir == 3 }
        shade()
        assertEquals("350", app.repository.state.value.values.weightTenths.toString())
        assertNotNull(device.wait(Until.findObject(By.res(pkg, "weight").text("35 kg")), 4_000))
        control("rir1")
        awaitState("Notification synchronizes overlay") { it.values.rir == 1 }
        device.pressBack()
        assertTrue(device.findObject(By.res(pkg, "overlay_rir1")).text.contains("●"))
        screenshot("04-overlay-over-clock")
        control("collapse")
        val bubble = device.wait(Until.findObject(By.descContains("Burbuja de entrenamiento")), 3_000)!!
        val before = bubble.visibleBounds
        bubble.drag(android.graphics.Point(800, 900), 500)
        val moved = device.wait(Until.findObject(By.descContains("Burbuja de entrenamiento")), 2_000)!!
        assertNotEquals(before, moved.visibleBounds)
        screenshot("05-bubble")
        moved.click()
        control("overlay_complete")
        awaitState("Overlay complete") { it.phase == Phase.Resting && it.records.size == 2 }
        shade()
        assertNotNull(device.wait(Until.findObject(By.res(pkg, "countdown")), 4_000))
        screenshot("06-overlay-updates-notification")
        device.pressBack()
        // Let one real ten-second rest expire without skipping.
        val end = SystemClock.elapsedRealtime() + 12_000
        while (app.repository.state.value.phase == Phase.Resting && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        awaitState("Rest automatically ended") { it.phase == Phase.ActiveSet && it.index == 2 }
        device.sleep()
        SystemClock.sleep(500)
        assertFalse(device.hasObject(By.res(pkg, "overlay_title")))
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        assertNotNull(device.wait(Until.findObject(By.res(pkg, "overlay_title")), 4_000))
        // Closing the panel must leave the session active.
        control("close")
        assertEquals(Phase.ActiveSet, app.repository.state.value.phase)
        device.pressHome()
        launch()
        assertEquals(2, app.repository.state.value.records.size)
        val beforeLibrary = app.repository.state.value
        button("Programas")
        button("Volver al entrenamiento")
        assertEquals("Library navigation must preserve the active session", beforeLibrary, app.repository.state.value)
        button("Iniciar demo — Burbuja flotante")
        repeat(5) { offset ->
            control("overlay_complete")
            if (offset < 4) {
                awaitState("Rest ${offset + 3}") { it.phase == Phase.Resting }
                control("overlay_skip")
                awaitState("Advance ${offset + 3}") { it.phase == Phase.ActiveSet }
            }
        }
        awaitState("All seven completed") { it.phase == Phase.Completed && it.records.size == 7 }
        assertEquals(SetValues(325, 7, 1), app.repository.state.value.records.first().values)
        assertNotNull(device.wait(Until.findObject(By.text("Entrenamiento terminado")), 4_000))
        screenshot("07-completed-summary")
        assertFalse(device.hasObject(By.res(pkg, "overlay_title")))
    }
}
