package com.example.workoutpoc.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.example.workoutpoc.domain.Phase
import com.example.workoutpoc.domain.PlannedSet
import com.example.workoutpoc.domain.SetStatus
import com.example.workoutpoc.domain.SetValues
import com.example.workoutpoc.domain.MAX_NOTE_LENGTH
import com.example.workoutpoc.domain.WorkoutState
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

interface WorkoutStorage {
    suspend fun read(): WorkoutState
    suspend fun write(state: WorkoutState)
}

/** Atomic DataStore snapshot; the repository publishes only after a successful write. */
class DataStoreWorkoutStorage(private val store: DataStore<WorkoutState>) : WorkoutStorage {
    override suspend fun read(): WorkoutState = store.data.first()
    override suspend fun write(state: WorkoutState) { store.updateData { state } }
}

fun WorkoutState.activePlan(): List<PlannedSet> = plan.ifEmpty { DemoWorkout.sets }

/** Invariants shared by the live snapshot and archived sessions. Throws IllegalArgumentException. */
object WorkoutValidation {
    fun validate(s: WorkoutState) {
        val plan = s.activePlan()
        require(plan.size <= 1000)
        plan.forEach { p ->
            require(p.exercise.isNotBlank() && p.exercise.length <= 200 && p.total >= 1 && p.number >= 1 && (p.extra || p.number <= p.total))
            require(p.weightStepTenths > 0 && p.maxWeightTenths in 0..1_000_000 && p.weightTenths in 0..p.maxWeightTenths)
            require(p.maxReps in 1..1000 && p.reps in 0..p.maxReps && (p.rir == null || p.rir in 0..4))
            require(p.restSeconds in 0..86400)
            require(!p.extra || (p.prescriptionId == null && !p.loadPrescribed))
        }
        require((s.plan.isEmpty()) == (s.source == null))
        require(s.index in plan.indices)
        values(s.values, plan[s.index], allowZeroReps = true)
        require(s.records.map { it.index } == (0 until s.records.size).toList())
        require(s.records.size <= plan.size)
        s.records.forEach { r ->
            values(r.values, plan[r.index], allowZeroReps = r.status == SetStatus.SKIPPED)
            require(r.prescriptionId == plan[r.index].prescriptionId)
            require(r.notes == null || (r.notes.isNotBlank() && r.notes.length <= MAX_NOTE_LENGTH))
            // Deviations exist only for planned sessions; the legacy demo shape stays exportable.
            require(s.source != null || (r.status == SetStatus.COMPLETED && r.notes == null && r.editedAtMillis == null))
        }
        require((s.phase == Phase.Resting) == (s.rest != null))
    }
    private fun values(v: SetValues, p: PlannedSet, allowZeroReps: Boolean) {
        require(v.weightTenths in 0..p.maxWeightTenths)
        require(v.reps in (if (allowZeroReps) 0 else 1)..p.maxReps)
        require(v.rir == null || v.rir in 0..4)
    }
}

object WorkoutSerializer : Serializer<WorkoutState> {
    internal val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override val defaultValue: WorkoutState = WorkoutState()
    override suspend fun readFrom(input: InputStream): WorkoutState = try {
        json.decodeFromString<WorkoutState>(input.readBytes().decodeToString()).also(WorkoutValidation::validate)
    } catch (e: SerializationException) { throw CorruptionException("Sesión ilegible", e) }
      catch (e: IllegalArgumentException) { throw CorruptionException("Sesión inválida", e) }

    override suspend fun writeTo(t: WorkoutState, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}

/** Finished or replaced sessions, newest last. Identity is the session ID; archiving is idempotent. */
@Serializable
data class WorkoutHistory(val schemaVersion: Int = 1, val sessions: List<WorkoutState> = emptyList())

interface SessionHistory {
    suspend fun read(): WorkoutHistory
    /** Returns false when the session was already archived. */
    suspend fun archive(session: WorkoutState): Boolean
}

class MemorySessionHistory : SessionHistory {
    private var history = WorkoutHistory()
    override suspend fun read() = history
    override suspend fun archive(session: WorkoutState): Boolean {
        if (history.sessions.any { it.sessionId == session.sessionId }) return false
        history = history.copy(sessions = history.sessions + session); return true
    }
}

class DataStoreSessionHistory(private val store: DataStore<WorkoutHistory>) : SessionHistory {
    override suspend fun read(): WorkoutHistory = store.data.first()
    override suspend fun archive(session: WorkoutState): Boolean {
        WorkoutValidation.validate(session)
        var added = false
        store.updateData { current ->
            if (current.sessions.any { it.sessionId == session.sessionId }) current
            else current.copy(sessions = current.sessions + session).also { added = true }
        }
        return added
    }
}

object WorkoutHistorySerializer : Serializer<WorkoutHistory> {
    override val defaultValue = WorkoutHistory()
    override suspend fun readFrom(input: InputStream): WorkoutHistory = try {
        WorkoutSerializer.json.decodeFromString<WorkoutHistory>(input.readBytes().decodeToString()).also { h ->
            require(h.schemaVersion == 1)
            require(h.sessions.map { it.sessionId }.distinct().size == h.sessions.size)
            h.sessions.forEach(WorkoutValidation::validate)
        }
    } catch (e: SerializationException) { throw CorruptionException("Historial ilegible. Se conserva sin reemplazar.", e) }
      catch (e: IllegalArgumentException) { throw CorruptionException("Historial inválido. Se conserva sin reemplazar.", e) }
    override suspend fun writeTo(t: WorkoutHistory, output: OutputStream) {
        output.write(WorkoutSerializer.json.encodeToString(t).encodeToByteArray())
    }
}
