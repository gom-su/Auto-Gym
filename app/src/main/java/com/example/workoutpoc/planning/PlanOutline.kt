package com.example.workoutpoc.planning

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.example.workoutpoc.exchange.PerformanceStatus
import com.example.workoutpoc.exchange.SessionResult
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

/**
 * General structure of a multi-week plan (typically 12 weeks): the periodization the agent follows week by week.
 * It never contains prescriptions or results. Each concrete week is still a [WeekPlan] proposed by the agent and
 * approved by the user; week number N of the plan is the N-th week of the program (blocks in order, weeks in order).
 */
@Serializable data class PlanOutline(
    val id: String, val version: Int, val programId: String, val programName: String, val name: String,
    /** Objectives of the whole cycle. */
    val goals: List<String>,
    /** Progression, effort, pain, cardio and deload rules the agent must apply, as written by the plan author. */
    val rules: List<String>,
    /** Day distribution (A–D…) with its exercises, in order. */
    val days: List<DayTemplate>,
    val blocks: List<OutlineBlock>,
    /** One entry per week, numbered 1..N without gaps. */
    val weeks: List<OutlineWeek>
)
@Serializable data class DayTemplate(val id: String, val name: String, val focus: String? = null, val exercises: List<TemplateExercise>)
@Serializable data class TemplateExercise(val exerciseId: String, val name: String, val sets: Int? = null, val notes: String? = null)
@Serializable data class OutlineBlock(val id: String, val name: String, val focus: String? = null)
@Serializable data class OutlineWeek(
    val number: Int, val blockId: String, val phase: String, val microcycle: String, val deload: Boolean,
    val targets: List<WeekTarget>, val cardio: CardioTarget? = null, val notes: String? = null
)
/** Effort and volume target for the week. Empty [exerciseIds] means every exercise without a more specific target. */
@Serializable data class WeekTarget(
    val label: String, val exerciseIds: List<String> = emptyList(), val sets: Int? = null,
    val repsMin: Int? = null, val repsMax: Int? = null, val rirMin: Int? = null, val rirMax: Int? = null
)
@Serializable data class CardioTarget(
    val modality: String, val minutes: Int? = null, val sessions: Int? = null,
    val rpeMin: Int? = null, val rpeMax: Int? = null, val notes: String? = null
)

@Serializable enum class BriefStatus {
    /** The next week can be planned now. */
    READY,
    /** The next week is already in the program; it needs real records before another week is planned. */
    ALREADY_PLANNED,
    /** The last week of the plan has real records: the cycle is over and needs an evaluation, not a new week. */
    CYCLE_COMPLETE
}

/**
 * Deterministic answer to "which week does the agent plan next, and with what targets?". Computed by the app from the
 * saved program, the plan and the recorded sessions; the agent never has to guess the week number or its block.
 */
@Serializable data class NextWeekBrief(
    val programId: String, val outlineId: String, val outlineVersion: Int, val status: BriefStatus,
    val totalWeeks: Int, val plannedWeeks: Int,
    /** Highest week number with real records (completed or partial sets/cardio); null when nothing was recorded. */
    val referenceWeekNumber: Int?, val referenceWeekId: String?,
    /** Recorded sessions of the reference week, as IDs in the context's history. */
    val referenceSessionIds: List<String>,
    /** Days of the reference week without real records (not performed, or not recorded yet). */
    val referenceDaysWithoutData: List<String>,
    val weekNumber: Int?, val blockId: String?, val blockName: String?, val weekInBlock: Int?, val weeksInBlock: Int?,
    val target: OutlineWeek?,
    val message: String
)

object PlanOutlines {
    const val MAX_WEEKS = 52
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

