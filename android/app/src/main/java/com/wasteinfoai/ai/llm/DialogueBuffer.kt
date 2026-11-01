package com.wasteinfoai.ai.llm

import com.wasteinfoai.features.assistant.ChatMessage

/**
 * Manages active multi-turn conversation memory for Bottle.
 * Keeps at most [maxMessages] turns, trims oldest entries when exceeding [maxCharBudget],
 * and resets cleanly when a new waste item is scanned.
 */
class DialogueBuffer(
    private val maxMessages: Int = 6,
    private val maxCharBudget: Int = 2500
) {
    private val messages = mutableListOf<ChatMessage>()
    var currentItemContext: String? = null
        private set

    @Synchronized
    fun reset(newItem: String?) {
        messages.clear()
        currentItemContext = newItem
    }

    @Synchronized
    fun clear() {
        messages.clear()
    }

    @Synchronized
    fun addMessage(message: ChatMessage) {
        messages.add(message)
        trimToBudget()
    }

    @Synchronized
    fun getHistory(): List<ChatMessage> {
        return messages.toList()
    }

    @Synchronized
    fun size(): Int = messages.size

    private fun trimToBudget() {
        // First trim by count
        while (messages.size > maxMessages) {
            messages.removeAt(0)
        }

        // Then trim by character budget
        var totalChars = messages.sumOf { it.text.length }
        while (messages.size > 1 && totalChars > maxCharBudget) {
            val removed = messages.removeAt(0)
            totalChars -= removed.text.length
        }
    }
}

