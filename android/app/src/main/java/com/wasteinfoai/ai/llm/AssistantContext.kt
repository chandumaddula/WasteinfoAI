package com.wasteinfoai.ai.llm

/**
 * Rich contextual snapshot of a scanned or selected waste item passed to Bottle.
 */
data class AssistantContext(
    val category: String,
    val specificItem: String? = null,
    val materialCode: String? = null,
    val confidence: Float = 1.0f,
    val isUserConfirmed: Boolean = false,
    val condition: String? = null,
    val jurisdiction: String = "INDIA_SWM_2016",
    val scanId: Long = 0L
)

