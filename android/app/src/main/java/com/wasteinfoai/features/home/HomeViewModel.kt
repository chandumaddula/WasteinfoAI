package com.wasteinfoai.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wasteinfoai.data.local.entity.ReportDraftEntity
import com.wasteinfoai.data.local.entity.ScanHistoryEntity
import com.wasteinfoai.data.local.entity.SyncState
import com.wasteinfoai.data.remote.SessionManager
import com.wasteinfoai.data.repository.ReportRepository
import com.wasteinfoai.data.repository.ScanRepository
import com.wasteinfoai.sync.SyncScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val userRole: String = "RESIDENT",
    val userCategory: String = "Households",
    val userEmail: String = "",
    val recentScans: List<ScanHistoryEntity> = emptyList(),
    val pendingSyncCount: Int = 0,
    val totalReportsCount: Int = 0
)

class HomeViewModel(
    private val scanRepository: ScanRepository,
    private val reportRepository: ReportRepository,
    private val sessionManager: SessionManager,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        sessionManager.roleState,
        sessionManager.categoryState,
        sessionManager.emailState,
        scanRepository.getScanHistory(),
        reportRepository.getAllReportsFlow()
    ) { role, category, email, scans, reports ->
        val pendingCount = reports.count { it.syncStatus == SyncState.PENDING_SYNC }
        HomeUiState(
            userRole = role,
            userCategory = category,
            userEmail = email,
            recentScans = scans.take(5),
            pendingSyncCount = pendingCount,
            totalReportsCount = reports.size
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    fun triggerSync() {
        viewModelScope.launch {
            syncScheduler.triggerImmediateSync()
        }
    }

    fun setRole(newRole: String) {
        sessionManager.setDevRoleOverride(newRole)
    }
}