    fun validate(o: PlanOutline) {
        fun id(v: String, path: String) = check(v.matches(ID), "$path: ID no válido.")
        fun name(v: String, path: String) = check(v.isNotBlank() && v.length <= 200, "$path: el nombre debe tener entre 1 y 200 caracteres.")
        fun text(v: String, path: String) = check(v.isNotBlank() && v.length <= 10000, "$path: texto vacío o demasiado largo.")
        fun range(lo: Int?, hi: Int?, min: Int, max: Int, what: String, path: String) {
            check(lo == null || lo in min..max, "$path: $what mínimo fuera de rango ($min–$max).")
            check(hi == null || hi in min..max, "$path: $what máximo fuera de rango ($min–$max).")
            check(lo == null || hi == null || lo <= hi, "$path: $what mínimo mayor que el máximo.")
        }
        id(o.id, "Plan"); id(o.programId, "Plan: programId"); name(o.programName, "Plan: nombre del programa"); name(o.name, "Plan")
        check(o.version > 0, "Plan: la versión debe ser positiva.")
        o.goals.forEach { text(it, "Plan: objetivo") }; o.rules.forEach { text(it, "Plan: regla") }
        check(o.days.isNotEmpty(), "Plan: falta la distribución de días.")
        check(o.days.map { it.id }.distinct().size == o.days.size, "Plan: días duplicados.")
        val templateIds = mutableSetOf<String>()
        o.days.forEach { d ->
            val p = "Día ${d.id}"
            id(d.id, p); name(d.name, p); d.focus?.let { text(it, p) }
            check(d.exercises.isNotEmpty(), "$p: falta un ejercicio.")
            check(d.exercises.map { it.exerciseId }.distinct().size == d.exercises.size, "$p: ejercicio repetido.")
            d.exercises.forEach { e ->
                id(e.exerciseId, "$p / ${e.name}"); name(e.name, "$p / ejercicio"); e.notes?.let { text(it, "$p / ${e.name}") }
                check(e.sets == null || e.sets in 1..50, "$p / ${e.name}: series fuera de rango (1–50).")
                templateIds += e.exerciseId
            }
        }
        check(o.blocks.isNotEmpty(), "Plan: falta un bloque.")
        check(o.blocks.map { it.id }.distinct().size == o.blocks.size, "Plan: bloques duplicados.")
        o.blocks.forEach { b -> id(b.id, "Bloque"); name(b.name, "Bloque ${b.id}"); b.focus?.let { text(it, "Bloque ${b.id}") } }
        check(o.weeks.size in 1..MAX_WEEKS, "Plan: entre 1 y $MAX_WEEKS semanas.")
        o.weeks.forEachIndexed { i, w ->
            val p = "Semana ${i + 1}"
            check(w.number == i + 1, "$p: las semanas se numeran 1..N, en orden y sin huecos.")
            check(o.blocks.any { it.id == w.blockId }, "$p: bloque ${w.blockId} no definido.")
            name(w.phase, "$p: fase"); name(w.microcycle, "$p: microciclo"); w.notes?.let { text(it, p) }
            w.targets.forEach { t ->
                val tp = "$p / ${t.label}"
                name(t.label, "$p: objetivo")
                t.exerciseIds.forEach { e -> check(e in templateIds, "$tp: el ejercicio $e no está en la distribución de días.") }
                check(t.exerciseIds.distinct().size == t.exerciseIds.size, "$tp: ejercicio repetido.")
                check(t.sets == null || t.sets in 1..50, "$tp: series fuera de rango (1–50).")
                range(t.repsMin, t.repsMax, 1, 1000, "repeticiones", tp); range(t.rirMin, t.rirMax, 0, 10, "RIR", tp)
            }
            // Two targets for the same scope would make the deterministic check ambiguous.
            val scopes = w.targets.flatMap { t -> t.exerciseIds.ifEmpty { listOf("*") } }
            check(scopes.distinct().size == scopes.size, "$p: dos objetivos aplican al mismo ejercicio.")
            w.cardio?.let { c ->
                name(c.modality, "$p: modalidad de cardio"); c.notes?.let { text(it, "$p: cardio") }
                check(c.minutes == null || c.minutes in 1..600, "$p: minutos de cardio fuera de rango (1–600).")
                check(c.sessions == null || c.sessions in 1..14, "$p: sesiones de cardio fuera de rango (1–14).")
                range(c.rpeMin, c.rpeMax, 1, 10, "RPE", "$p: cardio")
            }
        }
        // Blocks follow each other: once a block ends it does not come back, and blocks are listed in that order.
        val order = o.weeks.map { it.blockId }.fold(mutableListOf<String>()) { acc, b -> if (acc.lastOrNull() != b) acc += b; acc }
        check(order.distinct().size == order.size, "Plan: las semanas de un bloque deben ser consecutivas.")
        check(order == o.blocks.map { it.id }, "Plan: cada bloque necesita semanas y se lista en el orden en que ocurre.")
    }

