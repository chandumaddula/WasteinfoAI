package com.wasteinfoai.features.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wasteinfoai.core.common.Resource
import com.wasteinfoai.data.local.entity.ReportDraftEntity
import com.wasteinfoai.data.repository.ReportRepository
import com.wasteinfoai.location.LocationHelper
import com.wasteinfoai.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CreateReportUiState(
    val category: String = "plastic",
    val description: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val manualLocation: String? = null,
    val localImagePath: String? = null,
    val isFetchingLocation: Boolean = false,
    val isSubmitting: Boolean = false,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null
)

class CreateReportViewModel(
    private val reportRepository: ReportRepository,
    private val locationHelper: LocationHelper,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateReportUiState())
    val uiState: StateFlow<CreateReportUiState> = _uiState.asStateFlow()

    fun setInitialData(category: String, imagePath: String) {
        _uiState.value = _uiState.value.copy(
            category = category.ifBlank { "plastic" },
            localImagePath = imagePath.ifBlank { null }
        )
    }

    fun onCategoryChanged(newCategory: String) {
        _uiState.value = _uiState.value.copy(category = newCategory)
    }

    fun onDescriptionChanged(newDesc: String) {
        _uiState.value = _uiState.value.copy(description = newDesc)
    }

    fun onManualLocationChanged(newLoc: String) {
        _uiState.value = _uiState.value.copy(manualLocation = newLoc)
    }

    fun fetchLocation() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isFetchingLocation = true)
            val coords = locationHelper.getLastKnownLocation()
            if (coords != null) {
                _uiState.value = _uiState.value.copy(
                    latitude = coords.latitude,
                    longitude = coords.longitude,
                    isFetchingLocation = false
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isFetchingLocation = false,
                    errorMessage = "Location unavailable. Please enter a manual location description."
                )
            }
        }
    }

    fun submitReport() {
        val s = _uiState.value
        if (s.description.isBlank()) {
            _uiState.value = s.copy(errorMessage = "Please enter a brief description.")
            return
        }
        if (s.latitude == null && s.manualLocation.isNullOrBlank()) {
            _uiState.value = s.copy(errorMessage = "Please capture GPS location or provide a manual location.")
            return
        }

        viewModelScope.launch {
            _uiState.value = s.copy(isSubmitting = true, errorMessage = null)
            val draft = reportRepository.createAndSaveDraft(
                wasteCategory = s.category,
                description = s.description,
                latitude = s.latitude,
                longitude = s.longitude,
                manualLocation = s.manualLocation,
                evidenceRef = s.localImagePath?.let { "reports/${java.io.File(it).name}" },
                localImagePath = s.localImagePath,
                isReadyToSync = true
            )

            // Attempt online submission
            val res = reportRepository.submitReport(draft)
            when (res) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(isSubmitting = false, isSuccess = true)
                }
                is Resource.Error -> {
                    // Offline or temporary error - report is already queued locally
                    syncScheduler.triggerImmediateSync()
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        isSuccess = true, // Still success locally!
                        errorMessage = "Offline: Report queued for synchronization."
                    )
                }
                else -> {}
            }
        }
    }
}
