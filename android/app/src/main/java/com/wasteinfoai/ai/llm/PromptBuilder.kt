package com.wasteinfoai.ai.llm

import com.wasteinfoai.ai.rules.DisposalRule

/**
 * Builds grounded prompts for the AI assistant strictly anchored in verified disposal rules.
 */
object PromptBuilder {

    fun buildDisposalExplanationPrompt(
        category: String,
        rule: DisposalRule?,
        userQuestion: String? = null
    ): String {
        val ruleText = if (rule != null) {
            """
            Canonical Waste Category: ${rule.category}
            Disposal Method: ${rule.disposalMethod}
            Recyclable: ${if (rule.isRecyclable) "Yes" else "No"}
            Hazardous: ${if (rule.isHazardous) "YES - HAZARDOUS" else "No"}
            Target Bin: ${rule.targetBinColor}
            Preparation Instructions: ${rule.preparationInstructions}
            Safety Warnings: ${rule.safetyWarnings}
            Official Source: ${rule.source}
            """.trimIndent()
        } else {
            "No verified rule currently registered for category '$category'."
        }

        val questionPart = if (!userQuestion.isNullOrBlank()) {
            "User Question: $userQuestion"
        } else {
            "Please provide a clear, concise, and helpful summary of how to properly prepare and dispose of this item."
        }

        return """
        <|begin_of_text|><|start_header_id|>system<|end_header_id|>
        You are wasteinfoAI Assistant, an offline environmental sanitation assistant.
        Always follow these strict safety rules:
        1. Ground your answer ONLY in the official disposal rule below. Never invent new rules or recycling policies.
        2. If the item is hazardous, prominently emphasize safety warnings and personal protection.
        3. Keep the response friendly, educational, and easy to understand.
        
        Official Grounding Data:
        $ruleText
        <|eot_id|><|start_header_id|>user<|end_header_id|>
        I have an item identified as '$category'.
        $questionPart
        <|eot_id|><|start_header_id|>assistant<|end_header_id|>
        """.trimIndent()
    }
}
