package com.wasteinfoai.features.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wasteinfoai.data.local.entity.TaskEntity
import com.wasteinfoai.data.repository.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WorkerTasksUiState(
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
)

class WorkerTasksViewModel(private val taskRepository: TaskRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkerTasksUiState())
    val uiState: StateFlow<WorkerTasksUiState> = _uiState.asStateFlow()

    val tasksFlow: StateFlow<List<TaskEntity>> = taskRepository.getAssignedTasksFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        refreshTasks()
    }

    fun refreshTasks() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, errorMessage = null)
            val result = taskRepository.refreshAssignedTasks()
            _uiState.value = _uiState.value.copy(
                isRefreshing = false,
                errorMessage = result.let { if (it is com.wasteinfoai.core.common.Resource.Error) it.message else null }
            )
        }
    }

    fun resolveTask(taskId: String, note: String?) {
        viewModelScope.launch {
            taskRepository.resolveTask(taskId, note)
            refreshTasks()
        }
    }
}
