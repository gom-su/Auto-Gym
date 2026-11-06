package com.example.workoutpoc.questions

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.example.workoutpoc.catalog.Equipment
import com.example.workoutpoc.catalog.ExerciseCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** How an answer is used when preparing the planning context for an agent. */
enum class QuestionUse { PHASE, GOAL, RULE, EQUIPMENT }

data class PlanningQuestion(val id: String, val text: String, val why: String, val use: QuestionUse,
                            val exerciseId: String? = null, val exerciseName: String? = null)

/** Either a value given by the user or an explicit "not available". Nothing else is ever stored. */
@Serializable data class PlanningAnswer(val questionId: String, val value: String?, val unavailable: Boolean, val answeredAtMillis: Long) {
    fun validate() {
        require(questionId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,160}"))) { "Pregunta no válida." }
        require(unavailable == (value == null)) { "Una respuesta tiene valor o se marca como no disponible, no ambas." }
        require(value == null || (value.isNotBlank() && value == value.trim() && value.length <= 1000)) { "Respuesta vacía o demasiado larga." }
        require(answeredAtMillis > 0)
    }
}

@Serializable data class PlanningAnswers(val schemaVersion: Int = 1, val answers: List<PlanningAnswer> = emptyList()) {
    fun find(id: String) = answers.find { it.questionId == id }
}

object PlanningQuestions {
    private val base = listOf(
        PlanningQuestion("goal", "¿Cuál es tu objetivo principal de entrenamiento ahora?", "Orienta qué progresiones propone el agente.", QuestionUse.GOAL),
        PlanningQuestion("phase", "¿En qué fase estás (por ejemplo, acumulación, fuerza, mantenimiento)?", "Evita comparar o progresar como si fuera otra fase.", QuestionUse.PHASE),
        PlanningQuestion("restrictions", "¿Hay lesiones, molestias o restricciones que deba respetar la planificación?", "Una propuesta no debería cargar zonas restringidas.", QuestionUse.RULE),
        PlanningQuestion("availability", "¿Cuántos días y cuánto tiempo por sesión tienes disponibles?", "Ajusta el volumen semanal a lo realizable.", QuestionUse.RULE)
    )

    /**
     * Relevant planning gaps: the general context questions plus catalogued exercises whose equipment is unknown.
     * A question stops being a gap once answered or marked not available; nothing blocks while gaps remain.
     */
    fun questions(catalog: ExerciseCatalog): List<PlanningQuestion> = base + catalog.exercises.filter { it.equipment == null }.map {
        PlanningQuestion("equipment:${it.id}", "¿Con qué equipo haces ${it.name}?", "Distingue variantes no equivalentes y sus cargas.",
            QuestionUse.EQUIPMENT, it.id, it.name)
    }

    fun open(questions: List<PlanningQuestion>, answers: PlanningAnswers) = questions.filter { answers.find(it.id) == null }

    /**
     * Context for the agent from answers only. Unavailable answers are passed as explicit unknowns so the agent does
     * not assume them; unanswered questions are simply absent. Returns phase, goals and rules for TrainingContext v1.
     */
    fun context(questions: List<PlanningQuestion>, answers: PlanningAnswers): Triple<String?, List<String>, List<String>> {
        var phase: String? = null
        val goals = mutableListOf<String>(); val rules = mutableListOf<String>()
        questions.forEach { q ->
            val a = answers.find(q.id) ?: return@forEach
            if (a.unavailable) { rules += "Dato no disponible (indicado por el usuario): ${q.text}"; return@forEach }
            val v = a.value!!
            when (q.use) {
                QuestionUse.PHASE -> phase = v
                QuestionUse.GOAL -> goals += v
                QuestionUse.RULE -> rules += "${q.text} $v"
                QuestionUse.EQUIPMENT -> rules += "Equipo de ${q.exerciseName} (${q.exerciseId}): $v"
            }
        }
        return Triple(phase, goals, rules)
    }

    fun equipmentFromAnswer(value: String): Equipment? = Equipment.entries.find { it.name == value }
}

object PlanningAnswersSerializer : Serializer<PlanningAnswers> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    override val defaultValue = PlanningAnswers()
    fun validate(a: PlanningAnswers) {
        require(a.schemaVersion == 1) { "Versión no compatible." }
        require(a.answers.map { it.questionId }.distinct().size == a.answers.size) { "Respuestas duplicadas." }
        a.answers.forEach(PlanningAnswer::validate)
    }
    override suspend fun readFrom(input: InputStream): PlanningAnswers = try {
        json.decodeFromString<PlanningAnswers>(input.readBytes().decodeToString()).also(::validate)
    } catch (e: SerializationException) { throw CorruptionException("Respuestas ilegibles. Se conservan sin reemplazar.", e) }
      catch (e: IllegalArgumentException) { throw CorruptionException("Respuestas inválidas. Se conservan sin reemplazar.", e) }
    override suspend fun writeTo(t: PlanningAnswers, output: OutputStream) { validate(t); output.write(json.encodeToString(t).encodeToByteArray()) }
}

class PlanningAnswerRepository(private val store: DataStore<PlanningAnswers>, private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(PlanningAnswers())
    val answers = mutable.asStateFlow()
    suspend fun restore() = mutex.withLock { mutable.value = store.data.first() }
    /** Records [value] (trimmed) or, when null, an explicit "not available". Replaces an earlier answer. */
    suspend fun answer(questionId: String, value: String?) = mutex.withLock {
        val clean = value?.trim()?.ifBlank { null }
        if (value != null && clean == null) throw IllegalArgumentException("Escribe una respuesta o márcala como no disponible.")
        val a = PlanningAnswer(questionId, clean, clean == null, now()).also(PlanningAnswer::validate)
        mutable.value = store.updateData { s -> s.copy(answers = s.answers.filterNot { it.questionId == questionId } + a) }
    }
    /** Reopens a question. */
    suspend fun clear(questionId: String) = mutex.withLock {
        mutable.value = store.updateData { s -> s.copy(answers = s.answers.filterNot { it.questionId == questionId }) }
    }
}
