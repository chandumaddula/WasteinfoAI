package com.wasteinfoai.ai.llm

import com.wasteinfoai.ai.rules.DisposalRuleEngine
import com.wasteinfoai.ai.rules.SafetyGuard

data class AssistantResponse(
    val text: String,
    val isLlmGenerated: Boolean,
    val isHazardous: Boolean,
    val groundedCategory: String?,
    val targetBinColor: String?
)

/**
 * Assistant service coordinating AI generation and deterministic rule explanations.
 */
class AssistantService(
    private val llamaEngine: LlamaEngine,
    private val ruleEngine: DisposalRuleEngine
) {

    suspend fun getGuidance(
        category: String,
        userQuery: String? = null,
        onPartialToken: (String) -> Unit = {}
    ): AssistantResponse {
        val rule = ruleEngine.getRuleForCategory(category)
        val safety = SafetyGuard.evaluateSafety(category, isUncertain = false)

        if (llamaEngine.isModelAvailable) {
            val prompt = PromptBuilder.buildDisposalExplanationPrompt(category, rule, userQuery)
            val genResult = llamaEngine.generate(prompt, maxTokens = 256, onToken = onPartialToken)
            if (genResult.isSuccess) {
                return AssistantResponse(
                    text = genResult.getOrThrow().trim(),
                    isLlmGenerated = true,
                    isHazardous = safety.isHazardous,
                    groundedCategory = rule?.category ?: category,
                    targetBinColor = rule?.targetBinColor
                )
            }
        }

        // Deterministic rule-grounded response fallback
        val text = buildDeterministicExplanation(category, rule, userQuery, safety.isHazardous)
        return AssistantResponse(
            text = text,
            isLlmGenerated = false,
            isHazardous = safety.isHazardous,
            groundedCategory = rule?.category ?: category,
            targetBinColor = rule?.targetBinColor
        )
    }

    private fun buildDeterministicExplanation(
        category: String,
        rule: com.wasteinfoai.ai.rules.DisposalRule?,
        query: String?,
        isHazardous: Boolean
    ): String {
        if (rule == null) {
            return "No verified municipal rule is currently registered for category '$category'. Please consult your local waste authority before disposal."
        }

        val sb = StringBuilder()
        if (isHazardous) {
            sb.append("⚠️ SAFETY ADVISORY (Hazardous Waste):\n")
            sb.append("${rule.safetyWarnings}\n\n")
        }

        sb.append("Recommended Disposal: ${rule.disposalMethod}\n")
        sb.append("Target Bin: ${rule.targetBinColor} BIN\n")
        sb.append("Recyclable: ${if (rule.isRecyclable) "Yes" else "No"}\n\n")

        sb.append("Preparation Steps:\n")
        sb.append("• ${rule.preparationInstructions}\n\n")

        if (!query.isNullOrBlank()) {
            sb.append("(Offline Assistant Mode: Response grounded directly in ${rule.source})")
        }

        return sb.toString().trim()
    }
}
