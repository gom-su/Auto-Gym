package com.example.workoutpoc.migration

import java.security.MessageDigest

/** Deliberately bounded Workout-v3 dialect. Original text is authoritative, never executable. */
object ObsidianParser {
    const val VERSION = 1
    private fun clean(s: String) = s.replace("**", "").replace("`", "").trim()
    private fun normalized(s: String) = clean(s).lowercase().trim().removeSuffix(":").removeSuffix("*")
    private val rangePattern = Regex("(\\d+(?:\\.\\d+)?)(?:\\s*[–−-]\\s*(\\d+(?:\\.\\d+)?))?")
    private fun number(s: String): NumberRange? {
        val m = rangePattern.matchEntire(s.trim()) ?: return null
        val lo = m.groupValues[1].toDoubleOrNull() ?: return null
        val hi = m.groupValues[2].toDoubleOrNull() ?: lo
        return if (lo.isFinite() && hi.isFinite() && hi >= lo) NumberRange(lo, hi) else null
    }

    fun parse(text: String, source: String): MigrationDocument {
        if (text.isBlank()) throw MigrationException("El Markdown está vacío.")
        if (text.toByteArray(Charsets.UTF_8).size > MigrationCodec.MAX_BYTES) throw MigrationException("El archivo supera 1 MiB.")
        if (source.isBlank() || source.length > 1000) throw MigrationException("Origen de migración no válido.")
        val id = MessageDigest.getInstance("SHA-256").digest("obsidian:$VERSION\u0000$text".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val issues = mutableListOf<MigrationIssue>()
        val issueIds = mutableSetOf<String>()
        fun warn(code: String, line: Int, message: String) {
            val key = "$line:$code"
            if (issueIds.add(key)) issues += MigrationIssue(key, code, line, message)
        }
        val rawLines = text.split('\n')
        val lines = mutableListOf<SourceLine>()
        val metadata = linkedMapOf<String, String>()
        val days = mutableListOf<DayBuilder>()
        val notes = mutableListOf<MigrationComment>()
        var title: String? = null
        var section = ""
        var headers: List<String>? = null
        var frontmatter = rawLines.first().trim().removePrefix("\uFEFF") == "---"
        var frontClosed = !frontmatter
        var fence = false
        var previousMeta: String? = null
        val knownMeta = setOf("program", "week", "block", "week_in_block", "phase", "microcycle", "created", "status", "title")
        val knownColumns = setOf("ejercicio", "objetivo", "carga", "reps reales", "rir real", "notas", "notes", "comentarios gpt", "comentarios usuario", "resultado")

        rawLines.forEachIndexed { index, raw ->
            val line = index + 1
            val s = raw.removeSuffix("\r").trim().removePrefix("\uFEFF")
            var kind = "STRUCTURE"
            if (index == 0 && frontmatter) {
                lines += SourceLine(line, raw, "METADATA"); return@forEachIndexed
            }
            if (frontmatter) {
                kind = "METADATA"
                if (s == "---") { frontmatter = false; frontClosed = true }
                else {
                    val pair = Regex("([A-Za-z_][A-Za-z0-9_-]*):\\s*(.*)").matchEntire(s)
                    if (pair != null) {
                        val key = pair.groupValues[1]
                        if (metadata.containsKey(key)) warn("DUPLICATE_METADATA", line, "Metadato repetido; consulta ambas líneas originales.")
                        else metadata[key] = pair.groupValues[2]
                        previousMeta = key
                        if (key !in knownMeta) warn("UNKNOWN_METADATA", line, "Metadato conservado sin interpretar: $key.")
                    } else if (s.startsWith("- ") && previousMeta != null) {
                        val key = previousMeta!!
                        metadata[key] = metadata.getValue(key) + "\n" + raw
                        warn("UNKNOWN_METADATA", line, "Lista de metadatos conservada sin interpretar.")
                    } else if (s.isNotBlank()) warn("UNKNOWN_METADATA", line, "Metadato no reconocido; se conserva el original.")
                }
            } else if (s.startsWith("```") || s.startsWith("~~~")) {
                fence = !fence; kind = "UNINTERPRETED"
                warn("UNINTERPRETED", line, "Bloque de código conservado sin interpretar.")
            } else if (fence) {
                kind = "UNINTERPRETED"
            } else if (s.startsWith("#")) {
                headers = null
                val heading = clean(s.trimStart('#').trim())
                val day = Regex("^D[ií]a\\s+[A-Za-z0-9]+(?:\\s|[—–:-]|$)", RegexOption.IGNORE_CASE).containsMatchIn(heading)
                if (day) { days += DayBuilder("$id:day:$line", line, heading); section = ""; kind = "DAY" }
                else if (heading.startsWith("Semana", true)) {
                    if (title != null) warn("MULTIPLE_WEEKS", line, "Hay varios encabezados de semana. No se atribuyen los días a semanas por suposición.")
                    else title = heading
                    section = ""; kind = "WEEK"
                }
                else {
                    section = heading; kind = "SECTION"
                    if (!listOf("calentamiento", "fuerza", "cardio", "accesorios", "notas del agente", "formato de registro")
                            .any { heading.contains(it, true) } && heading != metadata["phase"])
                        warn("UNKNOWN_SECTION", line, "Sección conservada sin interpretar: $heading.")
                }
            } else if (s.startsWith("|")) {
                kind = "TABLE"
                val cells = cells(s)
                val next = rawLines.getOrNull(index + 1)?.trim().orEmpty()
                if (isSeparator(next)) {
                    headers = cells.map(::normalized)
                    if (headers!!.distinct().size != headers!!.size) warn("TABLE_HEADER", line, "La tabla contiene columnas repetidas.")
                    headers!!.filter { it !in knownColumns }.forEach { warn("UNKNOWN_COLUMN", line, "Columna sin interpretar: $it.") }
                } else if (!isSeparator(s)) {
                    val head = headers
                    val day = days.lastOrNull()
                    if (head == null || "ejercicio" !in head || day == null || head.distinct().size != head.size || cells.size != head.size) {
                        kind = "UNINTERPRETED"; warn("UNSUPPORTED_TABLE", line, "Fila sin estructura compatible; se conserva sin atribuir datos.")
                    } else {
                        val columns = head.zip(cells).toMap()
                        val name = clean(columns["ejercicio"].orEmpty())
                        if (name.isBlank()) warn("MISSING_EXERCISE", line, "Falta el nombre del ejercicio.")
                        val objective = clean(columns["objetivo"].orEmpty())
                        val p = Regex("(\\d+)\\s*[×x]\\s*(\\d+(?:\\s*[–-]\\s*\\d+)?)(\\s*c/l)?(?:\\s*@\\s*RIR\\s*(\\d+(?:\\s*[–-]\\s*\\d+)?))?", RegexOption.IGNORE_CASE).matchEntire(objective)
                        val count = p?.groupValues?.get(1)?.toIntOrNull()
                        val reps = p?.groupValues?.get(2)?.let(::number)
                        val rir = p?.groupValues?.get(4)?.takeIf { it.isNotBlank() }?.let(::number)
                        if (objective.isNotBlank() && (p == null || count == null || count !in 1..1000 || reps == null || reps.min < 1 || reps.max > 1000 || (rir != null && rir.max > 10)))
                            warn("PRESCRIPTION", line, "Objetivo no interpretable completamente; conserva su expresión original.")
                        if (objective.isBlank()) warn("MISSING_PRESCRIPTION", line, "No hay objetivo explícito.")
                        val prescription = MigrationPrescription(objective, count?.takeIf { it in 1..1000 }, reps?.takeIf { it.min >= 1 && it.max <= 1000 }, rir?.takeIf { it.max <= 10 }, p?.groupValues?.get(3)?.isNotBlank() == true)
                        val load = load(columns["carga"].orEmpty())
                        if (load.raw.isNotBlank() && (load.unit == null || load.basis == null || (load.magnitude == null && load.unit != "BODYWEIGHT")))
                            warn("LOAD", line, "Carga con magnitud, unidad, base o asignación incierta; no se ha completado por suposición.")
                        if (load.magnitude != null && load.magnitude.min != load.magnitude.max) warn("LOAD_RANGE", line, "Rango de carga conservado; no se selecciona una carga exacta.")
                        val actualReps = values(columns["reps reales"] ?: columns["resultado"].orEmpty(), 1000.0, true)
                        val actualRir = values(columns["rir real"].orEmpty(), 10.0, false)
                        listOf("REPS" to actualReps, "RIR" to actualRir).forEach { (code, v) ->
                            if (v.raw.isNotBlank() && (v.values.isEmpty() || v.values.any { it == null })) warn("AMBIGUOUS_$code", line, "Resultados $code con valores desconocidos o ambiguos; consulta el original.")
                            if (v.values.isNotEmpty() && count != null && v.values.size != count) warn("COUNT_$code", line, "Cantidad de resultados $code distinta de las series prescritas; no se recortan ni se rellenan.")
                        }
                        if (actualReps.values.isNotEmpty() && actualRir.values.isNotEmpty() && actualReps.values.size != actualRir.values.size)
                            warn("RESULT_ALIGNMENT", line, "Repeticiones y RIR no tienen la misma cantidad de resultados.")
                        if (columns["carga"].orEmpty().isNotBlank() && (actualReps.raw.isNotBlank() || actualRir.raw.isNotBlank()))
                            warn("LOAD_ROLE", line, "La columna Carga no distingue planificación de ejecución; permanece separada, sin asignarse a series.")
                        val comments = listOf("notas" to null, "notes" to null, "comentarios gpt" to "AGENT", "comentarios usuario" to "USER")
                            .mapNotNull { (key, author) -> columns[key]?.takeIf { it.isNotBlank() }?.let { MigrationComment(line, it, author) } }
                        day.exercises += MigrationExercise("$id:exercise:$line", line, name, section, columns, prescription, load, actualReps, actualRir, comments)
                    }
                }
            } else if (s.isNotBlank() && s != "---") {
                headers = null
                val day = days.lastOrNull()
                val label = Regex("^[*\\-]?\\s*\\*\\*([^*]+)\\*\\*:?\\s*(.*)").matchEntire(s)
                val labelName = label?.groupValues?.get(1)?.trim()?.removeSuffix(":")
                val value = label?.groupValues?.get(2)?.trim()?.removePrefix(":")?.trim().orEmpty()
                val cardioField = labelName?.lowercase() in setOf("modalidad", "tiempo", "rpe real", "notas", "comentarios gpt", "comentarios usuario")
                if (section.equals("Cardio", true) && day != null && cardioField) {
                    kind = "CARDIO"
                    val duration = if (labelName.equals("Tiempo", true)) Regex("(\\d+(?:\\s*[–-]\\s*\\d+)?)\\s*min", RegexOption.IGNORE_CASE).matchEntire(value)?.groupValues?.get(1)?.let(::number) else null
                    day.cardio += MigrationCardio(line, labelName ?: "texto", raw, duration)
                    if (labelName.equals("Tiempo", true) && duration == null && value.isNotBlank()) warn("CARDIO_DURATION", line, "Duración de cardio no interpretable; se conserva el original.")
                } else {
                    kind = if (section.contains("calentamiento", true)) "WARMUP" else "NOTE"
                    if (labelName != null && !cardioField && !labelName.equals("Estado", true)) {
                        kind = "UNINTERPRETED"
                        warn("UNKNOWN_FIELD", line, "Apartado conservado como texto original, sin convertirlo en campos estructurados.")
                    }
                }
                val author = when {
                    labelName.equals("Comentarios GPT", true) -> "AGENT"
                    labelName.equals("Comentarios usuario", true) -> "USER"
                    section.contains("Notas del agente", true) -> "AGENT"
                    else -> null
                }
                val comment = MigrationComment(line, raw, author)
                if (day == null || section.contains("Notas del agente", true) || section.contains("Formato de registro", true)) notes += comment else day.notes += comment
                if (labelName.equals("Estado", true) && day != null) {
                    day.status = when (value) { "✅" -> "COMPLETED"; "🟡" -> "PARTIAL"; "❌" -> "NOT_PERFORMED"; else -> null }
                    if (value.isNotBlank() && day.status == null) warn("SESSION_STATUS", line, "Estado vacío, plantilla o ambiguo; no se deduce que la sesión se haya realizado.")
                }
                if (section.isBlank() && day == null) warn("UNINTERPRETED", line, "Contenido conservado sin interpretar.")
            }
            lines += SourceLine(line, raw, kind)
        }
        if (!frontClosed) warn("FRONTMATTER", 1, "Metadatos sin cierre; no se interpreta el contenido como entrenamiento.")
        if (fence) warn("UNCLOSED_CODE", rawLines.size, "Bloque de código sin cierre.")
        if (days.isEmpty()) warn("UNSUPPORTED_FORMAT", 1, "No se reconocieron días Workout v3. El texto se conserva; la migración está incompleta.")
        if (days.any { it.exercises.isEmpty() && it.cardio.isEmpty() }) warn("EMPTY_DAY", days.first { it.exercises.isEmpty() && it.cardio.isEmpty() }.line, "Día sin ejercicios ni cardio interpretados.")
        return MigrationDocument(1, VERSION, id, source, text, title, metadata,
            days.map { MigrationDay(it.id, it.line, it.title, it.exercises, it.cardio, it.notes, it.status) }, notes, lines, issues)
    }

    private class DayBuilder(val id: String, val line: Int, val title: String) {
        val exercises = mutableListOf<MigrationExercise>()
        val cardio = mutableListOf<MigrationCardio>()
        val notes = mutableListOf<MigrationComment>()
        var status: String? = null
    }

    private fun isSeparator(s: String): Boolean = s.startsWith("|") && cells(s).isNotEmpty() && cells(s).all { Regex(":?-{3,}:?").matches(it) }
    private fun cells(s: String): List<String> {
        val result = mutableListOf<String>()
        val buffer = StringBuilder()
        var escaped = false
        s.removePrefix("|").removeSuffix("|").forEach { c ->
            if (escaped) { buffer.append('\\').append(c); escaped = false }
            else if (c == '\\') escaped = true
            else if (c == '|') { result += buffer.toString().trim(); buffer.clear() }
            else buffer.append(c)
        }
        if (escaped) buffer.append('\\')
        result += buffer.toString().trim()
        return result
    }

    private fun load(raw: String): MigrationLoad {
        val s = clean(raw)
        if (s.equals("BW", true)) return MigrationLoad(raw, null, "BODYWEIGHT", "BODYWEIGHT")
        val m = Regex("(\\d+(?:\\.\\d+)?(?:\\s*[–-]\\s*\\d+(?:\\.\\d+)?)?)\\s*(lb|kg)(?:\\s*(/hand|/mano|/lado|/side|total))?", RegexOption.IGNORE_CASE).matchEntire(s)
        return MigrationLoad(raw, m?.groupValues?.get(1)?.let(::number)?.takeIf { it.max <= 100000 },
            m?.groupValues?.get(2)?.uppercase(), when (m?.groupValues?.get(3)?.lowercase()) {
                "/hand", "/mano" -> "PER_HAND"; "/lado", "/side" -> "PER_SIDE"; "total" -> "TOTAL"; else -> null
            })
    }

    private fun values(raw: String, max: Double, groups: Boolean): MigrationValues {
        val s = clean(raw)
        if (s.isBlank()) return MigrationValues(raw, emptyList())
        // Slash is deliberately ambiguous: it can mean sides, alternatives, or a list.
        val tokens = s.split(',')
        val values = mutableListOf<Double?>()
        tokens.forEach { token ->
            val t = token.trim()
            val group = if (groups) Regex("(\\d+)\\s*[×x]\\s*(\\d+)", RegexOption.IGNORE_CASE).matchEntire(t) else null
            if (group != null) {
                val n = group.groupValues[1].toIntOrNull()
                val value = group.groupValues[2].toDoubleOrNull()?.takeIf { it in 0.0..max }
                if (n != null && n in 1..1000 && values.size + n <= 1000) repeat(n) { values += value }
                else if (values.size < 1001) values += null
            } else {
                val value = t.takeIf { Regex(if (groups) "\\d+" else "\\d+(?:\\.\\d+)?").matches(it) }?.toDoubleOrNull()
                if (values.size < 1001) values += value?.takeIf { it.isFinite() && it in 0.0..max }
            }
        }
        return MigrationValues(raw, values)
    }
}
