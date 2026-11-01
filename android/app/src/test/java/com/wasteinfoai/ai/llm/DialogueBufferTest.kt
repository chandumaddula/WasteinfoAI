package com.wasteinfoai.ai.llm

import com.wasteinfoai.features.assistant.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueBufferTest {

    @Test
    fun testDialogueBufferLimitsToMaxMessages() {
        val buffer = DialogueBuffer(maxMessages = 6, maxCharBudget = 5000)

        for (i in 1..10) {
            buffer.addMessage(ChatMessage(text = "Message $i", isUser = i % 2 != 0))
        }

        assertEquals(6, buffer.size())
        val history = buffer.getHistory()
        // Oldest 4 trimmed; should contain messages 5 to 10
        assertEquals("Message 5", history.first().text)
        assertEquals("Message 10", history.last().text)
    }

    @Test
    fun testDialogueBufferTrimsToCharacterBudget() {
        // Budget 150 chars
        val buffer = DialogueBuffer(maxMessages = 10, maxCharBudget = 150)

        val longText = "A".repeat(80)
        buffer.addMessage(ChatMessage(text = longText, isUser = true))
        buffer.addMessage(ChatMessage(text = longText, isUser = false))
        buffer.addMessage(ChatMessage(text = "Short text", isUser = true))

        val history = buffer.getHistory()
        val totalChars = history.sumOf { it.text.length }
        assertTrue("Total characters must be within budget", totalChars <= 150)
    }

    @Test
    fun testDialogueBufferResetOnNewItem() {
        val buffer = DialogueBuffer(maxMessages = 6)
        buffer.addMessage(ChatMessage(text = "Message 1", isUser = true))
        buffer.addMessage(ChatMessage(text = "Message 2", isUser = false))
        assertEquals(2, buffer.size())

        buffer.reset("Rechargeable Battery")

        assertEquals(0, buffer.size())
        assertEquals("Rechargeable Battery", buffer.currentItemContext)
    }
}

