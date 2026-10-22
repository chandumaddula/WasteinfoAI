package com.wasteinfoai.ai.llm

import com.wasteinfoai.ai.rules.DisposalRule
import com.wasteinfoai.ai.rules.DisposalRuleEngine
import com.wasteinfoai.ai.rules.SafetyGuard
import kotlinx.coroutines.withTimeoutOrNull

data class AssistantResponse(
    val text: String,
    val isLlmGenerated: Boolean,
    val isHazardous: Boolean,
    val groundedCategory: String?,
    val targetBinColor: String?
)

/**
 * Coordinates LLM generation with deterministic, rule-grounded guidance.
 * Safety warnings always come from the rule engine / SafetyGuard, never only from the LLM.
 */
class AssistantService(
    private val llamaEngine: LlamaEngine,
    private val ruleEngine: DisposalRuleEngine
) {
    companion object {
        private const val LLM_TIMEOUT_MS = 45_000L
        private const val MIN_LLM_CHARS = 20
    }

    /** Backward-compatible entry point (category only). */
    suspend fun getGuidance(
        category: String,
        userQuery: String? = null,
        onPartialToken: (String) -> Unit = {}
    ): AssistantResponse = getGuidanceFor(category, null, null, null, userQuery, onPartialToken)

    /** Uses the full hierarchy (specific item / material code / jurisdiction) when available. */
    suspend fun getGuidanceFor(
        category: String,
        specificItem: String?,
        materialCode: String?,
        jurisdiction: String?,
        userQuery: String?,
        onPartialToken: (String) -> Unit = {}
    ): AssistantResponse {
        val rule: DisposalRule? =
            if (!specificItem.isNullOrBlank() || !materialCode.isNullOrBlank()) {
                ruleEngine.getHierarchicalRule(category, specificItem, materialCode, jurisdiction)
            } else {
                ruleEngine.getRuleForCategory(category)
            }
        val safety = SafetyGuard.evaluateSafety(category, isUncertain = false)
        val hazardous = safety.isHazardous || rule?.isHazardous == true
        val warning = rule?.safetyWarnings?.takeIf { it.isNotBlank() } ?: safety.primaryWarning

        val llmText = generateWithLlm(category, rule, userQuery, onPartialToken)
        if (llmText != null) {
            val text = if (hazardous) "⚠️ SAFETY ADVISORY: $warning\n\n$llmText" else llmText
            return AssistantResponse(text, true, hazardous, rule?.category ?: category, rule?.targetBinColor)
        }

        return AssistantResponse(
            text = buildDeterministicExplanation(category, rule, userQuery, hazardous, warning),
            isLlmGenerated = false,
            isHazardous = hazardous,
            groundedCategory = rule?.category ?: category,
            targetBinColor = rule?.targetBinColor
        )
    }

    private suspend fun generateWithLlm(
        category: String,
        rule: DisposalRule?,
        query: String?,
        onToken: (String) -> Unit
    ): String? {
        if (!llamaEngine.isModelAvailable) return null
        if (!llamaEngine.isReady && llamaEngine.initialize().isFailure) return null

        val prompt = PromptBuilder.buildDisposalExplanationPrompt(category, rule, query)
        val result = withTimeoutOrNull(LLM_TIMEOUT_MS) { llamaEngine.generate(prompt, 256, onToken) }
        if (result == null) {
            llamaEngine.stop()
            return null
        }
        return result.getOrNull()?.trim()?.takeIf { it.length >= MIN_LLM_CHARS }
    }

    private fun buildDeterministicExplanation(
        category: String,
        rule: DisposalRule?,
        query: String?,
        isHazardous: Boolean,
        warning: String
    ): String {
        if (rule == null) {
            return "No verified municipal rule is currently registered for category '$category'. Please consult your local waste authority before disposal."
        }
        return buildString {
            if (isHazardous) append("⚠️ SAFETY ADVISORY (Hazardous Waste):\n$warning\n\n")
            append("Recommended Disposal: ${rule.disposalMethod}\n")
            append("Target Bin: ${rule.targetBinColor} BIN\n")
            append("Recyclable: ${if (rule.isRecyclable) "Yes" else "No"}\n\n")
            append("Preparation Steps:\n• ${rule.preparationInstructions}\n")
            if (rule.prohibitedPathways.isNotEmpty()) {
                append("\nDo NOT: ${rule.prohibitedPathways.joinToString("; ")}\n")
            }
            if (!query.isNullOrBlank()) {
                append("\n(Offline Assistant Mode: Response grounded directly in ${rule.source})")
            }
        }.trim()
    }
}
