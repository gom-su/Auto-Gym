package com.example.workoutpoc.proposal

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.example.workoutpoc.exchange.ExchangeRepository
import com.example.workoutpoc.exchange.ExchangeType
import com.example.workoutpoc.exchange.toSessionResult
import com.example.workoutpoc.planning.ProgramRepository
import com.example.workoutpoc.planning.ProgramValidationException
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

@Serializable enum class DecisionStatus { APPROVED, REJECTED }

/** Traceable record of an explicit user decision on one imported proposal version. */
@Serializable data class ProposalDecision(
    val importId: String, val proposalId: String, val version: Int, val status: DecisionStatus, val decidedAtMillis: Long,
    val programId: String, val blockId: String, val weekId: String,
    val warnings: List<String>, val differences: List<String>, val statedDifferences: List<String>, val reasons: List<String>,
    /** Each relevant change with its evidence and the agent's reason at decision time (AG-03, AG-07). */
    val explanations: List<ExplainedChange> = emptyList(),
    /** The approval replaced an already saved week (§2); [deletedRecords] are the sessions its replacement removed. */
    val replacedWeek: Boolean = false, val deletedRecords: Int = 0
)

@Serializable data class ProposalState(
    val schemaVersion: Int = 1, val rules: RuleSettings = RuleSettings(), val decisions: List<ProposalDecision> = emptyList(),
    /** Expected results of approved recommendations, evaluated later against observed data (AG-04). */
    val expectations: List<Expectation> = emptyList()
)

object ProposalStateSerializer : Serializer<ProposalState> {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    override val defaultValue = ProposalState()
    fun validate(s: ProposalState) {
        require(s.schemaVersion == 1) { "Versión no compatible." }
        s.rules.validate()
        require(s.decisions.map { it.importId }.distinct().size == s.decisions.size) { "Decisiones duplicadas." }
        require(s.decisions.map { it.proposalId to it.version }.distinct().size == s.decisions.size) { "Decisiones duplicadas por versión." }
        require(s.expectations.map { it.id }.distinct().size == s.expectations.size) { "Expectativas duplicadas." }
        s.expectations.forEach { e ->
            e.validate()
            require(s.decisions.any { it.importId == e.importId && it.status == DecisionStatus.APPROVED }) { "Expectativa sin aprobación." }
        }
    }
    override suspend fun readFrom(input: InputStream): ProposalState = try {
        json.decodeFromString<ProposalState>(input.readBytes().decodeToString()).also(::validate)
    } catch (e: SerializationException) { throw CorruptionException("Decisiones ilegibles. Se conservan sin reemplazar.", e) }
      catch (e: IllegalArgumentException) { throw CorruptionException("Decisiones inválidas. Se conservan sin reemplazar.", e) }
    override suspend fun writeTo(t: ProposalState, output: OutputStream) { validate(t); output.write(json.encodeToString(t).encodeToByteArray()) }
}

class ProposalException(message: String) : IllegalArgumentException(message)

/**
 * The AI proposes, the deterministic rules validate, the user decides. A proposal stays pending until an explicit
 * approval or rejection; only an approval without blocking issues appends its week to the saved program.
 */
