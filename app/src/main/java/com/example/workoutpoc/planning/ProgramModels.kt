package com.example.workoutpoc.planning

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable data class ProgramPackage(val schemaVersion: Int, val program: TrainingProgram)
@Serializable data class TrainingProgram(val id: String, val name: String, val blocks: List<TrainingBlock>)
@Serializable data class TrainingBlock(val id: String, val name: String, val weeks: List<WeekPlan>)
@Serializable data class WeekPlan(val id: String, val name: String, val version: Int, val days: List<SessionPlan>)
@Serializable data class SessionPlan(val id: String, val name: String, val exercises: List<ExercisePrescription>)
@Serializable data class ExercisePrescription(
    val id: String, val exerciseId: String, val name: String, val sets: List<SetPrescription>
)
@Serializable data class SetPrescription(
    val id: String, val reps: Int? = null, val rir: Int? = null,
    val load: PrescribedLoad? = null, val restSeconds: Int? = null
)
@Serializable data class PrescribedLoad(val value: Double, val unit: LoadUnit, val basis: LoadBasis)
@Serializable enum class LoadUnit { KG, LB, MACHINE_SETTING, BODYWEIGHT }
@Serializable enum class LoadBasis { TOTAL, PER_SIDE, PER_HAND, BODYWEIGHT, MACHINE_SETTING }
@Serializable data class ProgramLibrary(val schemaVersion: Int, val programs: List<TrainingProgram>)

class ProgramValidationException(message: String) : IllegalArgumentException(message)

/** Strict, versioned aggregate: list order is prescription order; identity never depends on names. */
object ProgramCodec {
    const val MAX_BYTES = 1_048_576
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun decode(text: String): TrainingProgram {
        check(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES, "El archivo supera 1 MiB.")
        val data = try { json.decodeFromString<ProgramPackage>(text) }
        catch (_: SerializationException) {
            throw ProgramValidationException("JSON incompatible: revisa los campos, tipos y valores del formato de programa v1.")
        } catch (_: IllegalArgumentException) {
            throw ProgramValidationException("El archivo no contiene un programa JSON válido.")
        }
        check(data.schemaVersion == 1, "Versión de formato no compatible: ${data.schemaVersion}. Se requiere 1.")
        validate(data.program)
        return data.program
    }

    fun validate(program: TrainingProgram) {
        val ids = mutableSetOf<String>()
        fun identity(id: String, path: String) {
            check(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")), "$path: ID no válido.")
            check(ids.add(id), "$path: ID duplicado '$id'.")
        }
        fun name(value: String, path: String) {
            check(value.isNotBlank() && value.length <= 200, "$path: el nombre debe tener entre 1 y 200 caracteres.")
        }
        identity(program.id, "Programa"); name(program.name, "Programa")
        check(program.blocks.isNotEmpty(), "El programa necesita al menos un bloque.")
        program.blocks.forEachIndexed { b, block ->
            val bp = "Bloque ${b + 1}"
            identity(block.id, bp); name(block.name, bp)
            check(block.weeks.isNotEmpty(), "$bp: falta una semana.")
            block.weeks.forEachIndexed { w, week ->
                val wp = "$bp / semana ${w + 1}"
                identity(week.id, wp); name(week.name, wp)
                check(week.version > 0, "$wp: la versión debe ser positiva.")
                check(week.days.isNotEmpty(), "$wp: falta un día.")
                week.days.forEachIndexed { d, day ->
                    val dp = "$wp / día ${d + 1}"
                    identity(day.id, dp); name(day.name, dp)
                    check(day.exercises.isNotEmpty(), "$dp: falta un ejercicio.")
                    day.exercises.forEachIndexed { e, exercise ->
                        val ep = "$dp / ejercicio ${e + 1}"
                        identity(exercise.id, ep); name(exercise.name, ep)
                        check(exercise.exerciseId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")), "$ep: exerciseId no válido.")
                        check(exercise.sets.isNotEmpty(), "$ep: falta una serie.")
                        exercise.sets.forEachIndexed { s, set ->
                            val sp = "$ep / serie ${s + 1}"
                            identity(set.id, sp)
                            check(set.reps == null || set.reps in 1..1000, "$sp: repeticiones fuera de rango (1–1000).")
                            check(set.rir == null || set.rir in 0..10, "$sp: RIR fuera de rango (0–10).")
                            check(set.restSeconds == null || set.restSeconds in 0..86400, "$sp: descanso fuera de rango (0–86400 s).")
                            set.load?.let { load ->
                                check(load.value.isFinite() && load.value in 0.0..100000.0, "$sp: carga fuera de rango.")
                                check(when (load.unit) {
                                    LoadUnit.KG, LoadUnit.LB -> load.basis in listOf(LoadBasis.TOTAL, LoadBasis.PER_SIDE, LoadBasis.PER_HAND)
                                    LoadUnit.MACHINE_SETTING -> load.basis == LoadBasis.MACHINE_SETTING
                                    LoadUnit.BODYWEIGHT -> load.basis == LoadBasis.BODYWEIGHT
                                }, "$sp: unidad y base de carga incompatibles.")
                            }
                        }
                    }
                }
            }
        }
    }

    fun validateLibrary(library: ProgramLibrary) {
        check(library.schemaVersion == 1, "Versión de biblioteca no compatible.")
        check(library.programs.map { it.id }.distinct().size == library.programs.size, "Programas duplicados en la biblioteca.")
        library.programs.forEach(::validate)
    }

    private fun check(valid: Boolean, message: String) { if (!valid) throw ProgramValidationException(message) }
}
