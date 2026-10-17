package com.wasteinfoai.features.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wasteinfoai.core.common.Resource
import com.wasteinfoai.data.local.entity.ReportDraftEntity
import com.wasteinfoai.data.remote.dto.ReportResponse
import com.wasteinfoai.data.repository.ReportRepository
import com.wasteinfoai.location.Coordinates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MapHotspot(
    val id: String,
    val category: String,
    val description: String,
    val coordinates: Coordinates,
    val status: String,
    val isLocalDraft: Boolean,
    val evidenceRef: String?
)

data class MapUiState(
    val isLoading: Boolean = false,
    val hotspots: List<MapHotspot> = emptyList(),
    val selectedHotspot: MapHotspot? = null,
    val selectedFilter: String = "ALL",
    val isOffline: Boolean = false,
    val errorMessage: String? = null
)

class MapViewModel(private val reportRepository: ReportRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState(isLoading = true))
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var allHotspots: List<MapHotspot> = emptyList()

    init {
        loadHotspots()
    }

    fun loadHotspots() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val hotspotList = mutableListOf<MapHotspot>()

            // 1. Fetch remote server reports
            val remoteRes = reportRepository.fetchRemoteReports()
            var offline = false
            when (remoteRes) {
                is Resource.Success -> {
                    remoteRes.data.forEach { r ->
                        if (r.latitude != null && r.longitude != null) {
                            val coords = Coordinates(r.latitude, r.longitude)
                            if (coords.isValid) {
                                hotspotList.add(
                                    MapHotspot(
                                        id = r.id,
                                        category = r.wasteCategory,
                                        description = r.description,
                                        coordinates = coords,
                                        status = r.status,
                                        isLocalDraft = false,
                                        evidenceRef = r.evidenceRef
                                    )
                                )
                            }
                        }
                    }
                }
                is Resource.Error -> {
                    offline = true
                }
                else -> {}
            }

            allHotspots = hotspotList
            applyFilter(_uiState.value.selectedFilter, offline)
        }
    }

    fun setFilter(filter: String) {
        _uiState.value = _uiState.value.copy(selectedFilter = filter)
        applyFilter(filter, _uiState.value.isOffline)
    }

    private fun applyFilter(filter: String, isOffline: Boolean) {
        val filtered = if (filter == "ALL") {
            allHotspots
        } else {
            allHotspots.filter { it.status.equals(filter, ignoreCase = true) }
        }
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            hotspots = filtered,
            isOffline = isOffline
        )
    }

    fun selectHotspot(hotspot: MapHotspot?) {
        _uiState.value = _uiState.value.copy(selectedHotspot = hotspot)
    }
}
