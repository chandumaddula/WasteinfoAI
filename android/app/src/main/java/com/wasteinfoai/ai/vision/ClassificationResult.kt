package com.wasteinfoai.ai.vision

/**
 * Encapsulates the output of the on-device waste classification model.
 */
data class ClassificationResult(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val inferenceDurationMs: Long,
    val modelVersion: String = "1.0.0",
    val isUncertain: Boolean = false,
    val uncertaintyReason: String? = null,
    val topKResults: List<ClassScore> = emptyList(),
) {
    companion object {
        fun uncertain(
            reason: String,
            durationMs: Long = 0L,
            topK: List<ClassScore> = emptyList()
        ): ClassificationResult = ClassificationResult(
            classId = -1,
            label = "Uncertain / Unidentified",
            confidence = 0f,
            inferenceDurationMs = durationMs,
            isUncertain = true,
            uncertaintyReason = reason,
            topKResults = topK
        )
    }
}

data class ClassScore(
    val classId: Int,
    val label: String,
    val score: Float
)
