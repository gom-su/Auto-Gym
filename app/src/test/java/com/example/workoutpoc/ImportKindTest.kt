package com.example.workoutpoc

import com.example.workoutpoc.exchange.ImportKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The single Importar sends each file to the screen that can review it, judged only by its shape. */
class ImportKindTest {
    private fun asset(name: String) = javaClass.classLoader!!.getResourceAsStream("examples/$name")!!.bufferedReader().use { it.readText() }

    @Test fun `program json goes to Plan`() = assertEquals(ImportKind.PROGRAM, ImportKind.detect(asset("variable-program-v1.json")))

    @Test fun `interchange packages go to Intercambio`() = assertEquals(ImportKind.EXCHANGE, ImportKind.detect(asset("plan-outline-v1.json")))

    @Test fun `markdown goes to Obsidian migration`() =
        assertEquals(ImportKind.MARKDOWN, ImportKind.detect("# Semana 1\n\n## Día A\n| Ejercicio | Series |\n"))

    @Test fun `json that is not an interchange package, even broken, is reviewed as a program`() {
        assertEquals(ImportKind.PROGRAM, ImportKind.detect("{\"schemaVersion\": 1, "))
        assertEquals(ImportKind.PROGRAM, ImportKind.detect("{}"))
    }
}
