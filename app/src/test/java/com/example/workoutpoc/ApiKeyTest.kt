package com.example.workoutpoc

import com.example.workoutpoc.agent.AgentException
import com.example.workoutpoc.agent.ApiKeyRepository
import com.example.workoutpoc.agent.ApiKeyStatus
import com.example.workoutpoc.agent.ApiKeyVault
import com.example.workoutpoc.agent.ApiKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** The Claude API key: obvious mistakes are refused, and screens only ever learn its last characters. */
class ApiKeyTest {
    private val key = "sk-ant-api03-synthetic-test-key-0000000000wxyz"

    private class MemoryVault(var stored: String? = null, var broken: Boolean = false) : ApiKeyVault {
        override fun read(): String? { check(!broken) { "unreadable" }; return stored }
        override fun write(key: String) { stored = key; broken = false }
        override fun clear() { stored = null; broken = false }
    }

    @Test fun `a pasted key is trimmed and obvious mistakes are refused`() {
        assertEquals(key, ApiKeys.clean("  $key\n"))
        listOf("", "   ", "agk1.k-123.c2VjcmV0c2VjcmV0c2VjcmV0", "sk-ant-admin01-0000000000000000", "sk-ant-short", "sk-ant-api03 with spaces inside 000000",
            "sk-ant-api03-ñandú-0000000000000000").forEach { bad ->
            try { ApiKeys.clean(bad); fail("Accepted '$bad'") } catch (_: AgentException) { }
        }
    }

    @Test fun `saving, restoring and clearing expose only the hint`() = runTest {
        val vault = MemoryVault()
        val repo = ApiKeyRepository(vault, Dispatchers.Unconfined)
        repo.restore()
        assertEquals(ApiKeyStatus(), repo.status.value)
        assertNull(repo.key())
        repo.save(" $key ")
        assertEquals(ApiKeyStatus(true, "…wxyz"), repo.status.value)
        assertEquals(key, vault.stored)
        val reopened = ApiKeyRepository(vault, Dispatchers.Unconfined).also { it.restore() }
        assertEquals(ApiKeyStatus(true, "…wxyz"), reopened.status.value)
        assertEquals(key, reopened.key())
        reopened.clear()
        assertNull(vault.stored)
        assertEquals(ApiKeyStatus(), reopened.status.value)
    }

    @Test fun `a refused key replaces nothing`() = runTest {
        val vault = MemoryVault(key)
        val repo = ApiKeyRepository(vault, Dispatchers.Unconfined).also { it.restore() }
        try { repo.save("agk1.k-123.c2VjcmV0c2VjcmV0c2VjcmV0"); fail("Accepted a pairing key") } catch (_: AgentException) { }
        assertEquals(key, vault.stored)
        assertEquals(ApiKeyStatus(true, "…wxyz"), repo.status.value)
    }

    @Test fun `an unreadable stored key is reported as absent and can be replaced`() = runTest {
        val vault = MemoryVault(key, broken = true)
        val repo = ApiKeyRepository(vault, Dispatchers.Unconfined)
        try { repo.restore(); fail("Expected the stored key to be unreadable") } catch (e: AgentException) { assertTrue(e.message!!.contains("Vuelve a pegarla")) }
        assertFalse(repo.status.value.present)
        repo.save(key)
        assertEquals(key, repo.key())
    }
}
