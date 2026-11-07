package com.example.workoutpoc

import com.example.workoutpoc.migration.*
import com.example.workoutpoc.planning.*
import org.junit.Assert.*
import org.junit.Test

/** ENT-03 follow-up: a reviewed migration becomes a program week with exact values only, never guessed. */
class MigrationConversionTest {
    private fun fixture(week: Int) = javaClass.classLoader!!.getResourceAsStream("examples/obsidian-week-v3-w$week.md")!!
        .bufferedReader().use { it.readText() }
    private fun reviewed(week: Int, text: String = fixture(week)) =
        ObsidianParser.parse(text, "synthetic-w$week.md").let { d -> d.copy(issues = d.issues.map { it.copy(acknowledged = true) }) }

    @Test fun `pending warnings block conversion`() {
        val d = ObsidianParser.parse(fixture(2), "synthetic.md")
        assertTrue(d.issues.isNotEmpty())
        val report = MigrationConversion.convert(d)
        assertNull(report.program)
        assertTrue(report.blockers.single().contains("${d.issues.size} advertencias pendientes"))
    }

    @Test fun `only exact values become prescriptions and everything else is reported`() {
        val report = MigrationConversion.convert(reviewed(2))
        val p = report.program!!
        assertEquals("obsidian-programa-ficticio-faro", p.id)
        assertEquals("Programa ficticio Faro", p.name)
        val block = p.blocks.single(); val week = block.weeks.single()
        assertEquals("Bloque 1", block.name); assertEquals("Semana 2", week.name); assertEquals("obsidian-programa-ficticio-faro-w2", week.id)
        assertEquals(listOf("Día A — Fuerza", "Día B — Fuerza"), week.days.map { it.name })

        val press = week.days[0].exercises[0]
        assertEquals("Press ficticio", press.name); assertEquals("obsidian-press-ficticio", press.exerciseId)
        assertEquals(3, press.sets.size)
        // 8–12 reps and RIR 2–3 are ranges: left unset rather than picking a bound.
        assertTrue(press.sets.all { it.reps == null && it.rir == null })
        assertEquals(PrescribedLoad(12.5, LoadUnit.LB, LoadBasis.PER_SIDE), press.sets[0].load)
        assertEquals(PrescribedLoad(50.0, LoadUnit.KG, LoadBasis.TOTAL), week.days[0].exercises[1].sets[0].load)
        val accessory = week.days[0].exercises[2]
        assertTrue(accessory.sets.all { it.reps == 10 && it.rir == 3 && it.load == null }) // "~20 kg" is approximate
        val squat = week.days[1].exercises.single()
        assertEquals(PrescribedLoad(0.0, LoadUnit.BODYWEIGHT, LoadBasis.BODYWEIGHT), squat.sets[0].load)
        assertEquals(listOf(10, 10), squat.sets.map { it.reps })

        assertTrue(report.dropped.any { it.contains("cardio no se convierte") })
        assertTrue(report.dropped.any { it.contains("«~20 kg»") })
        assertTrue(report.dropped.any { it.contains("por lado") })
        assertTrue(report.dropped.any { it.contains("resultados reales") })
        ProgramCodec.validate(p)
    }

    @Test fun `successive weeks merge into one program idempotently`() {
        val w1 = MigrationConversion.convert(reviewed(1)).program!!
        val w2 = MigrationConversion.convert(reviewed(2)).program!!
        assertEquals(w1.id, w2.id)
        assertEquals(MergeKind.NEW, ProgramMerge.plan(null, w1).kind)
        val merged = ProgramMerge.plan(w1, w2)
        assertEquals(MergeKind.ADDS_WEEKS, merged.kind)
        assertEquals(listOf("Semana 1", "Semana 2"), merged.program.blocks.single().weeks.map { it.name })
        assertEquals(MergeKind.IDENTICAL, ProgramMerge.plan(merged.program, w2).kind)
        // Conversion is deterministic: the same document always yields the same program.
        assertEquals(w2, MigrationConversion.convert(reviewed(2)).program)
    }

    @Test fun `unknown set counts block and missing metadata is reported, not invented`() {
        val text = "# Día X\n## Fuerza\n| Ejercicio | Objetivo | Carga |\n| --- | --- | --- |\n| Curl ficticio | series libres | 10 kg total |\n"
        val blocked = MigrationConversion.convert(reviewed(0, text))
        assertNull(blocked.program)
        assertTrue(blocked.blockers.any { it.contains("número de series desconocido") })

        val ok = MigrationConversion.convert(reviewed(0, text.replace("series libres", "2×10")))
        val p = ok.program!!
        assertEquals("Bloque sin número", p.blocks.single().name)
        assertTrue(p.blocks.single().weeks.single().id.contains("-m"))
        assertTrue(ok.dropped.any { it.contains("Sin número de bloque") })
        assertTrue(ok.dropped.any { it.contains("Sin número de semana") })
    }

    @Test fun `slugs fold accents and never produce an empty id`() {
        assertEquals("dia-a-fuerza", MigrationConversion.slug("Día A — Fuerza", 100))
        assertEquals("sin-nombre", MigrationConversion.slug("—", 100))
    }
}