    fun weekInBlock(o: PlanOutline, number: Int): Pair<Int, Int> {
        val w = o.weeks[number - 1]
        val weeks = o.weeks.filter { it.blockId == w.blockId }
        return weeks.indexOfFirst { it.number == number } + 1 to weeks.size
    }

    /** The program's weeks in plan order: week number N is element N-1. */
    fun programWeeks(program: TrainingProgram?): List<Pair<TrainingBlock, WeekPlan>> =
        program?.blocks.orEmpty().flatMap { b -> b.weeks.map { b to it } }

    /** Saved weeks that disagree with the plan. A plan is only activated, and a week only added, when this is empty. */
    fun conflicts(o: PlanOutline, program: TrainingProgram?): List<String> {
        if (program == null) return emptyList()
        val out = mutableListOf<String>()
        check(program.id == o.programId, "El plan es de otro programa.")
        val weeks = programWeeks(program)
        if (weeks.size > o.weeks.size) out += "El programa ya tiene ${weeks.size} semanas y el plan solo ${o.weeks.size}."
        weeks.take(o.weeks.size).forEachIndexed { i, (block, week) ->
            val planned = o.weeks[i].blockId
            if (block.id != planned) out += "Semana ${i + 1} (${week.name}) está en el bloque ${block.id}; el plan la sitúa en $planned."
        }
        return out
    }

    /** A session counts as real data only when something was actually performed. */
    fun hasRealData(s: SessionResult) = s.status in REAL && (s.sets.any { it.status in REAL } || s.cardio.any { it.status in REAL })
    private val REAL = setOf(PerformanceStatus.COMPLETED, PerformanceStatus.PARTIAL)

    fun brief(o: PlanOutline, program: TrainingProgram?, sessions: List<SessionResult>): NextWeekBrief {
        val weeks = programWeeks(program)
        val withData = sessions.filter { (it.programId == null || it.programId == o.programId) && hasRealData(it) }
        val reference = weeks.indices.lastOrNull { i -> weeks[i].second.days.any { d -> withData.any { it.sessionPlanId == d.id } } }
        val refWeek = reference?.let { weeks[it].second }
        val refSessions = refWeek?.let { w -> withData.filter { s -> w.days.any { it.id == s.sessionPlanId } } }.orEmpty()
        val missingDays = refWeek?.days?.filter { d -> refSessions.none { it.sessionPlanId == d.id } }?.map { it.name }.orEmpty()
        val refNumber = reference?.plus(1)
        val next = (refNumber ?: 0) + 1
        val status = when {
            next > o.weeks.size -> BriefStatus.CYCLE_COMPLETE
            next <= weeks.size -> BriefStatus.ALREADY_PLANNED
            else -> BriefStatus.READY
        }
        val target = if (status == BriefStatus.CYCLE_COMPLETE) null else o.weeks[next - 1]
        val block = target?.let { t -> o.blocks.first { it.id == t.blockId } }
        val (inBlock, blockSize) = target?.let { weekInBlock(o, it.number) } ?: (null to null)
        val total = o.weeks.size
        val message = when (status) {
            BriefStatus.CYCLE_COMPLETE -> "La semana $total es la última del plan y ya tiene registros. No se planifica una semana ${total + 1}. Al terminar sus días, genera un nuevo plan de 12 semanas con el historial del ciclo."
            BriefStatus.ALREADY_PLANNED -> "La semana $next ya está en el programa (${weeks[next - 1].second.name}). Regístrala antes de planificar la siguiente."
            BriefStatus.READY -> (if (refNumber == null) "Sin registros reales todavía: se planifica la semana 1."
                else "Referencia: semana $refNumber (${refWeek!!.name}). Se planifica la semana $next.") +
                " Bloque ${block!!.name}, semana $inBlock de $blockSize; ${target!!.phase} · ${target.microcycle}" +
                (if (target.deload) "; descarga." else ".") +
                (if (missingDays.isNotEmpty()) " Días sin registros en la referencia: ${missingDays.joinToString(", ")}." else "")
        }
        return NextWeekBrief(o.programId, o.id, o.version, status, total, weeks.size, refNumber, refWeek?.id,
            refSessions.map { it.id }, missingDays, if (status == BriefStatus.CYCLE_COMPLETE) null else next,
            block?.id, block?.name, inBlock, blockSize, target, message)
    }

