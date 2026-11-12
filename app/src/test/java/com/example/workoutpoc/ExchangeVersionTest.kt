package com.example.workoutpoc

import com.example.workoutpoc.exchange.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Versioning of interchange packages: this app writes v2 and still reads v1 packages. */
class ExchangeVersionTest {
    private val v1 = File("src/main/assets/examples/plan-outline-v1.json").readText()

    @Test fun `packages written by this app carry the current schema version`() {
        assertEquals(2, ExchangeCodec.SCHEMA_VERSION)
        val written = ExchangeCodec.encode(ExchangeCodec.decode(v1).copy(schemaVersion = ExchangeCodec.SCHEMA_VERSION))
        assertTrue(written.contains("\"schemaVersion\": 2"))
    }

    @Test fun `v1 packages stay readable`() {
        assertEquals(1, ExchangeCodec.decode(v1).schemaVersion)
    }

    @Test fun `an unknown version is refused with a message that asks to update the app`() {
        val future = v1.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 3")
        val error = runCatching { ExchangeCodec.decode(future) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("Actualiza la aplicación"))
    }
}