class ProposalRepository(
    private val store: DataStore<ProposalState>,
    private val programs: ProgramRepository,
    private val exchanges: ExchangeRepository,
    private val knownExerciseIds: suspend () -> Set<String>,
    private val now: () -> Long = System::currentTimeMillis,
    private val sessions: suspend () -> List<com.example.workoutpoc.domain.WorkoutState> = { emptyList() },
    /** Activated multi-week plans; a proposal for a planned program is checked against its plan week. */
    private val outlines: suspend () -> List<com.example.workoutpoc.planning.PlanOutline> = { emptyList() },
    /** Recorded sessions of one saved week, and the deletion an approved replacement performs (contract §2). */
    private val weekRecords: suspend (programId: String, weekId: String) -> Int = { _, _ -> 0 },
    private val deleteWeekRecords: suspend (programId: String, weekId: String) -> Int = { _, _ -> 0 }
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ProposalState())
    val state = mutableState.asStateFlow()

    suspend fun restore() = mutex.withLock { mutableState.value = store.data.first() }

    /** Replaces the whole store with a restored backup (see docs/FULL_BACKUP.md). The serializer validates it. */
    suspend fun replaceAll(state: ProposalState) = mutex.withLock { replaceAllLocked(state) }
    private suspend fun replaceAllLocked(state: ProposalState) { ProposalStateSerializer.validate(state); mutableState.value = store.updateData { state } }
    internal val backupStore get() = com.example.workoutpoc.backup.BackupStore(mutex,
        { it.copy(proposals = store.data.first()) }, { replaceAllLocked(it.proposals) })

    suspend fun evaluate(importId: String, replacing: Boolean = false): ProposalEvaluation {
        programs.restore(); exchanges.restore()
        val pkg = exchanges.archive.value.packages.find { it.importId == importId && it.type == ExchangeType.WeekProposal }
            ?: throw ProposalException("La propuesta no está guardada.")
        val rules = mutableState.value.rules
        val states = sessions()
        val proposal = pkg.proposal!!
        val replacedRecords = if (replacing) weekRecords(proposal.programId, proposal.week.id) else 0
        val base = applyRules(proposal, states, replacing, replacedRecords)
        val explanations = ProposalExplain.explain(base.proposal, base.base, states)
        val unexplained = if (rules.unexplainedChange == Severity.OFF) emptyList() else explanations.filter { it.reason == null }.map {
            ValidationIssue("UNEXPLAINED_CHANGE", rules.unexplainedChange, "${it.kind.label} sin motivo específico del agente: ${it.summary}")
        }
        return base.copy(issues = base.issues + unexplained, explanations = explanations)
    }

    /**
     * The same rules for a proposal that is not saved yet (one the in-app agent just returned), so a proposal with
     * blocking issues can be sent back for correction instead of being archived. Explanations are not computed.
     */
    suspend fun check(proposal: com.example.workoutpoc.exchange.WeekProposal): ProposalEvaluation {
        programs.restore()
        return applyRules(proposal, sessions())
    }

    private suspend fun applyRules(proposal: com.example.workoutpoc.exchange.WeekProposal, states: List<com.example.workoutpoc.domain.WorkoutState>,
                                   replacing: Boolean = false, replacedRecords: Int = 0) =
        ProposalRules.evaluate(proposal, programs.library.value, knownExerciseIds(), mutableState.value.rules,
            outlines().find { it.programId == proposal.programId }, states.distinctBy { it.sessionId }.mapNotNull { it.toSessionResult() },
            replacing, replacedRecords)

    fun decisionFor(importId: String): ProposalDecision? = mutableState.value.decisions.find { it.importId == importId }

    /**
     * Explicit approval. Re-checks the rules at decision time; blocking issues prevent it. The week is appended
     * idempotently (an interrupted earlier approval is completed, never duplicated) before the decision is recorded.
     */
    suspend fun approve(importId: String, replaceWeek: Boolean = false): ProposalDecision = mutex.withLock {
        decisionFor(importId)?.let { if (it.status == DecisionStatus.APPROVED) return@withLock it else throw ProposalException("La propuesta ya fue rechazada.") }
        val evaluation = evaluate(importId, replaceWeek)
        if (!evaluation.canApprove) throw ProposalException("No se puede aprobar: hay ${evaluation.blocking.size} bloqueos sin resolver.")
        val p = evaluation.proposal
        otherVersionDecision(p.id, p.version)
        var replaced = false
        var deleted = 0
        if (!evaluation.alreadyApplied) {
            val program = programs.library.value.programs.find { it.id == p.programId }
            val weekSaved = program?.blocks?.find { it.id == p.blockId }?.weeks?.any { it.id == p.week.id } == true
            if (replaceWeek && weekSaved) {
                // Explicit replacement (§2): the saved week and its recorded sessions change together. If the deletion
                // fails, the previous program is restored before the error reaches the user.
                replaced = true
                val before = programs.replaceProgram(ProposalRules.appliedReplacement(p, program!!))
                deleted = try { deleteWeekRecords(p.programId, p.week.id) } catch (e: Exception) {
                    programs.replaceAll(before)
                    throw ProposalException("No se pudieron borrar los registros de la semana. Se conserva la planificación anterior. ${e.message.orEmpty()}".trim())
                }
            } else {
                // With an activated plan, week 1 creates the program and a block's first week creates that block.
                val applied = ProposalRules.appliedProgram(p, program, outlines().find { it.programId == p.programId })
                    ?: throw ProposalException("El programa o el bloque de la propuesta ya no existe.")
                try { programs.importProgram(applied) }
                catch (e: ProgramValidationException) { throw ProposalException(e.message ?: "Conflicto al añadir la semana.") }
            }
        }
        record(importId, evaluation, DecisionStatus.APPROVED, replaced, deleted)
    }

    /** Explicit rejection; nothing in the program changes. */
    suspend fun reject(importId: String): ProposalDecision = mutex.withLock {
        decisionFor(importId)?.let { if (it.status == DecisionStatus.REJECTED) return@withLock it else throw ProposalException("La propuesta ya fue aprobada.") }
        val evaluation = evaluate(importId)
        otherVersionDecision(evaluation.proposal.id, evaluation.proposal.version)
        record(importId, evaluation, DecisionStatus.REJECTED)
    }

    /** Records what an approved recommendation is expected to achieve; the window starts at the decision. */
    suspend fun addExpectation(e: Expectation) = mutex.withLock {
        val decision = decisionFor(e.importId)
        if (decision?.status != DecisionStatus.APPROVED) throw ProposalException("Solo se evalúan recomendaciones aprobadas.")
        val expectation = e.copy(startMillis = decision.decidedAtMillis)
        try { expectation.validate() } catch (ex: IllegalArgumentException) { throw ProposalException(ex.message ?: "Expectativa no válida.") }
        mutableState.value = store.updateData { s ->
            if (s.expectations.any { it.id == expectation.id }) s else s.copy(expectations = s.expectations + expectation)
        }
    }

    /** Closes (or reopens with null) an expectation with the user's own judgement. */
    suspend fun setManualOutcome(id: String, outcome: Outcome?, note: String?) = mutex.withLock {
        val clean = note?.trim()?.ifBlank { null }
        if (clean != null && clean.length > 1000) throw ProposalException("La nota supera 1000 caracteres.")
        mutableState.value = store.updateData { s ->
            if (s.expectations.none { it.id == id }) throw ProposalException("Expectativa no encontrada.")
            s.copy(expectations = s.expectations.map { if (it.id == id) it.copy(manualOutcome = outcome, note = clean) else it })
        }
    }

    suspend fun updateRules(rules: RuleSettings) = mutex.withLock {
        rules.validate()
        mutableState.value = store.updateData { it.copy(rules = rules) }
    }

    private fun otherVersionDecision(proposalId: String, version: Int) {
        if (mutableState.value.decisions.any { it.proposalId == proposalId && it.version == version })
            throw ProposalException("Ya existe una decisión para la propuesta $proposalId versión $version importada con otro importId.")
    }

    private suspend fun record(importId: String, e: ProposalEvaluation, status: DecisionStatus,
                               replacedWeek: Boolean = false, deletedRecords: Int = 0): ProposalDecision {
        val p = e.proposal
        val decision = ProposalDecision(importId, p.id, p.version, status, now(), p.programId, p.blockId, p.week.id,
            e.warnings.map { it.message }, e.differences, p.differences, p.reasons, e.explanations, replacedWeek, deletedRecords)
        mutableState.value = store.updateData { it.copy(decisions = it.decisions + decision) }
        return decision
    }
}
