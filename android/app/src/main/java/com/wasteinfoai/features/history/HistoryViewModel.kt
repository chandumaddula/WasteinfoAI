package com.wasteinfoai.features.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wasteinfoai.data.local.entity.ScanHistoryEntity
import com.wasteinfoai.data.repository.ScanRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(private val scanRepository: ScanRepository) : ViewModel() {

    val scansFlow: StateFlow<List<ScanHistoryEntity>> = scanRepository.getScanHistory()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun deleteScan(id: Long) {
        viewModelScope.launch {
            scanRepository.deleteScan(id)
        }
    }
}
