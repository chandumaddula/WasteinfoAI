package com.wasteinfoai.ai.rules

/**
 * Verified disposal rule schema matching packaged disposal_rules.json.
 */
data class DisposalRule(
    val id: String,
    val category: String,
    val jurisdiction: String = "GLOBAL_STANDARD",
    val disposalMethod: String,
    val isRecyclable: Boolean,
    val isHazardous: Boolean,
    val preparationInstructions: String,
    val safetyWarnings: String,
    val targetBinColor: String,
    val reviewStatus: String = "VERIFIED",
    val source: String
)

data class DisposalRulesPayload(
    val version: String,
    val updatedAt: String,
    val description: String,
    val rules: List<DisposalRule>
)
