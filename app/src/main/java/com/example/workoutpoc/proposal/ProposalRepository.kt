package com.example.workoutpoc.proposal

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.example.workoutpoc.exchange.ExchangeRepository
import com.example.workoutpoc.exchange.ExchangeType
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
    val warnings: List<String>, val differences: List<String>, val statedDifferences: List<String>, val reasons: List<String>
)

@Serializable data class ProposalState(
    val schemaVersion: Int = 1, val rules: RuleSettings = RuleSettings(), val decisions: List<ProposalDecision> = emptyList()
)

object ProposalStateSerializer : Serializer<ProposalState> {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    override val defaultValue = ProposalState()
    fun validate(s: ProposalState) {
        require(s.schemaVersion == 1) { "Versión no compatible." }
        s.rules.validate()
        require(s.decisions.map { it.importId }.distinct().size == s.decisions.size) { "Decisiones duplicadas." }
        require(s.decisions.map { it.proposalId to it.version }.distinct().size == s.decisions.size) { "Decisiones duplicadas por versión." }
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
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ProposalState())
    val state = mutableState.asStateFlow()

    suspend fun restore() = mutex.withLock { mutableState.value = store.data.first() }

    suspend fun evaluate(importId: String): ProposalEvaluation {
        programs.restore(); exchanges.restore()
        val pkg = exchanges.archive.value.packages.find { it.importId == importId && it.type == ExchangeType.WeekProposal }
            ?: throw ProposalException("La propuesta no está guardada.")
        return ProposalRules.evaluate(pkg.proposal!!, programs.library.value, knownExerciseIds(), mutableState.value.rules)
    }

    fun decisionFor(importId: String): ProposalDecision? = mutableState.value.decisions.find { it.importId == importId }

    /**
     * Explicit approval. Re-checks the rules at decision time; blocking issues prevent it. The week is appended
     * idempotently (an interrupted earlier approval is completed, never duplicated) before the decision is recorded.
     */
    suspend fun approve(importId: String): ProposalDecision = mutex.withLock {
        decisionFor(importId)?.let { if (it.status == DecisionStatus.APPROVED) return@withLock it else throw ProposalException("La propuesta ya fue rechazada.") }
        val evaluation = evaluate(importId)
        if (!evaluation.canApprove) throw ProposalException("No se puede aprobar: hay ${evaluation.blocking.size} bloqueos sin resolver.")
        val p = evaluation.proposal
        otherVersionDecision(p.id, p.version)
        if (!evaluation.alreadyApplied) {
            val program = programs.library.value.programs.single { it.id == p.programId }
            try {
                programs.importProgram(program.copy(blocks = program.blocks.map { if (it.id == p.blockId) it.copy(weeks = it.weeks + p.week) else it }))
            } catch (e: ProgramValidationException) { throw ProposalException(e.message ?: "Conflicto al añadir la semana.") }
        }
        record(importId, evaluation, DecisionStatus.APPROVED)
    }

    /** Explicit rejection; nothing in the program changes. */
    suspend fun reject(importId: String): ProposalDecision = mutex.withLock {
        decisionFor(importId)?.let { if (it.status == DecisionStatus.REJECTED) return@withLock it else throw ProposalException("La propuesta ya fue aprobada.") }
        val evaluation = evaluate(importId)
        otherVersionDecision(evaluation.proposal.id, evaluation.proposal.version)
        record(importId, evaluation, DecisionStatus.REJECTED)
    }

    suspend fun updateRules(rules: RuleSettings) = mutex.withLock {
        rules.validate()
        mutableState.value = store.updateData { it.copy(rules = rules) }
    }

    private fun otherVersionDecision(proposalId: String, version: Int) {
        if (mutableState.value.decisions.any { it.proposalId == proposalId && it.version == version })
            throw ProposalException("Ya existe una decisión para la propuesta $proposalId versión $version importada con otro importId.")
    }

    private suspend fun record(importId: String, e: ProposalEvaluation, status: DecisionStatus): ProposalDecision {
        val p = e.proposal
        val decision = ProposalDecision(importId, p.id, p.version, status, now(), p.programId, p.blockId, p.week.id,
            e.warnings.map { it.message }, e.differences, p.differences, p.reasons)
        mutableState.value = store.updateData { it.copy(decisions = it.decisions + decision) }
        return decision
    }
}
