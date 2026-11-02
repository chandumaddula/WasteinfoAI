package com.wasteinfoai.ai.llm

import com.wasteinfoai.ai.rules.DisposalRule
import com.wasteinfoai.features.assistant.ChatMessage

/**
 * Builds grounded prompts for Bottle strictly anchored in verified disposal rules.
 * Uses [ChatTemplate] to render multi-turn dialogues adhering to model token formats.
 */
object PromptBuilder {

    private const val MAX_QUESTION_CHARS = 400
    private val defaultTemplate: ChatTemplate = Llama3ChatTemplate()

    const val SYSTEM_PROMPT = """You are Bottle, a friendly, encouraging, and user-focused environmental sanitation assistant.
Always follow these strict safety rules:
1. Ground your answer ONLY in the official disposal rule provided. Never invent municipal policies.
2. If the item is hazardous, prominently emphasize safety warnings and personal protection.
3. If no verified rule exists, say so and advise consulting local waste authorities.
4. Keep the response friendly, conversational, and under 120 words.
5. Provide a direct, reassuring 1-2 sentence answer first before offering details or next steps.
6. When answering follow-up questions about components like batteries, cleaning, or reuse, stay grounded and safety-conscious."""

    /**
     * Returns the strict language instruction corresponding to the target locale tag.
     */
    fun getLanguageInstruction(languageCode: String?): String {
        return when (languageCode?.trim()?.lowercase()?.take(2)) {
            "hi" -> "Respond strictly in Hindi using Devanagari script."
            "te" -> "Respond strictly in Telugu using Telugu script."
            "ta" -> "Respond strictly in Tamil using Tamil script."
            "mr" -> "Respond strictly in Marathi using Devanagari script."
            else -> "Respond in clear English."
        }
    }

    /**
     * Backward-compatible single-turn prompt builder.
     */
    fun buildDisposalExplanationPrompt(
        category: String,
        rule: DisposalRule?,
        userQuestion: String? = null,
        languageCode: String? = null
    ): String {
        return buildMultiTurnPrompt(
            context = AssistantContext(category = category, languageCode = languageCode),
            rule = rule,
            history = emptyList(),
            currentMessage = userQuestion ?: "How should I dispose of $category?",
            languageCode = languageCode
        )
    }

    /**
     * Multi-turn grounded prompt builder using [ChatTemplate].
     */
    fun buildMultiTurnPrompt(
        context: AssistantContext,
        rule: DisposalRule?,
        history: List<ChatMessage>,
        currentMessage: String,
        template: ChatTemplate = defaultTemplate,
        languageCode: String? = null
    ): String {
        val effectiveLang = languageCode ?: context.languageCode
        val langInstruction = getLanguageInstruction(effectiveLang)
        val effectiveSystemPrompt = "$SYSTEM_PROMPT\n5. $langInstruction"

        val groundingData = buildRuleGroundingText(context, rule)
        val sanitizedQuery = sanitize(currentMessage).take(MAX_QUESTION_CHARS)

        return template.format(
            systemPrompt = effectiveSystemPrompt,
            history = history,
            currentMessage = sanitizedQuery,
            groundingData = groundingData
        )
    }

    fun buildRuleGroundingText(context: AssistantContext, rule: DisposalRule?): String {
        val itemName = context.specificItem ?: context.category
        if (rule == null) {
            return "Item: $itemName. No verified municipal rule currently registered for category '${context.category}'."
        }

        val lines = mutableListOf(
            "Item: $itemName",
            "Canonical Waste Category: ${rule.category}",
            "Disposal Method: ${rule.disposalMethod}",
            "Recyclable: ${if (rule.isRecyclable) "Yes" else "No"}",
            "Hazardous: ${if (rule.isHazardous) "YES - CRITICAL HAZARD" else "No"}",
            "Target Bin: ${rule.targetBinColor} BIN",
            "Preparation Instructions: ${rule.preparationInstructions}",
            "Safety Warnings: ${rule.safetyWarnings}",
            "Official Source: ${rule.source}"
        )

        if (rule.cleaningTips != null) {
            lines.add("Cleaning Tips: ${rule.cleaningTips}")
        }
        if (rule.reuseIdeas.isNotEmpty()) {
            lines.add("Verified Reuse Ideas: ${rule.reuseIdeas.joinToString("; ")}")
        }
        if (rule.environmentalImpact != null) {
            lines.add("Environmental Impact: ${rule.environmentalImpact}")
        }
        if (rule.conditionNotes.isNotEmpty()) {
            val conds = rule.conditionNotes.map { "${it.key}: ${it.value}" }.joinToString(" | ")
            lines.add("Condition Handling: $conds")
        }

        return lines.joinToString("\n")
    }

    private fun sanitize(text: String): String {
        return text
            .replace("<|", "< |")
            .replace("|>", "| >")
            .trim()
    }
}
