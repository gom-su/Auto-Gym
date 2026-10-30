package com.example.workoutpoc

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.workoutpoc.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Separates pixel feedback from input synchronization and accessibility-cache latency. */
@RunWith(AndroidJUnit4::class)
class NotificationLatencyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = "com.example.workoutpoc"
    private val measurements = mutableListOf<String>()

    private fun tap(id: String, expectedId: String, expected: String, block: Int): Long {
        val button = device.findObject(By.res(pkg, id)) ?: error("Missing $id")
        val bounds = button.visibleBounds
        val valueBounds = device.findObject(By.res(pkg, expectedId))!!.visibleBounds
        fun pixels(): IntArray {
            val bitmap = checkNotNull(automation.takeScreenshot())
            return try {
                IntArray(valueBounds.width() * valueBounds.height()).also {
                    bitmap.getPixels(it, 0, valueBounds.width(), valueBounds.left, valueBounds.top,
                        valueBounds.width(), valueBounds.height())
                }
            } finally { bitmap.recycle() }
        }
        val before = pixels()
        val executor = Executors.newSingleThreadExecutor()
        val start = SystemClock.uptimeMillis()
        val firstChangedPixels = executor.submit<Long> {
            while (SystemClock.uptimeMillis() - start < 3_000) {
                val after = pixels()
                // RIR's pressed background changes on touch-down; count text glyphs,
                // not that immediate pressed-state color, as value feedback.
                fun ink(color: Int): Boolean = if (id.startsWith("overlay_")) {
                    // Overlay text is white/lime on dark; notification text is dark on light.
                    android.graphics.Color.red(color) > 150 && android.graphics.Color.green(color) > 150 &&
                        android.graphics.Color.blue(color) > 70
                } else {
                    android.graphics.Color.red(color) < 140 && android.graphics.Color.green(color) < 140 &&
                        android.graphics.Color.blue(color) < 140
                }
                if (before.indices.count { ink(before[it]) != ink(after[it]) } > 50)
                    return@submit SystemClock.uptimeMillis() - start
            }
            -1L
        }
        try {
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            val injectionMs = SystemClock.uptimeMillis() - start
            while (SystemClock.uptimeMillis() - start < 6_000) {
                if (device.hasObject(By.res(pkg, expectedId).text(expected))) {
                    val latency = SystemClock.uptimeMillis() - start
                    Log.i("WorkoutLatency", "$id -> $expected latency=${latency}ms")
                    val pixelMs = firstChangedPixels.get(4, TimeUnit.SECONDS)
                    assertTrue("No visible pixel update for $id", pixelMs > 0)
                    measurements += "{\"surface\":\"${if (id.startsWith("overlay_")) "overlay" else "notification"}\",\"block\":$block,\"control\":\"$id\",\"pixelChangeMs\":$pixelMs,\"injectionMs\":$injectionMs,\"accessibilityTextMs\":$latency}"
                    Log.i("WorkoutLatency", measurements.last())
                    return latency
                }
                SystemClock.sleep(10)
            }
            error("Text never updated: $id -> $expected; state=${app.repository.state.value}")
        } finally { executor.shutdownNow() }
    }

    private fun openNotificationControls() {
        device.openNotification()
        if (!device.wait(Until.hasObject(By.res(pkg, "weight_plus")), 1_000)) {
            var parent = device.findObject(By.text("Workout Notification PoC"))
            while (parent != null) {
                val expand = parent.findObject(By.res("android", "expand_button"))
                if (expand != null) { expand.click(); break }
                parent = parent.parent
            }
        }
        assertNotNull(device.wait(Until.findObject(By.res(pkg, "weight_plus")), 5_000))
        device.waitForIdle()
    }

    private fun openOverlayControls() {
        device.pressBack()
        app.startActivity(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        device.wait(Until.findObject(By.text("Iniciar demo — Burbuja flotante")), 8_000)!!.click()
        assertNotNull(device.wait(Until.findObject(By.res(pkg, "overlay_weight_plus")), 5_000))
        device.pressHome()
        device.waitForIdle()
    }

    private fun measureBlock(overlay: Boolean, block: Int) {
        val prefix = if (overlay) "overlay_" else ""
        val previousIdleTimeout = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout = 0
        try {
            repeat(4) {
                val weight = app.repository.state.value.values.weightTenths + 25
                tap("${prefix}weight_plus", "${prefix}weight", "${weightLabel(weight)} kg", block)
                assertEquals(weight, app.repository.state.value.values.weightTenths)
                val reps = app.repository.state.value.values.reps + 1
                tap("${prefix}reps_plus", "${prefix}reps", "$reps reps", block)
                assertEquals(reps, app.repository.state.value.values.reps)
                val rir = if (app.repository.state.value.values.rir == 0) 1 else 0
                tap("${prefix}rir$rir", "${prefix}rir$rir", "●$rir", block)
                assertEquals(rir, app.repository.state.value.values.rir)
                // Same cadence on both surfaces, below notification rate limits.
                SystemClock.sleep(600)
            }
        } finally { Configurator.getInstance().waitForIdleTimeout = previousIdleTimeout }
    }

    @Test fun compareNotificationAndOverlayTapToVisibleText() {
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard"); device.pressHome()
        device.executeShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        runBlocking { app.repository.restore(); app.repository.dispatch(app.repository.state.value.command(WorkoutEvent.ResetDemo)) }
        app.startActivity(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        device.wait(Until.findObject(By.text("Iniciar demo — Notificación")), 8_000)!!.click()
        device.waitForIdle()
        device.pressHome()
        try {
            // A/B/A/B reduces the chance that warm-up or run order explains the difference.
            repeat(2) { block ->
                openNotificationControls()
                measureBlock(overlay = false, block = block)
                openOverlayControls()
                measureBlock(overlay = true, block = block)
                device.findObject(By.res(pkg, "close"))!!.click()
                device.waitForIdle()
            }
        } finally {
            // Preserve partial evidence even when a control fails.
            val breakdown = File(app.getExternalFilesDir(null), "feedback-latency-comparison.json")
            breakdown.writeText(measurements.joinToString(",\n", "[\n", "\n]\n"))
            device.executeShellCommand("cp ${breakdown.absolutePath} /sdcard/Download/feedback-latency-comparison.json")
        }
        assertEquals(48, measurements.size)
    }
}
