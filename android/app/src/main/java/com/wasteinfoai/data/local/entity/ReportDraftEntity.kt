package com.wasteinfoai.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local Room entity representing reports and drafts.
 * Separates local synchronization state (syncStatus) from authoritative server workflow status (serverStatus).
 */
@Entity(tableName = "reports")
data class ReportDraftEntity(
    @PrimaryKey
    val clientUuid: String,
    val wasteCategory: String,
    val description: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val manualLocation: String? = null,
    val evidenceRef: String? = null,
    val localImagePath: String? = null,
    val syncStatus: SyncState = SyncState.LOCAL_DRAFT,
    val serverId: String? = null,
    val serverStatus: String? = null,
    val serverVersion: Int = 1,
    val reviewNote: String? = null,
    val assignedWorkerId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncAttempts: Int = 0,
    val lastSyncError: String? = null
)

enum class SyncState {
    LOCAL_DRAFT,
    PENDING_SYNC,
    SYNCING,
    SYNCED,
    CONFLICT,
    PERMANENT_FAILURE
}
