package com.example.workoutpoc

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.*
import com.example.workoutpoc.proposal.DecisionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** AG-06 through the real UI: paste a proposal, see it pending with rule results, approve, and the week is in the program. */
@RunWith(AndroidJUnit4::class)
class ProposalDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)
    private val suffix = SystemClock.elapsedRealtime().toString()
    private val weekId = "week-device-$suffix"
    private val importId = "import-device-$suffix"

    @Before fun prepare() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        val program = ProgramCodec.decode(app.assets.open("examples/variable-program-v1.json").use { it.readBytes().decodeToString() }
            // Its own synthetic program: approvals append weeks, which must not alter other tests' fixtures.
            .replace("fictional-program", "proposal-device-program").replace("Programa ficticio variable", "Programa ficticio de propuestas"))
        runBlocking { app.programs.restore(); app.programs.importProgram(program) }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    @After fun capture() {
        val file = File(app.getExternalFilesDir(null), "ag-last-ui.xml")
        device.dumpWindowHierarchy(file)
        device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/ag-last-ui.xml")
    }

    private fun click(text: String) { device.scrollToText(text).click(); device.waitForIdle() }

    private fun proposalJson(): String {
        val week = WeekPlan(weekId, "Semana dispositivo $suffix", 1, listOf(SessionPlan("$weekId-day", "Día dispositivo", listOf(
            ExercisePrescription("$weekId-press", "machine-press", "Press ficticio", listOf(
                SetPrescription("$weekId-p1", 10, 2, PrescribedLoad(4.0, LoadUnit.MACHINE_SETTING, LoadBasis.MACHINE_SETTING), 90)))))))
        return ExchangeCodec.encode(ExchangePackage(1, ExchangeType.WeekProposal, importId, "2026-11-06T12:00:00Z",
            proposal = WeekProposal("proposal-device-$suffix", 1, "proposal-device-program", "block-base", "Agente ficticio", week,
                listOf("Carga de press 3 → 4"), listOf("Razón ficticia"))))
    }

    @Test fun export_import_validate_and_explicitly_approve() {
        click("Programas")
        click("Intercambio JSON")
        device.setFieldText(proposalJson())
        click("Revisar intercambio")
        click("Guardar intercambio")
        val end = SystemClock.elapsedRealtime() + 6_000
        while (app.exchanges.archive.value.packages.none { it.importId == importId } && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue(app.exchanges.archive.value.packages.any { it.importId == importId })
        // Saving the exchange never applies the proposal.
        assertTrue(app.programs.library.value.programs.single { it.id == "proposal-device-program" }.blocks.flatMap { it.weeks }.none { it.id == weekId })
        device.pressBack()
        click("Propuestas de semana")
        click("proposal-device-$suffix v1")
        device.scrollToText("Estado: Pendiente de tu decisión")
        device.scrollToText("• Press ficticio: carga +33 %")
        click("Aprobar y añadir semana")
        val end2 = SystemClock.elapsedRealtime() + 6_000
        while (app.proposals.state.value.decisions.none { it.importId == importId } && SystemClock.elapsedRealtime() < end2) SystemClock.sleep(100)
        assertEquals(DecisionStatus.APPROVED, app.proposals.state.value.decisions.single { it.importId == importId }.status)
        val saved = runBlocking { File(app.filesDir, "datastore/programs-v1.json").inputStream().use { ProgramLibrarySerializer.readFrom(it) } }
        assertNotNull(saved.programs.single { it.id == "proposal-device-program" }.findDay(weekId, "$weekId-day"))
        device.scrollToText("Estado: Aprobada")
        // AG-07: explained changes; AG-03: the decision is reviewable afterwards in the history.
        assertNotNull(device.scrollToText("Aumento · Press ficticio: carga máxima 3 → 4"))
        device.clickText("Ver todas las propuestas")
        device.scrollToText("Historial de decisiones")
        assertNotNull(device.scrollToText("· Aprobada · proposal-device-$suffix v1", contains = true))
        device.pressHome()
    }
}
