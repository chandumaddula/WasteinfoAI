package com.wasteinfoai.ai.llm

/**
 * Intents for offline waste queries, ordered by priority:
 * SAFETY > CLEANING > UPCYCLE > BIN_LOCATION > GENERAL
 */
enum class AssistantIntent {
    SAFETY,
    CLEANING,
    UPCYCLE,
    BIN_LOCATION,
    GENERAL
}

