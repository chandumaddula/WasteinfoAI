package com.wasteinfoai.ai.llm

import com.wasteinfoai.ai.rules.DisposalRule

/**
 * Builds grounded prompts for the AI assistant strictly anchored in verified disposal rules.
 *
 * Built with buildString (not trimIndent): trimIndent on text containing interpolated multi-line
 * values computes the wrong indent and leaves stray whitespace before <|begin_of_text|>.
 */
object PromptBuilder {

    private const val MAX_QUESTION_CHARS = 300

    fun buildDisposalExplanationPrompt(
        category: String,
        rule: DisposalRule?,
        userQuestion: String? = null
    ): String {
        val ruleText = if (rule != null) {
            listOf(
                "Canonical Waste Category: ${rule.category}",
                "Disposal Method: ${rule.disposalMethod}",
                "Recyclable: ${if (rule.isRecyclable) "Yes" else "No"}",
                "Hazardous: ${if (rule.isHazardous) "YES - HAZARDOUS" else "No"}",
                "Target Bin: ${rule.targetBinColor}",
                "Preparation Instructions: ${rule.preparationInstructions}",
                "Safety Warnings: ${rule.safetyWarnings}",
                "Official Source: ${rule.source}"
            ).joinToString("\n")
        } else {
            "No verified rule currently registered for category '$category'."
        }

        // Strip chat-template control tokens so a user question cannot inject new turns.
        val safeQuestion = userQuestion
            ?.replace("<|", "< |")
            ?.replace("|>", "| >")
            ?.trim()
            ?.take(MAX_QUESTION_CHARS)

        val questionPart = if (!safeQuestion.isNullOrBlank()) {
            "User Question: $safeQuestion"
        } else {
            "Please provide a clear, concise, and helpful summary of how to properly prepare and dispose of this item."
        }

        return buildString {
            append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n")
            append("You are wasteinfoAI Assistant, an offline environmental sanitation assistant.\n")
            append("Always follow these strict safety rules:\n")
            append("1. Ground your answer ONLY in the official disposal rule below. Never invent new rules or recycling policies.\n")
            append("2. If the item is hazardous, prominently emphasize safety warnings and personal protection.\n")
            append("3. If no verified rule exists, say so and advise consulting the local waste authority.\n")
            append("4. Keep the response friendly and under 120 words.\n\n")
            append("Official Grounding Data:\n")
            append(ruleText)
            append("<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n")
            append("I have an item identified as '$category'.\n")
            append(questionPart)
            append("<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n")
        }
    }
}
