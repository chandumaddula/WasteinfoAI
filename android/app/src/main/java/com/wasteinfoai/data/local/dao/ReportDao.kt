package com.wasteinfoai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.wasteinfoai.data.local.entity.ReportDraftEntity
import com.wasteinfoai.data.local.entity.SyncState
import kotlinx.coroutines.flow.Flow

@Dao
interface ReportDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(report: ReportDraftEntity)

    @Update
    suspend fun update(report: ReportDraftEntity)

    @Query("SELECT * FROM reports ORDER BY createdAt DESC")
    fun getAllReportsFlow(): Flow<List<ReportDraftEntity>>

    @Query("SELECT * FROM reports WHERE clientUuid = :clientUuid LIMIT 1")
    suspend fun getByClientUuid(clientUuid: String): ReportDraftEntity?

    @Query("SELECT * FROM reports WHERE syncStatus = :state ORDER BY createdAt ASC")
    suspend fun getPendingSyncReports(state: SyncState = SyncState.PENDING_SYNC): List<ReportDraftEntity>

    @Query("SELECT * FROM reports WHERE syncStatus = 'PENDING_SYNC' LIMIT :limit")
    suspend fun getPendingBatch(limit: Int = 50): List<ReportDraftEntity>

    @Query("UPDATE reports SET syncStatus = :newState, lastSyncError = :error WHERE clientUuid = :clientUuid")
    suspend fun updateSyncState(clientUuid: String, newState: SyncState, error: String? = null)

    @Query("UPDATE reports SET syncStatus = 'SYNCED', lastSyncError = null WHERE syncStatus = 'PENDING_SYNC'")
    suspend fun markAllPendingAsSynced()

    @Query("DELETE FROM reports WHERE clientUuid = :clientUuid")
    suspend fun delete(clientUuid: String)
}
