package com.example.workoutpoc

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.*
import com.example.workoutpoc.proposal.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** AG-04 through the real UI: an approved recommendation gets an expected result that starts pending. */
@RunWith(AndroidJUnit4::class)
class EvaluationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)
    private val suffix = SystemClock.elapsedRealtime().toString()
    private val importId = "import-eval-$suffix"
    private val programId = "eval-device-program"

    @Before fun prepare() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        val program = ProgramCodec.decode(app.assets.open("examples/variable-program-v1.json").use { it.readBytes().decodeToString() }
            .replace("fictional-program", programId).replace("Programa ficticio variable", "Programa ficticio de evaluación"))
        val weekId = "week-eval-$suffix"
        val week = WeekPlan(weekId, "Semana evaluación", 1, listOf(SessionPlan("$weekId-d", "Día", listOf(
            ExercisePrescription("$weekId-e", "squat", "Sentadilla ficticia", listOf(
                SetPrescription("$weekId-s", 5, 2, PrescribedLoad(5.0, LoadUnit.KG, LoadBasis.PER_SIDE), 90)))))))
        runBlocking {
            app.programs.restore(); app.programs.importProgram(program)
            app.exchanges.restore()
            app.exchanges.save(ExchangePackage(1, ExchangeType.WeekProposal, importId, "2026-11-06T12:00:00Z", proposal = WeekProposal(
                "proposal-eval-$suffix", 1, programId, "block-base", "Agente ficticio", week, emptyList(), listOf("Sentadilla ficticia: subir carga"))))
            app.proposals.restore(); app.proposals.approve(importId)
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertNotNull(device.wait(Until.findObject(By.text("Programas")), 10_000))
    }

    @Test fun expected_result_is_recorded_and_pending() {
        device.clickText("Programas")
        device.clickText("Propuestas de semana")
        device.clickText("proposal-eval-$suffix v1")
        device.scrollToText("Evaluación posterior")
        device.clickText("Guardar resultado esperado")
        val end = SystemClock.elapsedRealtime() + 6_000
        while (app.proposals.state.value.expectations.none { it.importId == importId } && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        val saved = runBlocking { File(app.filesDir, "datastore/proposals-v1.json").inputStream().use { ProposalStateSerializer.readFrom(it) } }
        val e = saved.expectations.single { it.importId == importId }
        assertEquals("squat", e.exerciseId); assertEquals(ExpectedDirection.INCREASE, e.direction); assertEquals(28, e.windowDays)
        assertNotNull(device.scrollToText("Resultado: Pendiente (automático)"))
        device.pressHome()
    }
}
