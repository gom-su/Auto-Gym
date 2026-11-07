package com.example.workoutpoc.migration

import com.example.workoutpoc.planning.*
import java.text.Normalizer

/**
 * What converting a reviewed migration into a program v1 week would produce. [program] is null when [blockers] exist.
 * [dropped] lists everything that is kept only in the migration archive, so nothing is lost silently.
 */
data class ConversionReport(val program: TrainingProgram?, val dropped: List<String>, val blockers: List<String>)

/**
 * Deterministic conversion of a saved Workout v3 week into a program week. Only exact values become prescriptions:
 * ranges, approximate loads and unknown units stay unset (never guessed). Identifiers derive from the program name and
 * week metadata, so successive weeks of the same program append to one program through the idempotent import rules.
 * Recorded results are not converted into history.
 */
object MigrationConversion {
    fun convert(d: MigrationDocument): ConversionReport {
        val blockers = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        val pending = d.issues.count { !it.acknowledged }
        if (pending > 0) blockers += "Revisa las $pending advertencias pendientes antes de convertir."
        val programName = (d.metadata["program"] ?: d.title ?: d.source).trim().take(200)
        val programId = "obsidian-" + slug(programName, 60)
        val block = d.metadata["block"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val week = d.metadata["week"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val blockId = "$programId-b${block ?: 0}"
        // Without a week number the document identity keeps the week distinct, never merged by position.
        val weekId = if (week != null) "$programId-w$week" else "$programId-m${d.id.take(16)}"
        if (block == null) dropped += "Sin número de bloque en los metadatos: se usa «Bloque sin número»."
        if (week == null) dropped += "Sin número de semana en los metadatos: la semana se identifica por esta migración."
        val days = d.days.mapIndexedNotNull { i, day ->
            if (day.cardio.isNotEmpty()) dropped += "${day.title}: el cardio no se convierte (el programa v1 no tiene cardio)."
            if (day.exercises.isEmpty()) { dropped += "${day.title}: sin ejercicios; el día no se convierte."; return@mapIndexedNotNull null }
            val dayId = "$weekId-d${i + 1}"
            val exercises = day.exercises.mapIndexedNotNull { j, e ->
                val label = "${day.title} · ${e.name}"
                val count = e.prescription.sets
                if (count == null || count !in 1..100) { blockers += "$label: número de series desconocido («${e.prescription.raw}»)."; return@mapIndexedNotNull null }
                val reps = e.prescription.reps?.exact(1..1000)
                if (reps == null) dropped += "$label: repeticiones «${e.prescription.raw}» sin valor exacto; quedan sin fijar."
                val rir = e.prescription.rir?.exact(0..10)
                if (rir == null && e.prescription.rir != null) dropped += "$label: RIR en rango; queda sin fijar."
                if (e.prescription.perSide) dropped += "$label: «por lado» en el objetivo no se representa en el programa."
                val load = load(e.load)
                if (load == null && e.load.raw.isNotBlank()) dropped += "$label: carga «${e.load.raw.trim()}» no exacta o sin unidad; queda sin fijar."
                val exerciseId = "$dayId-e${j + 1}"
                ExercisePrescription(exerciseId, "obsidian-" + slug(e.name, 100), e.name.trim().take(200),
                    List(count) { k -> SetPrescription("$exerciseId-s${k + 1}", reps, rir, load) })
            }
            SessionPlan(dayId, day.title.trim().take(200), exercises)
        }.filter { it.exercises.isNotEmpty() }
        if (days.isEmpty()) blockers += "No hay días con ejercicios para convertir."
        if (d.days.any { day -> day.exercises.any { it.reps.values.isNotEmpty() || it.rir.values.isNotEmpty() } })
            dropped += "Los resultados reales (reps y RIR registrados) no se convierten en historial; siguen en la migración."
        if (blockers.isNotEmpty()) return ConversionReport(null, dropped, blockers)
        val program = TrainingProgram(programId, programName, listOf(TrainingBlock(blockId, block?.let { "Bloque $it" } ?: "Bloque sin número",
            listOf(WeekPlan(weekId, week?.let { "Semana $it" } ?: (d.title ?: d.source).trim().take(200), 1, days)))))
        return try { ProgramCodec.validate(program); ConversionReport(program, dropped, emptyList()) }
        catch (e: ProgramValidationException) { ConversionReport(null, dropped, listOf("El programa resultante no es válido: ${e.message}")) }
    }

    private fun NumberRange.exact(range: IntRange): Int? =
        min.takeIf { it == max && it == Math.floor(it) && it.toInt() in range }?.toInt()

    private fun load(l: MigrationLoad): PrescribedLoad? = when {
        l.unit == "BODYWEIGHT" -> PrescribedLoad(0.0, LoadUnit.BODYWEIGHT, LoadBasis.BODYWEIGHT)
        l.magnitude == null || l.magnitude.min != l.magnitude.max -> null
        else -> {
            val unit = when (l.unit) { "KG" -> LoadUnit.KG; "LB" -> LoadUnit.LB; else -> null }
            val basis = when (l.basis) { "TOTAL" -> LoadBasis.TOTAL; "PER_SIDE" -> LoadBasis.PER_SIDE; "PER_HAND" -> LoadBasis.PER_HAND; else -> null }
            if (unit == null || basis == null) null else PrescribedLoad(l.magnitude.min, unit, basis)
        }
    }

    /** Lowercase ASCII identifier fragment; accents are folded so "Día" and "Dia" match. */
    fun slug(text: String, max: Int): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(max).trim('-').ifEmpty { "sin-nombre" }
}
