package com.example.workoutpoc.catalog

import com.example.workoutpoc.planning.LoadBasis
import com.example.workoutpoc.planning.LoadUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable enum class Equipment { BARBELL, DUMBBELL, MACHINE, CABLE, BODYWEIGHT, KETTLEBELL, BAND, OTHER }

/**
 * One exercise identity. [id] is the stable key used by prescriptions, records and history; [name] is only its
 * current label. Earlier labels are kept in [aliases]. Entries with the same movement but different equipment,
 * variant or setup are distinct exercises, never merged.
 */
@Serializable data class CatalogExercise(
    val id: String, val name: String,
    val movement: String? = null, val variant: String? = null, val equipment: Equipment? = null,
    /** Usual load base for this exercise; informative only, never used to fill a missing prescription. */
    val loadBasis: LoadBasis? = null,
    /** Machine configuration or setup notes, e.g. seat height. */
    val setup: String? = null,
    val aliases: List<String> = emptyList(),
    /** Unit the user last recorded this exercise in (contract §2). Only KG or LB; null = no preference yet. */
    val preferredUnit: LoadUnit? = null
)

@Serializable data class ExerciseCatalog(val schemaVersion: Int = 1, val exercises: List<CatalogExercise> = emptyList())

class CatalogException(message: String) : IllegalArgumentException(message)

object CatalogCodec {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

    private fun check(ok: Boolean, message: String) { if (!ok) throw CatalogException(message) }
    private fun text(value: String?, max: Int, what: String) =
        check(value == null || (value.isNotBlank() && value == value.trim() && value.length <= max), "$what vacío, con espacios sobrantes o de más de $max caracteres.")

    /** Two labels are confusable when they compare equal ignoring case and surrounding spaces. */
    fun key(name: String, variant: String?, equipment: Equipment?) =
        listOf(name.trim().lowercase(), variant?.trim()?.lowercase().orEmpty(), equipment?.name.orEmpty()).joinToString("\u0000")

    fun validate(e: CatalogExercise) {
        check(e.id.matches(idPattern), "ID de ejercicio no válido.")
        check(e.name.isNotBlank() && e.name == e.name.trim() && e.name.length <= 200, "El nombre debe tener entre 1 y 200 caracteres.")
        text(e.movement, 200, "Movimiento"); text(e.variant, 200, "Variante"); text(e.setup, 1000, "Configuración")
        check(e.aliases.size <= 50 && e.aliases.distinct().size == e.aliases.size, "Nombres anteriores repetidos o demasiados.")
        e.aliases.forEach { text(it, 200, "Nombre anterior") }
        check(e.name !in e.aliases, "El nombre actual no puede figurar como nombre anterior.")
        check(e.preferredUnit == null || e.preferredUnit == LoadUnit.KG || e.preferredUnit == LoadUnit.LB,
            "La unidad preferida solo puede ser kg o lb.")
        check(e.equipment != Equipment.BODYWEIGHT || e.loadBasis == null || e.loadBasis == LoadBasis.BODYWEIGHT,
            "Un ejercicio de peso corporal solo admite base de peso corporal.")
        check(e.equipment != Equipment.MACHINE || e.loadBasis == null || e.loadBasis in setOf(LoadBasis.MACHINE_SETTING, LoadBasis.TOTAL, LoadBasis.PER_SIDE),
            "Base de carga incompatible con una máquina.")
    }

    fun validate(c: ExerciseCatalog) {
        check(c.schemaVersion == 1, "Versión de catálogo no compatible.")
        check(c.exercises.size <= 5000, "Demasiados ejercicios.")
        check(c.exercises.map { it.id }.distinct().size == c.exercises.size, "IDs de ejercicio duplicados.")
        c.exercises.forEach(::validate)
    }

    /** Exercises that share name, variant and equipment cannot be told apart in lists; return their IDs. */
    fun ambiguous(c: ExerciseCatalog): Set<String> =
        c.exercises.groupBy { key(it.name, it.variant, it.equipment) }.values.filter { it.size > 1 }.flatten().map { it.id }.toSet()
}
