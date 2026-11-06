package com.example.workoutpoc.catalog

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import java.io.InputStream
import java.io.OutputStream

object ExerciseCatalogSerializer : Serializer<ExerciseCatalog> {
    override val defaultValue = ExerciseCatalog()
    override suspend fun readFrom(input: InputStream): ExerciseCatalog = try {
        CatalogCodec.json.decodeFromString<ExerciseCatalog>(input.readBytes().decodeToString()).also(CatalogCodec::validate)
    } catch (e: SerializationException) { throw CorruptionException("Catálogo ilegible. Se conserva sin reemplazar.", e) }
      catch (e: IllegalArgumentException) { throw CorruptionException("Catálogo inválido. Se conserva sin reemplazar.", e) }
    override suspend fun writeTo(t: ExerciseCatalog, output: OutputStream) {
        CatalogCodec.validate(t)
        output.write(CatalogCodec.json.encodeToString(t).toByteArray(Charsets.UTF_8))
    }
}

/** A name seen for an exercise ID in a program, session or history. */
data class ExerciseReference(val id: String, val name: String)

class CatalogRepository(private val store: DataStore<ExerciseCatalog>) {
    private val mutex = Mutex()
    private val mutableCatalog = MutableStateFlow(ExerciseCatalog())
    val catalog = mutableCatalog.asStateFlow()

    suspend fun restore() = mutex.withLock { mutableCatalog.value = store.data.first() }

    /**
     * Registers every referenced exercise ID that is not yet catalogued, using the name it was referenced with.
     * Existing entries are never renamed by a reference: a different referenced name is kept as an alias.
     * Nothing else (movement, equipment, load base) is inferred. Returns the number of new entries.
     */
    suspend fun sync(references: List<ExerciseReference>): Int = mutex.withLock {
        var added = 0
        val saved = store.updateData { current ->
            val byId = current.exercises.associateBy { it.id }.toMutableMap()
            val order = current.exercises.map { it.id }.toMutableList()
            references.forEach { ref ->
                val name = ref.name.trim().take(200)
                if (name.isEmpty() || !ref.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) return@forEach
                val existing = byId[ref.id]
                if (existing == null) { byId[ref.id] = CatalogExercise(ref.id, name); order += ref.id; added++ }
                else if (name != existing.name && name !in existing.aliases && existing.aliases.size < 50)
                    byId[ref.id] = existing.copy(aliases = existing.aliases + name)
            }
            val next = current.copy(exercises = order.map { byId.getValue(it) })
            if (next == current) current else next.also(CatalogCodec::validate)
        }
        mutableCatalog.value = saved
        added
    }

    /**
     * Saves edits to one entry. The ID never changes. A rename moves the previous name to aliases, so history
     * recorded under any earlier name still resolves to the same exercise. Labels that would make two exercises
     * indistinguishable (same name, variant and equipment) are rejected.
     */
    suspend fun update(edited: CatalogExercise) = mutex.withLock {
        val saved = store.updateData { current ->
            val existing = current.exercises.find { it.id == edited.id } ?: throw CatalogException("El ejercicio ya no existe.")
            val name = edited.name.trim()
            val aliases = (existing.aliases + (if (name != existing.name) listOf(existing.name) else emptyList()))
                .filter { it != name }.distinct().takeLast(50)
            val next = edited.copy(name = name, movement = edited.movement?.trim()?.ifEmpty { null },
                variant = edited.variant?.trim()?.ifEmpty { null }, setup = edited.setup?.trim()?.ifEmpty { null }, aliases = aliases)
            CatalogCodec.validate(next)
            val clash = current.exercises.find { it.id != next.id && CatalogCodec.key(it.name, it.variant, it.equipment) == CatalogCodec.key(next.name, next.variant, next.equipment) }
            if (clash != null) throw CatalogException("Ya existe \"${clash.name}\" (${clash.id}) con la misma variante y equipo. Añade una variante o cambia el nombre para distinguirlos.")
            current.copy(exercises = current.exercises.map { if (it.id == next.id) next else it })
        }
        mutableCatalog.value = saved
    }
}
