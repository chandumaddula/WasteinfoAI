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
    val reuseIdeas: List<String> = emptyList(),
    val cleaningTips: String? = null,
    val environmentalImpact: String? = null,
    val conditionNotes: Map<String, String> = emptyMap()
)

data class DisposalRulesPayload(
    val version: String,
    val updatedAt: String,
    val description: String,
    val rules: List<DisposalRule>
)
