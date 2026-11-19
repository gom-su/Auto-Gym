package com.example.workoutpoc.wearapp

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import com.example.workoutpoc.wearprotocol.ActionRequest
import com.example.workoutpoc.wearprotocol.PanelAction
import com.example.workoutpoc.wearprotocol.PanelControl
import com.example.workoutpoc.wearprotocol.PanelMode
import com.example.workoutpoc.wearprotocol.PanelProtocol
import com.example.workoutpoc.wearprotocol.PanelReply
import com.example.workoutpoc.wearprotocol.PanelSnapshot
import com.example.workoutpoc.wearprotocol.PanelTimer
import com.example.workoutpoc.wearprotocol.ReplyResult
import com.example.workoutpoc.wearprotocol.TimerKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the Android service, vibration driver and notification while the phone replies are deterministic. */
@RunWith(AndroidJUnit4::class)
class WearBackgroundDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as TestWearApplication
    private val notifications = app.getSystemService(NotificationManager::class.java)

    @Test fun expiredRestVibratesAwayFromThePanelAndNotificationConfirms() {
        Assume.assumeTrue(app.relayPort == null && !app.usePairedPhone)
        app.phone.deadline = SystemClock.elapsedRealtime() + 15_000
        confirmBackgroundRest {
            await("The phone received OK and the watch stopped vibrating") {
                app.phone.confirmations == 1 && !vibrationActive()
            }
            assertEquals(1, app.phone.confirmations)
        }
    }

    @Test fun phoneEmulatorPersistsTheWatchConfirmationAndNextSet() {
        Assume.assumeTrue("Requires a paired phone or the two-emulator ADB test relay",
            app.usePairedPhone || app.relayPort != null)
        confirmBackgroundRest {
            await("The phone opened its next set and stopped the alarm") {
                val state = app.connection.state.value
                state.connected && !state.busy && state.snapshot?.mode == PanelMode.SET &&
                    state.snapshot.index == 1 && !vibrationActive()
            }
            val next = requireNotNull(app.connection.state.value.snapshot)
            val complete = next.controls.single { it.action == PanelAction.COMPLETE_SET && it.enabled }
            instrumentation.runOnMainSync { app.connection.perform(complete, next) }
            await("The phone acknowledged the completed session") {
                app.connection.state.value.snapshot?.mode == PanelMode.COMPLETED
            }
        }
    }

    private fun confirmBackgroundRest(afterConfirmation: () -> Unit) {
        shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        notifications.cancelAll()
        ActivityScenario.launch(WearActivity::class.java).use {
            try {
                await("Started foreground monitor", timeoutMillis = 15_000) {
                    val state = app.connection.state.value
                    state.connected && state.snapshot?.mode == PanelMode.REST && notifications.activeNotifications.isNotEmpty()
                }
                assertTrue("The rest should still be counting down before leaving the panel",
                    app.connection.state.value.snapshot?.alert == false)
                shell("input keyevent KEYCODE_HOME")
                shell("input keyevent KEYCODE_SLEEP")
                await("The panel moved to the background") { !app.connection.state.value.panelVisible }
                val okText = app.getString(R.string.wear_monitor_ok)
                await("Background notification contains OK", timeoutMillis = 45_000) {
                    notifications.activeNotifications.any { item ->
                        item.notification.actions.orEmpty().any { action -> action.title.toString() == okText }
                    }
                }
                await("The system recorded a repeating Auto-Gym vibration") { vibrationActive() }
                val action = notifications.activeNotifications.flatMap { it.notification.actions.orEmpty().toList() }
                    .singleOrNull { it.title.toString() == okText }
                assertNotNull(action)
                requireNotNull(action).actionIntent.send()
                afterConfirmation()
            } finally {
                WearSessionService.close(app)
                shell("input keyevent KEYCODE_WAKEUP")
            }
        }
    }

    private fun await(message: String, timeoutMillis: Long = 8_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            SystemClock.sleep(120)
        }
        fail(message)
    }

    private fun vibrationActive(): Boolean =
        shell("dumpsys vibrator_manager").substringAfter("CurrentVibration:", "")
            .substringBefore("NextVibration:").contains(app.packageName)
}

class WearTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(javaClass.classLoader, TestWearApplication::class.java.name, context)
}

class TestWearApplication : WearApplication() {
    internal val phone = RestTestPhone()
    internal val usePairedPhone: Boolean
        get() = InstrumentationRegistry.getArguments().getString("wearPair") == "true"
    internal val relayPort: Int?
        get() = InstrumentationRegistry.getArguments().getString("wearRelayPort")
            ?.toIntOrNull()?.takeIf { it in 1..65535 }

    internal override fun createConnection(): WearPanelConnection {
        if (usePairedPhone) return super.createConnection()
        return WearPanelConnection(
            relayPort?.let(::AdbPhonePanelTestTransport) ?: phone,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), SystemClock::elapsedRealtime,
        )
    }
}

private class AdbPhonePanelTestTransport(private val port: Int) : WearPanelTransport {
    override fun listen(onPhonesChanged: (Set<WearPhone>) -> Unit, onPanelChanged: (String) -> Unit) = Unit
    override fun stopListening() = Unit
    override suspend fun reachablePhones() = setOf(WearPhone("phone-emulator", true))

    override suspend fun request(nodeId: String, path: String, body: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            require(path in setOf(PanelProtocol.STATE_PATH, PanelProtocol.ACTION_PATH, PanelProtocol.SUMMARY_PATH))
            val payload = Base64.encodeToString(body, Base64.NO_WRAP)
            val (stdout, stdin) = InstrumentationRegistry.getInstrumentation()
                .uiAutomation.executeShellCommandRw("toybox nc -w 3 -W 4 127.0.0.1 $port")
            val response = ParcelFileDescriptor.AutoCloseInputStream(stdout).bufferedReader().use { reader ->
                ParcelFileDescriptor.AutoCloseOutputStream(stdin).bufferedWriter().use { writer ->
                    writer.write(path)
                    writer.newLine()
                    writer.write(payload)
                    writer.newLine()
                    writer.flush()
                    reader.readLine()
                }
            }
            check(!response.isNullOrEmpty()) { "The phone emulator did not answer the ADB test relay" }
            Log.i("AutoGymTestRelay", "${path.substringAfterLast('/')}: ${response.length} bytes")
            Base64.decode(response, Base64.NO_WRAP)
        }
}

private fun shell(command: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

internal class RestTestPhone : WearPanelTransport {
    @Volatile var deadline = Long.MAX_VALUE
    @Volatile var confirmations = 0
        private set

    override fun listen(
        onPhonesChanged: (Set<WearPhone>) -> Unit,
        onPanelChanged: (String) -> Unit,
    ) = Unit

    override fun stopListening() = Unit

    override suspend fun reachablePhones() = setOf(WearPhone("phone", true))

    override suspend fun request(nodeId: String, path: String, body: ByteArray): ByteArray {
        if (path == PanelProtocol.ACTION_PATH) {
            val request = PanelProtocol.decode<ActionRequest>(body)
            check(request == ActionRequest("session", 0, 1, "rest", PanelAction.REST_OK))
            confirmations++
        } else check(path == PanelProtocol.STATE_PATH)
        val remaining = deadline - SystemClock.elapsedRealtime()
        val current = if (confirmations > 0) PanelSnapshot(sessionId = "session", index = 1,
            revision = 2, mode = PanelMode.SET, title = "Jalón") else PanelSnapshot(
            sessionId = "session", revision = 1, restSegmentId = "rest", mode = PanelMode.REST,
            title = "Descanso", notice = "Siguiente: Jalón",
            timer = PanelTimer(remaining, TimerKind.REST, running = true), alert = remaining <= 0,
            controls = if (remaining > 0) listOf(PanelControl(PanelAction.REST_SKIP, "Saltar descanso"))
            else listOf(PanelControl(PanelAction.REST_OK, "OK")),
        )
        return PanelProtocol.encode(PanelReply(ReplyResult.OK, current))
    }
}
