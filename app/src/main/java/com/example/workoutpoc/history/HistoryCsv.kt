package com.example.workoutpoc.history

import com.example.workoutpoc.data.activePlan
import com.example.workoutpoc.domain.*
import java.time.Instant
import java.util.Locale

/**
 * Session history as RFC 4180 CSV (UTF-8, comma separated, CRLF line ends), one row per recorded set and one per
 * cardio entry, newest session first. Only recorded data is written: an omitted set has no performance values, and
 * fields that were never entered are left empty rather than filled with defaults. Times are ISO-8601 UTC.
 */
object HistoryCsv {
    val header = listOf(
        "session_id", "started_at", "ended_at", "program", "week", "day", "session_status", "row_type",
        "exercise", "exercise_id", "set_number", "set_status", "load", "unit", "basis", "reps", "rir",
        "prescribed_load", "prescribed_reps", "prescribed_rir", "edited_at", "previous_versions", "notes",
        "cardio_modality", "duration_seconds", "distance", "distance_unit"
    )

    fun encode(archived: List<WorkoutState>, current: WorkoutState, nowWallMillis: Long): String {
        val rows = mutableListOf(header)
        recordedSessions(archived, current).forEach { s ->
            val summary = summarize(s, nowWallMillis)
            val plan = s.activePlan()
            val session = listOf(s.sessionId, time(s.startedAtMillis), time(s.endedAtMillis), s.source?.programName ?: "Demo",
                s.source?.weekName ?: "", s.source?.dayName ?: "",
                summary.status.name + if (summary.inProgress) " (IN_PROGRESS)" else if (summary.endedEarly) " (ENDED_EARLY)" else "")
            s.records.forEach { r ->
                val p = plan[r.index]
                val done = r.status == SetStatus.COMPLETED
                rows += session + listOf(
                    if (p.warmup) "warmup_set" else if (p.extra) "extra_set" else "set",
                    p.exercise, p.exerciseId ?: "", p.number.toString(), r.status.name,
                    if (done) tenths(r.values.weightTenths) else "", p.unit.name, p.basis ?: "",
                    if (done) r.values.reps.toString() else "", if (done) rir(r.values.rir) else "",
                    if (!p.extra && p.loadPrescribed) tenths(p.weightTenths) else "", if (!p.extra) p.reps.toString() else "",
                    if (!p.extra) rir(p.rir) else "",
                    time(r.editedAtMillis), r.revisions.size.toString(), r.notes ?: "", "", "", "", "")
            }
            s.cardio.forEach { c ->
                rows += session + listOf("cardio") + List(14) { "" } + listOf(c.notes ?: "", c.modality,
                    c.durationSeconds?.toString() ?: "", c.distanceTenths?.let(::tenths) ?: "", c.distanceUnit?.name ?: "")
            }
        }
        return rows.joinToString("") { row -> row.joinToString(",", transform = ::field) + "\r\n" }
    }

    private fun tenths(value: Int) = String.format(Locale.ROOT, "%.1f", value / 10.0)
    private fun rir(value: Int?) = when (value) { null -> ""; 4 -> "4+"; else -> value.toString() }
    private fun time(millis: Long?) = millis?.let { Instant.ofEpochMilli(it).toString() } ?: ""
    /** Quotes a field when it holds a comma, quote or line break; a leading formula character is escaped for spreadsheets. */
    fun field(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r" && value.toDoubleOrNull() == null) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
