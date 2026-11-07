package com.example.workoutpoc.ui

/** Semantic tone of a message or status: picks the banner/pill colours and icon. */
enum class Tone { Info, Success, Warning, Error }

/**
 * Tone of a free-text status message. Messages come from view models as plain Spanish sentences with consistent
 * phrasing, so the banner can colour them without every model declaring a tone. Checked in order: "already there"
 * notes, then failures ("No se pudo…", "…fuera de rango"), then limits the user must act on, then confirmations.
 */
fun toneOf(message: String): Tone {
    val m = message.lowercase()
    fun any(vararg words: String) = words.any { it in m }
    return when {
        // "Ya estaba importado… no se crearán duplicados" is reassurance, not a failure.
        any("ya estaba") -> Tone.Info
        any("no se pudo", "no se pudieron", "no se guardó", "no se ha reemplazado", "incompatible", "inválid", "no válid", "ilegible",
            "fuera de rango", "duplicad", "supera", "rechazado", "conflicto", "contenido diferente", "interrumpi", "no coincide",
            "no existe", "no encontrad", "demasiad", "error", "falló") ||
            Regex("no (es|contiene) .*válid").containsMatchIn(m) -> Tone.Error
        any("denegado", "no concedido", "no permite", "desactivad") -> Tone.Warning
        any("ya existe") -> Tone.Info
        any("guardad", "exportad", "copiad", "eliminad", "verificad", "añadid", "aprobad", "cread", "ampliad", "importad",
            "revocada", "correctamente") -> Tone.Success
        else -> Tone.Info
    }
}
