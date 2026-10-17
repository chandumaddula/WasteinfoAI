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

data class AdminMetrics(
    val totalReports: Int = 0,
    val underReviewCount: Int = 0,
    val verifiedCount: Int = 0,
    val assignedCount: Int = 0,
    val resolvedCount: Int = 0,
    val rejectedCount: Int = 0,
    val topCategories: List<Pair<String, Int>> = emptyList()
)

data class AdminUiState(
    val isLoading: Boolean = false,
    val metrics: AdminMetrics = AdminMetrics(),
    val errorMessage: String? = null
)

class AdminDashboardViewModel(private val reportRepository: ReportRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminUiState(isLoading = true))
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    init {
        loadMetrics()
    }

    fun loadMetrics() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = reportRepository.fetchRemoteReports()
            when (result) {
                is Resource.Success -> {
                    val reports = result.data
                    val underReview = reports.count { it.status == "UNDER_REVIEW" }
                    val verified = reports.count { it.status == "VERIFIED" }
                    val assigned = reports.count { it.status == "ASSIGNED" }
                    val resolved = reports.count { it.status == "RESOLVED" }
                    val rejected = reports.count { it.status == "REJECTED" }

                    val categoryCounts = reports.groupingBy { it.wasteCategory }.eachCount()
                        .toList().sortedByDescending { it.second }.take(5)

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        metrics = AdminMetrics(
                            totalReports = reports.size,
                            underReviewCount = underReview,
                            verifiedCount = verified,
                            assignedCount = assigned,
                            resolvedCount = resolved,
                            rejectedCount = rejected,
                            topCategories = categoryCounts
                        )
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
}
