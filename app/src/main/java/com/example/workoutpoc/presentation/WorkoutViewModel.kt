package com.example.workoutpoc.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.workoutpoc.WorkoutApplication
import com.example.workoutpoc.domain.*
import kotlinx.coroutines.launch

class WorkoutViewModel(application: Application) : AndroidViewModel(application) {
    private val app: WorkoutApplication = application as WorkoutApplication
    val state = app.repository.state
    val ready = app.repository.ready
    val error = app.error
    fun dispatch(command: WorkoutCommand) {
        viewModelScope.launch { app.safely { app.repository.dispatch(command) } }
    }
    fun retry() { viewModelScope.launch { app.safely { app.repository.restore(); app.error.value = null } } }
}
