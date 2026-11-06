package com.example.workoutpoc.catalog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.workoutpoc.domain.WorkoutState
import com.example.workoutpoc.workoutApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CatalogUiState(val ready: Boolean = false, val busy: Boolean = false, val message: String? = null,
                          val sessions: List<WorkoutState> = emptyList())

class CatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.workoutApp
    val catalog = app.catalog.catalog
    private val mutableUi = MutableStateFlow(CatalogUiState())
    val ui = mutableUi.asStateFlow()
    init { refresh() }

    /** Restores the catalog and registers exercises referenced by programs and recorded sessions. */
    fun refresh() = operation {
        app.catalog.restore(); app.programs.restore(); app.repository.restore()
        val current = app.repository.state.value
        val sessions = app.repository.archived.value.sessions + listOfNotNull(current.takeIf { it.records.isNotEmpty() })
        val added = app.catalog.sync(exerciseReferences(app.programs.library.value.programs, sessions))
        mutableUi.value = mutableUi.value.copy(ready = true, sessions = sessions,
            message = if (added > 0) "Se registraron $added ejercicios a partir de programas y sesiones. Revisa su variante y equipo." else null)
    }

    fun save(edited: CatalogExercise) = operation {
        app.catalog.update(edited)
        mutableUi.value = mutableUi.value.copy(message = "Ejercicio guardado. Su historial se conserva por ID.")
    }

    private fun operation(block: suspend () -> Unit) {
        if (ui.value.busy) return
        mutableUi.value = mutableUi.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: CatalogException) { mutableUi.value = mutableUi.value.copy(message = e.message) }
            catch (_: Exception) { mutableUi.value = mutableUi.value.copy(message = "No se pudo leer o guardar el catálogo. Tus datos se conservan. Reintenta.") }
            finally { mutableUi.value = mutableUi.value.copy(busy = false) }
        }
    }
}
