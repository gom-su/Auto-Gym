package com.example.workoutpoc

import com.example.workoutpoc.ui.questionAnswer
import org.junit.Assert.*
import org.junit.Test

class QuestionAnswerChoicesTest {
    private val options = listOf("Primera", "Segunda", "Tercera")

    @Test fun `only the selected choice is submitted and a custom answer must not be blank`() {
        assertNull(questionAnswer(options, -1, "Texto sin seleccionar Otra"))
        assertEquals("Segunda", questionAnswer(options, 1, "Respuesta anterior"))
        assertEquals("Mi respuesta", questionAnswer(options, 3, "  Mi respuesta  "))
        assertNull(questionAnswer(options, 3, "  "))
        assertNull(questionAnswer(options, 4, "Índice inválido"))
        assertEquals("Primera", questionAnswer(options, 0, "Respuesta anterior de Otra"))
    }
}
