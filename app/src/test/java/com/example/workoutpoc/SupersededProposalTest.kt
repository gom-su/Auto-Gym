package com.example.workoutpoc

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.example.workoutpoc.exchange.*
import com.example.workoutpoc.planning.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Contract §2: a regenerated pending proposal leaves one pending version; other packages are untouched. */
class SupersededProposalTest {
    @get:Rule val folder = TemporaryFolder()

    private fun pkgOf(importId: String) = ExchangePackage(1, ExchangeType.WeekProposal, importId, "2026-11-06T12:00:00Z",
        proposal = WeekProposal("proposal-next", 1, "prog", "block-base", "Agente ficticio",
            WeekPlan("week-two", "Semana 2", 1, listOf(SessionPlan("d", "Día A", emptyList(), cardio = CardioPrescription("Bici", 1800)))),
            emptyList(), emptyList()))

    @Test fun `removing a superseded proposal keeps the new version and the other packages`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = folder.newFolder().resolve("exchanges.json")
        val store = DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM, object : OkioSerializer<ExchangeArchive> {
            override val defaultValue = ExchangeArchiveSerializer.defaultValue
            override suspend fun readFrom(source: BufferedSource) = ExchangeArchiveSerializer.readFrom(source.inputStream())
            override suspend fun writeTo(t: ExchangeArchive, sink: BufferedSink) = ExchangeArchiveSerializer.writeTo(t, sink.outputStream())
        }) { file.absolutePath.toPath() }, scope = scope)
        val exchanges = ExchangeRepository(store)
        exchanges.restore()
        exchanges.save(pkgOf("old-version"))
        exchanges.save(pkgOf("new-version"))
        exchanges.save(pkgOf("other-week"))

        exchanges.removeSuperseded("old-version")
        exchanges.restore()

        assertEquals(listOf("new-version", "other-week"), exchanges.archive.value.packages.map { it.importId })
        scope.cancel()
    }
}
