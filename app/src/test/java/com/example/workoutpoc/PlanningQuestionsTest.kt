package com.example.workoutpoc

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.example.workoutpoc.catalog.*
import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.ProgramCodec
import com.example.workoutpoc.questions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

/** AG-01: planning gaps are asked, answered or marked not available, and never invented. */
class PlanningQuestionsTest {
    @get:Rule val folder = TemporaryFolder()
    private val catalog = ExerciseCatalog(exercises = listOf(CatalogExercise("squat", "Sentadilla", equipment = Equipment.BARBELL),
        CatalogExercise("row", "Remo")))

    private suspend fun repo(scope: CoroutineScope) = PlanningAnswerRepository(DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM,
        object : OkioSerializer<PlanningAnswers> {
            override val defaultValue = PlanningAnswersSerializer.defaultValue
            override suspend fun readFrom(source: BufferedSource) = PlanningAnswersSerializer.readFrom(source.inputStream())
            override suspend fun writeTo(t: PlanningAnswers, sink: BufferedSink) { PlanningAnswersSerializer.writeTo(t, sink.outputStream()) }
        }) { folder.newFolder().resolve("planning-answers-v1.json").absolutePath.toPath() }, scope = scope), { 1_800_000_000_000 }).also { it.restore() }

    @Test fun `gaps stay open until answered or marked unavailable, and only answers reach the context`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val r = repo(scope)
        val questions = PlanningQuestions.questions(catalog)
        // General context plus the one exercise whose equipment is unknown.
        assertEquals(listOf("goal", "phase", "restrictions", "availability", "equipment:row"), questions.map { it.id })
        assertEquals(questions, PlanningQuestions.open(questions, r.answers.value))
        val (phase0, goals0, rules0) = PlanningQuestions.context(questions, r.answers.value)
        assertNull(phase0); assertTrue(goals0.isEmpty()); assertTrue(rules0.isEmpty())        // nothing invented

        r.answer("goal", "  Ganar fuerza en básicos  ")
        r.answer("phase", "Acumulación")
        r.answer("restrictions", null)                                                       // explicitly not available
        r.answer("equipment:row", Equipment.CABLE.name)
        val open = PlanningQuestions.open(questions, r.answers.value)
        assertEquals(listOf("availability"), open.map { it.id })
        val (phase, goals, rules) = PlanningQuestions.context(questions, r.answers.value)
        assertEquals("Acumulación", phase)
        assertEquals(listOf("Ganar fuerza en básicos"), goals)
        assertTrue(rules.any { it.startsWith("Dato no disponible (indicado por el usuario):") && it.contains("restricciones") })
        assertTrue(rules.any { it == "Equipo de Remo (row): CABLE" })
        assertTrue(rules.none { it.contains("disponibles") && !it.startsWith("Dato") })      // the unanswered question is absent

        // Blank text is never stored as an answer; reopening returns the question.
        try { r.answer("availability", "   "); fail() } catch (_: IllegalArgumentException) { }
        r.clear("phase")
        assertEquals(setOf("availability", "phase"), PlanningQuestions.open(questions, r.answers.value).map { it.id }.toSet())
        scope.cancel(); scope.coroutineContext[Job]!!.join()
    }

    @Test fun `answers feed a valid v1 training context`() {
        val program = ProgramCodec.decode(javaClass.classLoader!!.getResourceAsStream("examples/variable-program-v1.json")!!
            .bufferedReader().use { it.readText() })
        val answers = PlanningAnswers(answers = listOf(PlanningAnswer("goal", "Hipertrofia", false, 1), PlanningAnswer("restrictions", null, true, 1)))
        val (phase, goals, rules) = PlanningQuestions.context(PlanningQuestions.questions(catalog), answers)
        val pkg = ExchangePackage(1, ExchangeType.TrainingContext, "ctx", "2026-11-06T12:00:00Z",
            context = TrainingContext(listOf(program), phase, TrainingResults(emptyList()), rules, goals))
        assertEquals(pkg, ExchangeCodec.decode(ExchangeCodec.encode(pkg)))
        assertNull(pkg.context!!.phase); assertEquals(listOf("Hipertrofia"), pkg.context.goals); assertEquals(1, pkg.context.rules.size)
    }

    @Test fun `stored answers are validated and never reset`() {
        listOf(PlanningAnswer("goal", null, false, 1), PlanningAnswer("goal", "x", true, 1), PlanningAnswer("goal", " ", false, 1),
            PlanningAnswer("bad id", "x", false, 1)).forEach { bad ->
            try { bad.validate(); fail("accepted $bad") } catch (_: IllegalArgumentException) { }
        }
        val dup = """{"schemaVersion":1,"answers":[{"questionId":"goal","value":"a","unavailable":false,"answeredAtMillis":1},{"questionId":"goal","value":"b","unavailable":false,"answeredAtMillis":2}]}"""
        try { runBlocking { PlanningAnswersSerializer.readFrom(ByteArrayInputStream(dup.toByteArray())) }; fail() } catch (_: CorruptionException) { }
    }
}
