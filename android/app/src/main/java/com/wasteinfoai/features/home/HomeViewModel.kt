package com.wasteinfoai.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wasteinfoai.core.common.Resource
import com.wasteinfoai.data.local.entity.ReportDraftEntity
import com.wasteinfoai.data.local.entity.ScanHistoryEntity
import com.wasteinfoai.data.local.entity.SyncState
import com.wasteinfoai.data.remote.SessionManager
import com.wasteinfoai.data.repository.ReportRepository
import com.wasteinfoai.data.repository.ScanRepository
import com.wasteinfoai.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val userRole: String = "RESIDENT",
    val userCategory: String = "Household recycling",
    val userEmail: String = "",
    val recentScans: List<ScanHistoryEntity> = emptyList(),
    val pendingSyncCount: Int = 0,
    val totalReportsCount: Int = 0,
    val isSyncing: Boolean = false,
    val syncMessage: String? = null
)

class HomeViewModel(
    private val scanRepository: ScanRepository,
    private val reportRepository: ReportRepository,
    private val sessionManager: SessionManager,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    private val _isSyncing = MutableStateFlow(false)
    private val _syncMessage = MutableStateFlow<String?>(null)

    private val baseState = combine(
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
    }

    val uiState: StateFlow<HomeUiState> = combine(
        baseState,
        _isSyncing,
        _syncMessage
    ) { base, syncing, msg ->
        base.copy(isSyncing = syncing, syncMessage = msg)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    fun triggerSync() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = null
            syncScheduler.triggerImmediateSync()
            val result = reportRepository.syncPendingReports()
            _isSyncing.value = false
            when (result) {
                is Resource.Success -> {
                    if (result.data > 0) {
                        _syncMessage.value = "Synced ${result.data} report(s) successfully!"
                    }
                }
                is Resource.Error -> {
                    _syncMessage.value = "Server offline: Reports saved locally."
                }
                else -> {}
            }
        }
    }

    fun markPendingAsSyncedLocally() {
        viewModelScope.launch {
            reportRepository.markAllPendingAsSynced()
            _syncMessage.value = null
        }
    }

    fun clearSyncMessage() {
        _syncMessage.value = null
    }

    fun setRole(newRole: String) {
        sessionManager.setDevRoleOverride(newRole)
    }
}
