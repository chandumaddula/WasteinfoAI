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
    val acceptanceState: AcceptanceState = if (isUncertain) AcceptanceState.LOW_CONFIDENCE else AcceptanceState.ACCEPTED,
    val policyResult: PolicyEvaluationResult? = null
) {
    val isAccepted: Boolean get() = acceptanceState == AcceptanceState.ACCEPTED && !isUncertain

    companion object {
        fun uncertain(
            reason: String,
            durationMs: Long = 0L,
            topK: List<ClassScore> = emptyList(),
            state: AcceptanceState = AcceptanceState.LOW_CONFIDENCE,
            policyResult: PolicyEvaluationResult? = null
        ): ClassificationResult = ClassificationResult(
            classId = -1,
            label = "Uncertain / Unidentified",
            confidence = 0f,
            inferenceDurationMs = durationMs,
            isUncertain = true,
            uncertaintyReason = reason,
            topKResults = topK,
            acceptanceState = state,
            policyResult = policyResult
        )

        fun rejected(
            state: AcceptanceState,
            reason: String,
            durationMs: Long = 0L,
            topK: List<ClassScore> = emptyList(),
            policyResult: PolicyEvaluationResult? = null
        ): ClassificationResult = ClassificationResult(
            classId = -1,
            label = "No Clear Waste Detected",
            confidence = topK.firstOrNull()?.score ?: 0f,
            inferenceDurationMs = durationMs,
            isUncertain = true,
            uncertaintyReason = reason,
            topKResults = topK,
            acceptanceState = state,
            policyResult = policyResult
        )
    }
}

data class ClassScore(
    val classId: Int,
    val label: String,
    val score: Float
)
