package com.wasteinfoai.ai.llm

/**
 * Everything "Bottle" knows about the item the user is asking about.
 * Pass the scan id / fields from the scan result instead of a bare category string.
 */
data class AssistantContext(
    val category: String,
    val specificItem: String? = null,
    val materialCode: String? = null,
    val confidence: Float = 0f,
    val isUserConfirmed: Boolean = false,
    val condition: String? = null,      // broken / crushed / wet / greasy / empty / full
    val jurisdiction: String? = null,
    val scanId: Long = 0L,
    val languageCode: String? = null    // en, hi, te, ta, mr
)
