package com.example.workoutpoc

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.body.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** ANA-03 through the real UI: a measurement by another method is kept apart and the trend warns about it. */
@RunWith(AndroidJUnit4::class)
class BodyMeasurementDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)
    private val dexaId = "device-dexa-${SystemClock.elapsedRealtime()}"

    @Before fun prepare() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        runBlocking {
            app.measurements.restore()
            // Synthetic DEXA reading two days earlier.
            app.measurements.add(BodyMeasurement(dexaId, System.currentTimeMillis() - 2 * 86_400_000L, BodyMetric.BODY_FAT, 17.0,
                MeasureUnit.PERCENT, MeasureMethod.DEXA))
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        // Wait for the first screen: dragging before it is drawn would drag the launcher instead.
        device.awaitHome()
    }

    @After fun capture() {
        val file = File(app.getExternalFilesDir(null), "body-last-ui.xml")
        device.dumpWindowHierarchy(file)
        device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/body-last-ui.xml")
    }

    @Test fun different_method_is_a_separate_series_with_a_warning() {
        device.openTab("Progreso")
        device.clickText("Cuerpo")
        device.clickText("Grasa corporal")
        val value = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        assertNotNull(value); value!!.text = "19,5"
        SystemClock.sleep(600)
        if (device.hasObject(By.pkg("com.google.android.inputmethod.latin"))) { device.pressBack(); device.waitForIdle() }
        device.clickText("Bioimpedancia")
        device.clickText("Guardar medición")
        val end = SystemClock.elapsedRealtime() + 6_000
        while (app.measurements.log.value.measurements.none { it.method == MeasureMethod.BIA && it.value == 19.5 } && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        val saved = runBlocking { File(app.filesDir, "datastore/measurements-v1.json").inputStream().use { MeasurementLogSerializer.readFrom(it) } }
        val bia = saved.measurements.last { it.method == MeasureMethod.BIA }
        assertEquals(BodyMetric.BODY_FAT, bia.metric); assertEquals(19.5, bia.value, 0.0); assertEquals(MeasureUnit.PERCENT, bia.unit)
        assertTrue(saved.measurements.any { it.id == dexaId })
        assertNotNull(device.scrollToText("Hay mediciones con métodos distintos"))
        assertNotNull(device.scrollToText("Bioimpedancia · % ·"))
        assertNotNull(device.scrollToText("DEXA · % ·"))
        device.pressHome()
    }
}
