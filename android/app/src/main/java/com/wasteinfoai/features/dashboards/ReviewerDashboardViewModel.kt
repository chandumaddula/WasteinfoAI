package com.wasteinfoai.features.dashboards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wasteinfoai.core.common.Resource
import com.wasteinfoai.data.remote.dto.ReportResponse
import com.wasteinfoai.data.repository.ReportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReviewerUiState(
    val isLoading: Boolean = false,
    val pendingReports: List<ReportResponse> = emptyList(),
    val errorMessage: String? = null,
    val actionSuccessMessage: String? = null
)

class ReviewerDashboardViewModel(private val reportRepository: ReportRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewerUiState(isLoading = true))
    val uiState: StateFlow<ReviewerUiState> = _uiState.asStateFlow()

    init {
        loadPendingReports()
    }

    fun loadPendingReports() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = reportRepository.fetchRemoteReports(status = "UNDER_REVIEW")
            when (result) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        pendingReports = result.data
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.message
                    )
                }
                else -> {}
            }
        }
    }

    fun verifyReport(reportId: String, note: String?) {
        viewModelScope.launch {
            val res = reportRepository.reviewReport(reportId, "VERIFIED", note)
            if (res is Resource.Success) {
                _uiState.value = _uiState.value.copy(actionSuccessMessage = "Report verified successfully")
                loadPendingReports()
            } else if (res is Resource.Error) {
                _uiState.value = _uiState.value.copy(errorMessage = res.message)
            }
        }
    }

    fun rejectReport(reportId: String, note: String) {
        viewModelScope.launch {
            val res = reportRepository.reviewReport(reportId, "REJECTED", note)
            if (res is Resource.Success) {
                _uiState.value = _uiState.value.copy(actionSuccessMessage = "Report rejected")
                loadPendingReports()
            } else if (res is Resource.Error) {
                _uiState.value = _uiState.value.copy(errorMessage = res.message)
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, actionSuccessMessage = null)
    }
}
