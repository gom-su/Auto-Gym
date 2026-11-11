package com.example.workoutpoc

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.workoutpoc.questions.PlanningAnswersSerializer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** AG-01 through the real UI: an answer and an explicit "not available" are recorded and reach the exported context. */
@RunWith(AndroidJUnit4::class)
class QuestionsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as WorkoutApplication
    private val device = UiDevice.getInstance(instrumentation)
    private val phase = "Fase ficticia ${SystemClock.elapsedRealtime()}"

    @Before fun prepare() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        runBlocking { app.planningAnswers.restore(); listOf("goal", "phase", "restrictions", "availability").forEach { app.planningAnswers.clear(it) } }
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.awaitHome()
    }

    @Test fun answer_and_unavailable_are_recorded_and_exported() {
        device.openTab("Agente")
        device.clickText("Preguntas de planificación")
        // Each question card is a column: act on the field and buttons inside the card that holds the question text.
        fun card(question: String): UiObject2 = device.scrollToText(question).parent
        val goal = card("¿Cuál es tu objetivo")
        assertNull(goal.findObject(By.clazz("android.widget.EditText")))
        goal.findObject(By.text("Mejorar fuerza")).click()
        card("¿Cuál es tu objetivo").findObject(By.text("Guardar respuesta")).click()
        val goalEnd = SystemClock.elapsedRealtime() + 6_000
        while (app.planningAnswers.answers.value.find("goal") == null && SystemClock.elapsedRealtime() < goalEnd) SystemClock.sleep(100)
        card("¿En qué fase estás").findObject(By.text("Otra")).click()
        card("¿En qué fase estás").findObject(By.clazz("android.widget.EditText")).text = phase
        SystemClock.sleep(600)
        if (device.hasObject(By.pkg("com.google.android.inputmethod.latin"))) { device.pressBack(); device.waitForIdle() }
        card("¿En qué fase estás").findObject(By.text("Guardar respuesta")).click()
        val end = SystemClock.elapsedRealtime() + 6_000
        while (app.planningAnswers.answers.value.find("phase")?.value != phase && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        card("¿Hay lesiones").findObject(By.text("No disponible")).click()
        val end2 = SystemClock.elapsedRealtime() + 6_000
        while (app.planningAnswers.answers.value.find("restrictions") == null && SystemClock.elapsedRealtime() < end2) SystemClock.sleep(100)
        val saved = runBlocking { File(app.filesDir, "datastore/planning-answers-v1.json").inputStream().use { PlanningAnswersSerializer.readFrom(it) } }
        assertEquals(phase, saved.find("phase")?.value)
        assertTrue(saved.find("restrictions")!!.unavailable)
        assertEquals("Mejorar fuerza", saved.find("goal")?.value)

        // The exported context is built by exactly this function from the stored answers (export encoding is unit-tested).
        val (contextPhase, _, rules) = com.example.workoutpoc.questions.PlanningQuestions.context(
            com.example.workoutpoc.questions.PlanningQuestions.questions(app.catalog.catalog.value), saved)
        assertEquals(phase, contextPhase)
        assertTrue(rules.any { it.startsWith("Dato no disponible") && it.contains("lesiones") })
        device.pressHome()
    }
}
