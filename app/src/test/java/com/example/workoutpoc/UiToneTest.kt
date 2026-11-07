package com.example.workoutpoc

import com.example.workoutpoc.ui.Tone
import com.example.workoutpoc.ui.toneOf
import org.junit.Assert.assertEquals
import org.junit.Test

/** Banner colours follow the meaning of the app's own status messages. */
class UiToneTest {
    @Test fun failures_are_errors() {
        listOf(
            "No se pudo leer el historial. Tus datos se conservan. Reintenta.",
            "La sesión cambió mientras editabas. Vuelve a abrir la serie; no se guardó nada.",
            "JSON incompatible: revisa los campos, tipos y valores del formato de programa v1.",
            "El archivo no es UTF-8 válido.",
            "El archivo supera 1 MiB.",
            "Envío firmado rechazado: firma no válida. No se guardó nada.",
            "Ya existe un programa con ID 'x' y contenido diferente. No se guardará nada.",
        ).forEach { assertEquals(it, Tone.Error, toneOf(it)) }
    }

    @Test fun limits_to_act_on_are_warnings() {
        assertEquals(Tone.Warning, toneOf("Permiso de superposición no concedido. Puedes usar la notificación o registrar aquí."))
        assertEquals(Tone.Warning, toneOf("Este dispositivo no permite abrir los ajustes del panel flotante."))
    }

    @Test fun confirmations_are_successes() {
        listOf(
            "Medición guardada en este dispositivo.", "Historial exportado en CSV.", "JSON copiado al portapapeles del sistema.",
            "Envío firmado por k1 guardado. La propuesta queda pendiente de tu aprobación.",
            "Clave k1 revocada: sus envíos se rechazarán.",
        ).forEach { assertEquals(it, Tone.Success, toneOf(it)) }
    }

    @Test fun neutral_notes_are_info() {
        // The user's own rejection is a decision, not a failure; "already there" is information.
        assertEquals(Tone.Info, toneOf("Propuesta rechazada. El programa no cambió."))
        assertEquals(Tone.Info, toneOf("Este programa ya estaba importado con el mismo contenido. No se crearán duplicados."))
        assertEquals(Tone.Info, toneOf("Despliega la notificación para registrar sin salir de tu aplicación."))
    }
}
