package com.wasteinfoai.ai.llm

import com.wasteinfoai.features.assistant.ChatMessage

/**
 * Interface for formatting chat turns into LLM-compatible prompt strings.
 * Enables drop-in support for Llama 3, SmolLM2, Qwen2.5, etc.
 */
interface ChatTemplate {
    fun format(
        systemPrompt: String,
        history: List<ChatMessage>,
        currentMessage: String,
        groundingData: String
    ): String

    fun stopTokens(): List<String>
}

/**
 * Llama 3 / 3.1 / 3.2 Chat Template implementation with special header tokens:
 * <|begin_of_text|><|start_header_id|>system<|end_header_id|>
 * ...<|eot_id|><|start_header_id|>user<|end_header_id|>...
 */
class Llama3ChatTemplate : ChatTemplate {

    override fun stopTokens(): List<String> = listOf("<|eot_id|>", "<|end_of_text|>")

    override fun format(
        systemPrompt: String,
        history: List<ChatMessage>,
        currentMessage: String,
        groundingData: String
    ): String = buildString {
        append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n")
        append(systemPrompt.trim())
        if (groundingData.isNotBlank()) {
            append("\n\nOfficial Grounding Data:\n")
            append(groundingData.trim())
        }
        append("<|eot_id|>")

        // Multi-turn history: pairs of user and assistant turns
        for (msg in history) {
            val role = if (msg.isUser) "user" else "assistant"
            val sanitized = sanitize(msg.text)
            if (sanitized.isNotBlank()) {
                append("<|start_header_id|>$role<|end_header_id|>\n\n")
                append(sanitized)
                append("<|eot_id|>")
            }
        }

        // Current turn
        val safeCurrent = sanitize(currentMessage)
        append("<|start_header_id|>user<|end_header_id|>\n\n")
        append(safeCurrent)
        append("<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n")
    }

    private fun sanitize(text: String): String {
        return text
            .replace("<|", "< |")
            .replace("|>", "| >")
            .trim()
    }
}