    private fun check(valid: Boolean, message: String) { if (!valid) throw ProgramValidationException(message) }
}

/**
 * Activated plans. Only one plan is active at a time: activating another program's plan sets the current one aside
 * in [inactive], from where it can be activated again. A newer version of the same plan replaces it; earlier
 * versions are kept in [replaced].
 */
@Serializable data class PlanOutlineStore(
    val schemaVersion: Int = 1, val active: List<PlanOutline> = emptyList(), val replaced: List<PlanOutline> = emptyList(),
    val inactive: List<PlanOutline> = emptyList()
) {
    fun forProgram(programId: String) = active.find { it.programId == programId }
}

object PlanOutlineStoreSerializer : Serializer<PlanOutlineStore> {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    override val defaultValue = PlanOutlineStore()
    fun validate(s: PlanOutlineStore) {
        require(s.schemaVersion == 1) { "Versión no compatible." }
        require(s.active.map { it.programId }.distinct().size == s.active.size) { "Dos planes activos para el mismo programa." }
        (s.active + s.replaced + s.inactive).forEach(PlanOutlines::validate)
    }
    override suspend fun readFrom(input: InputStream): PlanOutlineStore = try {
        json.decodeFromString<PlanOutlineStore>(input.readBytes().decodeToString()).also(::validate)
    } catch (e: SerializationException) { throw CorruptionException("Planes ilegibles. Se conservan sin reemplazar.", e) }
      catch (e: IllegalArgumentException) { throw CorruptionException("Planes inválidos. Se conservan sin reemplazar.", e) }
    override suspend fun writeTo(t: PlanOutlineStore, output: OutputStream) { validate(t); output.write(json.encodeToString(t).encodeToByteArray()) }
}

enum class ActivationResult { ACTIVATED, ALREADY_ACTIVE, REPLACED }

class PlanOutlineRepository(private val store: DataStore<PlanOutlineStore>) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(PlanOutlineStore())
    val outlines = mutable.asStateFlow()
    suspend fun restore() = mutex.withLock { mutable.value = store.data.first() }

    /** Replaces the whole store with a restored backup (see docs/FULL_BACKUP.md). The serializer validates it. */
    suspend fun replaceAll(outlines: PlanOutlineStore) = mutex.withLock { replaceAllLocked(outlines) }
    private suspend fun replaceAllLocked(outlines: PlanOutlineStore) { PlanOutlineStoreSerializer.validate(outlines); mutable.value = store.updateData { outlines } }
    internal val backupStore get() = com.example.workoutpoc.backup.BackupStore(mutex,
        { it.copy(planOutlines = store.data.first()) }, { replaceAllLocked(it.planOutlines) })

    /**
     * Explicit activation by the user. The plan must agree with the weeks already saved in its program; a plan for a
     * program with an active plan is only accepted as a newer version of that same plan.
     */
    suspend fun activate(outline: PlanOutline, program: TrainingProgram?): ActivationResult = mutex.withLock {
        PlanOutlines.validate(outline)
        PlanOutlines.conflicts(outline, program).takeIf { it.isNotEmpty() }?.let {
            throw ProgramValidationException("El plan no coincide con las semanas guardadas:\n" + it.joinToString("\n") { c -> "• $c" })
        }
        var result = ActivationResult.ACTIVATED
        mutable.value = store.updateData { s ->
            val current = s.forProgram(outline.programId)
            when {
                // The one active plan; any other program's plan is kept as inactive, never deleted.
                current == null -> s.copy(active = listOf(outline), inactive = (s.inactive + s.active).filter { it != outline })
                current == outline -> { result = ActivationResult.ALREADY_ACTIVE; s }
                current.id != outline.id -> throw ProgramValidationException(
                    "El programa ya tiene el plan '${current.name}' (${current.id}). Publica una versión nueva de ese plan para cambiarlo.")
                outline.version <= current.version -> throw ProgramValidationException(
                    "La versión ${outline.version} no es posterior a la activa (${current.version}). No se reemplazó nada.")
                else -> { result = ActivationResult.REPLACED
                    s.copy(active = s.active.map { if (it.programId == outline.programId) outline else it }, replaced = s.replaced + current) }
            }
        }
        result
    }
}
