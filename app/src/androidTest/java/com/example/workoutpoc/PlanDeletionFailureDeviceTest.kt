package com.example.workoutpoc

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.workoutpoc.catalog.ChartMetric
import com.example.workoutpoc.data.WorkoutHistory
import com.example.workoutpoc.domain.PlannedCardio
import com.example.workoutpoc.domain.SessionComment
import com.example.workoutpoc.domain.SessionSource
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.exchange.ExchangeArchive
import com.example.workoutpoc.exchange.ExchangeCodec
import com.example.workoutpoc.planning.ProgramCodec
import com.example.workoutpoc.proposal.DecisionStatus
import com.example.workoutpoc.proposal.Expectation
import com.example.workoutpoc.proposal.ExpectedDirection
import com.example.workoutpoc.proposal.ProposalDecision
import com.example.workoutpoc.proposal.ProposalState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Deleting a plan with real data on the emulator, then a real write failure in the middle of the deletion (the exchange
 * archive cannot be written, after the program, annotations, proposals and comments are already removed). The plan must
 * stay listed, and deleting again must finish everything.
 */
@RunWith(AndroidJUnit4::class)
class PlanDeletionFailureDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)
    private val planJson = app.assets.open("examples/plan-outline-v1.json").use { it.readBytes().decodeToString() }
    private val planPackage = ExchangeCodec.decode(planJson)
    private val outline = planPackage.outline!!
    private val programId = outline.programId
    private val program = ProgramCodec.decode(app.assets.open("examples/variable-program-v1.json").use { it.readBytes().decodeToString() }
        .replace("fictional-program", programId))
    private val weekCount = program.blocks.sumOf { it.weeks.size }
    private val exchangeFile get() = File(app.filesDir, "datastore/exchanges-v1.json")

    @Before fun seed() {
        device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard"); device.pressHome()
        runBlocking {
            restoreAll()
            // Clean leftovers of an earlier run, then seed real data in every store the deletion touches.
            removeEverything()
            app.outlines.activate(outline, null)
            app.programs.importProgram(program)
            app.exchanges.replaceAll(app.exchanges.archive.value.copy(packages = app.exchanges.archive.value.packages + planPackage))
            // A session of this program needs a planned step (strength or cardio) to be valid.
            val session = WorkoutState(sessionId = "del-fail-session", cardioPlan = listOf(PlannedCardio("bike", "Bicicleta", 3600)), source = SessionSource(
                programId = programId, programName = program.name, blockId = program.blocks.first().id,
                weekId = program.blocks.first().weeks.first().id, weekName = "Semana", weekVersion = 1, dayId = "day", dayName = "Día"))
            app.repository.replaceAll(WorkoutState(), WorkoutHistory(sessions = app.repository.archived.value.sessions + session))
            app.comments.add(SessionComment(id = "del-fail-comment", text = "Comentario de prueba", createdAtMillis = 1L, programId = programId))
            app.weekAnnotations.setDeload(programId, program.blocks.first().weeks.first().id, true)
            val decision = ProposalDecision(importId = "del-fail-import", proposalId = "del-fail-proposal", version = 1,
                status = DecisionStatus.APPROVED, decidedAtMillis = 1L, programId = programId, blockId = program.blocks.first().id,
                weekId = program.blocks.first().weeks.first().id, warnings = emptyList(), differences = emptyList(),
                statedDifferences = emptyList(), reasons = emptyList())
            val expectation = Expectation(id = "del-fail-expectation", importId = "del-fail-import", exerciseId = "sentadilla",
                exerciseName = "Sentadilla", metric = ChartMetric.TOP_LOAD, direction = ExpectedDirection.INCREASE, windowDays = 14, startMillis = 1L)
            val proposals = app.proposals.state.value
            app.proposals.replaceAll(proposals.copy(decisions = proposals.decisions + decision, expectations = proposals.expectations + expectation))
        }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.awaitHome()
    }

    @After fun repairAndClean() {
        // A failed run must never leave the exchange archive as a directory for the next tests.
        if (exchangeFile.isDirectory) exchangeFile.deleteRecursively()
        runBlocking { restoreAll(); removeEverything() }
    }

    private suspend fun restoreAll() {
        app.outlines.restore(); app.exchanges.restore(); app.programs.restore(); app.repository.restore()
        app.weekAnnotations.restore(); app.proposals.restore(); app.comments.restore()
    }

    private suspend fun removeEverything() {
        app.outlines.removeProgram(programId); app.programs.removeProgram(programId)
        app.weekAnnotations.removeProgram(programId); app.proposals.removeProgram(programId)
        app.comments.removeProgram(programId); app.exchanges.removeProgram(programId)
        app.repository.deleteProgramRecords(programId)
    }

    /** From the plan list: opens the plan's detail and asks for the deletion. */
    private fun openDeletionDialog() {
        device.openTab("Plan")
        device.scrollToText("Planes de periodización")
        device.clickText("Planes de periodización")
        device.clickText("${outline.name} v${outline.version}")
        askForDeletion()
    }

    /** From the plan's detail (where the tab bar is hidden): asks for the deletion again. */
    private fun askForDeletion() {
        device.scrollToText("Borrar plan y entrenamientos")
        device.clickText("Borrar plan y entrenamientos")
        assertTrue(device.wait(Until.hasObject(By.text("¿Borrar el plan y sus entrenamientos?")), 5_000))
    }

    @Test fun real_data_is_counted_removed_and_a_failed_deletion_can_be_finished() {
        // 1. The dialog counts every seeded record.
        openDeletionDialog()
        assertTrue("dialog must count the session", device.hasObject(By.textContains("1 sesión(es)")))
        assertTrue("dialog must count the weeks", device.hasObject(By.textContains("$weekCount semana(s)")))
        assertTrue("dialog must count the comment", device.hasObject(By.textContains("1 comentario(s)")))
        assertTrue("dialog must count the package", device.hasObject(By.textContains("1 paquete(s)")))
        device.clickText("Cancelar")
        runBlocking { restoreAll() }
        assertTrue("cancel must keep everything", app.outlines.outlines.value.active.any { it.programId == programId })

        // 2. A real write failure in the middle: the exchange archive cannot be written, after the earlier steps.
        askForDeletion()
        val original = exchangeFile.readBytes()
        exchangeFile.delete(); exchangeFile.mkdirs()
        try {
            device.clickText("Borrar")
            assertTrue("the failure must be reported", device.wait(Until.hasObject(By.textContains("quedó a medias")), 10_000))
        } finally {
            exchangeFile.deleteRecursively(); exchangeFile.writeBytes(original)
        }
        runBlocking { restoreAll() }
        // Partial progress is real: the program, sessions, comment, annotation and decision are gone...
        assertTrue("program removed before the failure", app.programs.library.value.programs.none { it.id == programId })
        assertTrue("sessions removed before the failure", app.repository.archived.value.sessions.none { it.source?.programId == programId })
        assertTrue("comment removed before the failure", app.comments.log.value.comments.none { it.programId == programId })
        assertTrue("decision removed before the failure", app.proposals.state.value.decisions.none { it.programId == programId })
        // ...but the plan itself is kept, so it is still listed and the deletion can be resumed from Plan.
        assertTrue("plan must still be listed", app.outlines.outlines.value.active.any { it.programId == programId })
        assertTrue("the plan's package must still be listed", app.exchanges.archive.value.packages.any { it.type.name == "PlanOutline" && it.outline?.programId == programId })

        // 3. Deleting again finishes the rest.
        askForDeletion()
        assertTrue("the retry must offer what is left", device.hasObject(By.textContains("0 semana(s)")))
        device.clickText("Borrar")
        assertTrue("the retry must report success", device.wait(Until.hasObject(By.textContains("borrado")), 10_000))
        runBlocking { restoreAll() }
        assertTrue("plan removed", app.outlines.outlines.value.active.none { it.programId == programId })
        assertTrue("package removed", app.exchanges.archive.value.packages.none { it.outline?.programId == programId })
        assertTrue("program removed", app.programs.library.value.programs.none { it.id == programId })
        assertTrue("sessions removed", app.repository.archived.value.sessions.none { it.source?.programId == programId })
        assertTrue("annotations removed", app.weekAnnotations.annotations.value.deloads.none { it.programId == programId })
        assertTrue("proposal records removed", app.proposals.state.value.decisions.none { it.programId == programId } &&
            app.proposals.state.value.expectations.none { it.importId == "del-fail-import" })
        assertTrue("comments removed", app.comments.log.value.comments.none { it.programId == programId })
    }
}
