package com.example.workoutpoc

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.*
import com.example.workoutpoc.sync.SignedSync
import com.example.workoutpoc.sync.SyncEnvelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.Base64

/** INT-02 through the real screen: pair a key, accept a signed proposal once, reject its replay. */
@RunWith(AndroidJUnit4::class)
class SignedSyncDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)
    private val suffix = SystemClock.elapsedRealtime().toString()
    private val importId = "signed-device-$suffix"

    @Before fun prepare() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        runBlocking { app.sync.restore(); app.exchanges.restore() }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertNotNull(device.wait(Until.findObject(By.text("Programas")), 10_000))
    }

    private fun envelopeFor(keyId: String, secret: String): String {
        val week = WeekPlan("week-signed-$suffix", "Semana firmada", 1, listOf(SessionPlan("day-signed-$suffix", "Día", listOf(
            ExercisePrescription("ex-signed-$suffix", "squat", "Sentadilla ficticia", listOf(SetPrescription("set-signed-$suffix", 6, 2)))))))
        val payload = ExchangeCodec.encode(ExchangePackage(1, ExchangeType.WeekProposal, importId, Instant.now().toString(), proposal =
            WeekProposal("proposal-signed-$suffix", 1, "device-test-program", "block-base", "Agente vía MCP", week, emptyList(), listOf("Sentadilla ficticia: prueba"))))
        val unsigned = SyncEnvelope(1, keyId, "%032x".format(System.nanoTime()), Instant.now().toString(), "WeekProposal", payload, "")
        val mac = SignedSync.mac(Base64.getUrlDecoder().decode(secret), SignedSync.signingInput(unsigned))
        return Json.encodeToString(SyncEnvelope.serializer(), unsigned.copy(mac = mac))
    }

    private fun paste(text: String) {
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000) ?: run { device.scrollToText("JSON de intercambio"); device.findObject(By.clazz("android.widget.EditText")) }
        field.text = text
        SystemClock.sleep(600)
        if (device.hasObject(By.pkg("com.google.android.inputmethod.latin"))) { device.pressBack(); device.waitForIdle() }
        device.clickText("Revisar intercambio")
    }

    @Test fun pair_accept_once_and_reject_replay() {
        device.clickText("Programas")
        device.clickText("Intercambio JSON")
        val before = app.sync.state.value.keys.size
        device.clickText("Generar clave de emparejamiento")
        val end = SystemClock.elapsedRealtime() + 6_000
        while (app.sync.state.value.keys.size == before && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        val key = app.sync.state.value.keys.last()
        val envelope = envelopeFor(key.keyId, key.secret)

        paste(envelope)
        assertNotNull(device.scrollToText("Firma verificada con la clave ${key.keyId}"))
        device.clickText("Guardar intercambio")
        val end2 = SystemClock.elapsedRealtime() + 6_000
        while (app.exchanges.archive.value.packages.none { it.importId == importId } && SystemClock.elapsedRealtime() < end2) SystemClock.sleep(100)
        assertTrue(app.exchanges.archive.value.packages.any { it.importId == importId })
        assertTrue(app.sync.state.value.seen.any { it.keyId == key.keyId })

        // The same delivery again (e.g. a retry after a disconnection) is rejected and changes nothing.
        val count = app.exchanges.archive.value.packages.size
        paste(envelope)
        assertNotNull(device.scrollToText("Envío firmado rechazado", contains = false))
        assertEquals(count, app.exchanges.archive.value.packages.size)
        device.pressHome()
    }
}
