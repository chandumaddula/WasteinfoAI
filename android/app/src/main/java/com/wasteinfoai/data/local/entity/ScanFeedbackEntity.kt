package com.wasteinfoai.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class FeedbackChoice {
    CORRECT,
    WRONG_ITEM,
    NOT_SURE
}

/**
 * Human-in-the-loop active learning feedback record.
 * Preserves original model predictions separately from user corrections,
 * adhering to strict user upload consent and idempotent local persistence.
 */
@Entity(tableName = "scan_feedback")
data class ScanFeedbackEntity(
    @PrimaryKey
    val feedbackUuid: String = UUID.randomUUID().toString(),
    val scanUuid: String,
    val originalCategory: String,
    val originalConfidence: Float,
    val topKJson: String,
    val detectorLabel: String? = null,
    val userChoice: FeedbackChoice,
    val userConfirmedCategory: String? = null,
    val modelVersion: String = "1.0.0-rexnet150",
    val preprocessingVersion: String = "letterbox-224",
    val timestamp: Long = System.currentTimeMillis(),
    val hasUploadConsent: Boolean = false,
    val syncStatus: SyncState = SyncState.LOCAL_DRAFT,
    val syncErrorMessage: String? = null
)
