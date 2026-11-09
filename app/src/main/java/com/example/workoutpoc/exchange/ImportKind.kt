package com.example.workoutpoc.exchange

import com.example.workoutpoc.sync.SignedSync
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Which review screen a file or pasted text belongs to. Only the shape decides; each screen still validates fully. */
enum class ImportKind(val label: String) {
    /** A program JSON v1 (`{"schemaVersion":1,"program":{…}}`), or JSON that is not an interchange package: Plan reviews it and explains what is wrong. */
    PROGRAM("programa"),
    /** An interchange v1 package (it names its `type`) or a signed envelope: Intercambio JSON. */
    EXCHANGE("intercambio"),
    /** Anything that is not a JSON object: an Obsidian Markdown week, reviewed in Migrar desde Obsidian. */
    MARKDOWN("semana en Markdown");

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun detect(text: String): ImportKind {
            if (SignedSync.isEnvelope(text)) return EXCHANGE
            val obj: JsonObject = try { json.parseToJsonElement(text.trim()).jsonObject } catch (_: Exception) {
                // Not JSON at all is Markdown; broken JSON (it starts like an object) is reviewed as a program, which explains the error.
                return if (text.trimStart().startsWith("{")) PROGRAM else MARKDOWN
            }
            return if ("type" in obj) EXCHANGE else PROGRAM
        }
    }
}
