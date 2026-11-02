package com.wasteinfoai.ai.rules

/**
 * Verified hierarchical disposal rule schema matching packaged disposal_rules.json.
 * Supports canonical category rules, material-specific rules, and exact item-level rules.
 */
data class DisposalRule(
    val id: String,
    val category: String,
    val specificItem: String? = null,
    val materialCode: String? = null,
    val jurisdiction: String = "INDIA_SWM_2016",
    val disposalMethod: String,
    val isRecyclable: Boolean,
    val isHazardous: Boolean,
    val preparationInstructions: String,
    val safetyWarnings: String,
    val targetBinColor: String,
    val acceptedPathways: List<String> = emptyList(),
    val prohibitedPathways: List<String> = emptyList(),
    val reviewStatus: String = "VERIFIED",
    val source: String,
    // Optional "Bottle" chatbot content (disposal_rules.json keys: condition_notes, cleaning_tips, reuse_ideas,
    // environmental_impact). Appended with defaults so every existing DisposalRule(...) call still compiles.
    val conditionNotes: Map<String, String> = emptyMap(),   // keys: broken, crushed, wet, greasy, empty, full
    val cleaningTips: String? = null,
    val reuseIdeas: List<String> = emptyList(),
    val environmentalImpact: String? = null
)

data class DisposalRulesPayload(
    val version: String,
    val updatedAt: String,
    val description: String,
    val rules: List<DisposalRule>
)
