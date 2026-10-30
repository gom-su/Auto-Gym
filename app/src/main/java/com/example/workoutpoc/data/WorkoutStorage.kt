package com.example.workoutpoc.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.example.workoutpoc.domain.WorkoutState
import kotlinx.coroutines.flow.first
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

object WorkoutSerializer : Serializer<WorkoutState> {
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override val defaultValue: WorkoutState = WorkoutState()
    override suspend fun readFrom(input: InputStream): WorkoutState = try {
        json.decodeFromString<WorkoutState>(input.readBytes().decodeToString()).also { s ->
            require(s.index in DemoWorkout.sets.indices)
            require(s.values.weightTenths in 0..3000 && s.values.weightTenths % 25 == 0)
            require(s.values.reps in 0..30 && s.values.rir in 0..4)
            require(s.records.map { it.index } == (0 until s.records.size).toList())
            require(s.records.size <= DemoWorkout.sets.size)
            require(s.records.all { it.values.reps in 1..30 && it.values.rir in 0..4 &&
                it.values.weightTenths in 0..3000 && it.values.weightTenths % 25 == 0 })
            require((s.phase == com.example.workoutpoc.domain.Phase.Resting) == (s.rest != null))
        }
    } catch (e: SerializationException) { throw CorruptionException("Sesión ilegible", e) }
      catch (e: IllegalArgumentException) { throw CorruptionException("Sesión inválida", e) }

    override suspend fun writeTo(t: WorkoutState, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}
