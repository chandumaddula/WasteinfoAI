package com.wasteinfoai.data.repository

import com.wasteinfoai.data.local.dao.FeedbackDao
import com.wasteinfoai.data.local.entity.ScanFeedbackEntity
import com.wasteinfoai.data.local.entity.SyncState
import kotlinx.coroutines.flow.Flow

/**
 * Repository mediating access to human-in-the-loop active-learning feedback.
 */
class FeedbackRepository(
    private val feedbackDao: FeedbackDao
) {
    suspend fun saveFeedback(feedback: ScanFeedbackEntity) {
        feedbackDao.insertFeedback(feedback)
    }

    suspend fun getPendingFeedback(limit: Int = 50): List<ScanFeedbackEntity> {
        return feedbackDao.getPendingFeedbackBatch(limit)
    }

    suspend fun updateSyncStatus(uuid: String, state: SyncState, error: String? = null) {
        feedbackDao.updateSyncState(uuid, state, error)
    }

    fun getAllFeedback(): Flow<List<ScanFeedbackEntity>> {
        return feedbackDao.getAllFeedback()
    }
}
