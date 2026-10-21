package com.wasteinfoai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.wasteinfoai.data.local.entity.ScanFeedbackEntity
import com.wasteinfoai.data.local.entity.SyncState
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedbackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeedback(feedback: ScanFeedbackEntity)

    @Update
    suspend fun updateFeedback(feedback: ScanFeedbackEntity)

    @Query("SELECT * FROM scan_feedback WHERE hasUploadConsent = 1 AND syncStatus IN ('LOCAL_DRAFT', 'PENDING_SYNC') LIMIT :limit")
    suspend fun getPendingFeedbackBatch(limit: Int): List<ScanFeedbackEntity>

    @Query("UPDATE scan_feedback SET syncStatus = :state, syncErrorMessage = :error WHERE feedbackUuid = :uuid")
    suspend fun updateSyncState(uuid: String, state: SyncState, error: String? = null)

    @Query("SELECT * FROM scan_feedback ORDER BY timestamp DESC")
    fun getAllFeedback(): Flow<List<ScanFeedbackEntity>>

    @Query("SELECT * FROM scan_feedback WHERE feedbackUuid = :uuid LIMIT 1")
    suspend fun getFeedbackById(uuid: String): ScanFeedbackEntity?

    @Query("DELETE FROM scan_feedback WHERE feedbackUuid = :uuid")
    suspend fun deleteFeedback(uuid: String)
}
