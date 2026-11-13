package com.example.workoutpoc

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.data.*
import com.example.workoutpoc.domain.*
import com.example.workoutpoc.notification.WorkoutNotificationManager
import com.example.workoutpoc.overlay.WorkoutOverlayService
import com.example.workoutpoc.timer.clockSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Native RemoteViews, WindowManager and editor checks on the emulator; prior session data is restored. */
@RunWith(AndroidJUnit4::class)
class SessionSurfaceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = "com.example.workoutpoc"
    private lateinit var previous: Pair<WorkoutState, WorkoutHistory>
    private val source = SessionSource("surface-program", "Prueba", "block", "week", "Semana", 1, "day", "Día")
    private val plan = listOf(
        PlannedSet("Remo", 1, 1, 400, 12, null, "row-1", "row", restSeconds = 75),
        PlannedSet("Jalón", 1, 1, 450, 10, null, "pull-1", "pulldown", restSeconds = 75, setType = SetType.APPROACH))

    @Before fun prepare() {
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        runBlocking {
            app.repository.restore()
            previous = app.repository.withBackupAccess { it.workout to it.archive }
        }
    }

    @After fun restore() {
        app.stopService(Intent(app, WorkoutOverlayService::class.java))
        runBlocking { app.repository.replaceAll(previous.first, previous.second, allowInProgress = true) }
        device.executeShellCommand("cmd statusbar collapse"); device.pressHome()
    }

    private fun fixture(ageMillis: Long): WorkoutState {
        val now = clockSnapshot(app)
        val at = now.copy(elapsedMillis = now.elapsedMillis - ageMillis, wallMillis = now.wallMillis - ageMillis)
        val reducer = WorkoutReducer(DemoWorkout.sets)
        var state = reducer.load(WorkoutState(), plan, source, "surface-session")
        state = reducer.reduce(state, state.command(WorkoutEvent.StartWorkout), at, "surface-session")
        state = reducer.reduce(state, state.command(WorkoutEvent.SelectRir, 2), at, "surface-session")
        return reducer.reduce(state, state.command(WorkoutEvent.CompleteSet), at, "surface-session")
    }

    private fun seed(state: WorkoutState, overlay: Boolean = false) {
        instrumentation.runOnMainSync { app.stopService(Intent(app, WorkoutOverlayService::class.java)) }
        instrumentation.waitForIdleSync()
        runBlocking { app.repository.replaceAll(state, previous.second, allowInProgress = true) }
        app.startActivity(Intent(app, MainActivity::class.java).setAction("EDIT").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        assertTrue(device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 8_000))
        ContextCompat.startForegroundService(app, Intent(app, WorkoutOverlayService::class.java).setAction(if (overlay) WorkoutOverlayService.SHOW else WorkoutOverlayService.HIDE))
    }

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; SystemClock.sleep(50) }
        fail(message)
    }

    private fun click(id: String) {
        val end = SystemClock.elapsedRealtime() + 6_000
        while (SystemClock.elapsedRealtime() < end) {
            try {
                freshTree()
                val node = device.wait(Until.findObject(By.res(pkg, id)), 500) ?: continue
                node.click(); return
            } catch (_: StaleObjectException) { }
        }
        fail("Missing control $id")
    }

    private fun screenshot(name: String) {
        val file = File(app.getExternalFilesDir("surface-evidence"), "$name.png")
        assertTrue("Screenshot $name", device.takeScreenshot(file))
        device.executeShellCommand("mkdir -p /sdcard/Download/codex-surface-evidence")
        device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/codex-surface-evidence/$name.png")
    }

    private fun shade() {
        device.openNotification()
        val end = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < end) {
            freshTree()
            if (device.hasObject(By.res(pkg, "skip_rest")) || device.hasObject(By.res(pkg, "machine_wait"))) return
            try {
                var node = device.wait(Until.findObject(By.res(pkg, "compact_title")), 500)
                while (node != null) {
                    val expand = node.findObject(By.res("android", "expand_button"))
                    if (expand != null) { expand.click(); break }
                    node = node.parent
                }
            } catch (_: StaleObjectException) { }
            SystemClock.sleep(100)
        }
        screenshot("shade-failure")
        fail("Workout notification did not expand")
    }

    @Test fun transparentAlignedNotificationsAndRirRulesInBothThemes() {
        val state = fixture(95_000)
        instrumentation.runOnMainSync {
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val config = Configuration(app.resources.configuration).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night }
                val context = app.createConfigurationContext(config)
                val notification = WorkoutNotificationManager(context).build(state)
                val parent = FrameLayout(context)
                val expanded = notification.bigContentView.apply(context, parent)
                val compact = notification.contentView.apply(context, parent)
                val width = (360 * context.resources.displayMetrics.density).toInt()
                expanded.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                expanded.layout(0, 0, width, expanded.measuredHeight)
                assertEquals(0, Color.alpha((expanded.background as ColorDrawable).color))
                assertEquals(0, Color.alpha((compact.background as ColorDrawable).color))
                val reduce = expanded.findViewById<TextView>(R.id.reduce_rest)
                val add = expanded.findViewById<TextView>(R.id.add_rest)
                val skip = expanded.findViewById<TextView>(R.id.skip_rest)
                assertEquals(reduce.top, skip.top); assertEquals(add.height, skip.height)
                assertEquals("OK", skip.text.toString())
                assertTrue(expanded.findViewById<TextView>(R.id.countdown).text.startsWith("−"))
                assertEquals(expanded.findViewById<TextView>(R.id.countdown).text, compact.findViewById<TextView>(R.id.compact_countdown).text)
                assertEquals(View.VISIBLE, expanded.findViewById<View>(R.id.edit_last).visibility)

                val work = state.copy(phase = Phase.ActiveSet, values = state.values.copy(rir = null), rest = null, restSegments = emptyList())
                val workView = WorkoutNotificationManager(context).build(work).bigContentView.apply(context, parent)
                assertEquals("Selecciona el RIR", workView.findViewById<TextView>(R.id.complete).text)
                assertFalse(workView.findViewById<View>(R.id.complete).isEnabled)
                val approach = work.copy(index = 1, values = SetValues(450, 10, null))
                val approachView = WorkoutNotificationManager(context).build(approach).bigContentView.apply(context, parent)
                assertEquals(View.GONE, approachView.findViewById<View>(R.id.notification_rir).visibility)
                assertTrue(approachView.findViewById<View>(R.id.complete).isEnabled)
            }
        }
    }

    @Test fun editLastSeriesFromNotificationKeepsRestRunningAndCancelDiscardsChanges() {
        seed(fixture(70_000))
        shade()
        assertNotNull(device.wait(Until.findObject(By.res(pkg, "edit_last")), 5_000))
        screenshot("notification-rest")
        click("edit_last")
        val save = device.wait(Until.findObject(By.text("Guardar cambios")), 6_000)
        assertNotNull(save)
        assertEquals(Phase.Resting, app.repository.state.value.phase)
        val reps = app.repository.state.value.records.single().values.reps
        device.findObject(By.desc("Aumentar repeticiones"))!!.click()
        SystemClock.sleep(8_000)
        device.findObject(By.text("Guardar cambios"))!!.click()
        await("The same record must be saved") { app.repository.state.value.records.single().values.reps == reps + 1 }
        assertEquals(Phase.Resting, app.repository.state.value.phase)
        assertEquals(0, app.repository.state.value.index)
        assertTrue(app.repository.state.value.restSignedBalance(clockSnapshot(app))!! < 0)
        assertTrue(device.wait(Until.gone(By.text("Guardar cambios")), 4_000))
        assertNotNull(device.wait(Until.findObject(By.textStartsWith("−00:")), 4_000))
        screenshot("app-negative-rest")

        // Open the same deep link again and cancel a different edit.
        app.startActivity(Intent(app, MainActivity::class.java).setAction("EDIT_LAST_RECORD")
            .putExtra("session", app.repository.state.value.sessionId).putExtra("recordIndex", 0)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        assertNotNull(device.wait(Until.findObject(By.text("Cancelar")), 4_000))
        device.findObject(By.desc("Aumentar repeticiones"))!!.click()
        device.findObject(By.text("Cancelar"))!!.click()
        assertEquals(reps + 1, app.repository.state.value.records.single().values.reps)
    }

    @Test fun overlayArmsWaitThenConfirmsAnApproachWithoutRir() {
        seed(fixture(45_000), overlay = true)
        click("overlay_wait")
        await("Wait should be armed") { app.repository.state.value.openMachineWait?.pending != null }
        assertNotNull(device.findObject(By.text("Espera al terminar el descanso")))
        assertTrue(device.findObject(By.res(pkg, "overlay_notice"))!!.text.contains("Aproximación"))
        click("overlay_reduce"); await("First rest adjustment") { app.repository.state.value.openRestSegment?.configuredSeconds == 60 }
        click("overlay_reduce")
        await("Wait should start") { app.repository.state.value.runningWait != null }
        assertNotNull(device.wait(Until.findObject(By.text("Terminar espera")), 3_000))
        SystemClock.sleep(1_500)
        screenshot("overlay-machine-wait")
        click("overlay_wait")
        await("Wait should close without advancing") { app.repository.state.value.openMachineWait == null }
        assertEquals(Phase.Resting, app.repository.state.value.phase)
        assertNull(app.repository.state.value.openRestSegment!!.measuredMillis)
        assertTrue(app.repository.state.value.machineWaits.single().measuredMillis!! >= 1_000)
        click("overlay_skip")
        await("Approach should start after confirmation") { app.repository.state.value.phase == Phase.ActiveSet && app.repository.state.value.index == 1 }
        assertNull(device.findObject(By.res(pkg, "overlay_rir0")))
        assertTrue(device.findObject(By.res(pkg, "overlay_subtitle"))!!.text.contains("Aproximación"))
        screenshot("overlay-approach")
        click("overlay_complete")
        await("Approach can complete without RIR") { app.repository.state.value.phase == Phase.Completed }
    }

    @Test fun notificationWaitKeepsRestOpenUntilExplicitConfirmation() {
        seed(fixture(80_000))
        shade()
        click("machine_wait")
        await("Notification should start wait") { app.repository.state.value.runningWait != null }
        assertNotNull(device.wait(Until.findObject(By.text("Terminar espera")), 3_000))
        SystemClock.sleep(1_500)
        screenshot("notification-machine-wait")
        click("machine_wait")
        await("Notification should end wait") { app.repository.state.value.openMachineWait == null }
        assertEquals(Phase.Resting, app.repository.state.value.phase)
        assertNull(app.repository.state.value.openRestSegment!!.measuredMillis)
        assertEquals("OK", device.wait(Until.findObject(By.res(pkg, "skip_rest")), 3_000)!!.text)
        click("skip_rest")
        await("Only OK advances to the approach") { app.repository.state.value.phase == Phase.ActiveSet && app.repository.state.value.index == 1 }
    }
}
