package com.wasteinfoai.ai.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class IntentDetectorTest {

    @Test
    fun testSafetyPriorityOverAll() {
        // Contains SAFETY ('burn') + CLEANING ('clean') + UPCYCLE ('pot')
        val q1 = "Can I burn this clean bottle to make a pot?"
        assertEquals(AssistantIntent.SAFETY, IntentDetector.detect(q1))

        val q2 = "Is this chemical toxic to children if touched?"
        assertEquals(AssistantIntent.SAFETY, IntentDetector.detect(q2))

        val q3 = "What is the fire danger of this battery?"
        assertEquals(AssistantIntent.SAFETY, IntentDetector.detect(q3))
    }

    @Test
    fun testCleaningPriorityOverUpcycleAndBin() {
        // Contains CLEANING ('wash') + UPCYCLE ('reuse')
        val q1 = "How should I wash this before I reuse it?"
        assertEquals(AssistantIntent.CLEANING, IntentDetector.detect(q1))

        // Contains CLEANING ('grease') + BIN ('throw')
        val q2 = "It is covered in grease and food, where do I throw it?"
        assertEquals(AssistantIntent.CLEANING, IntentDetector.detect(q2))
    }

    @Test
    fun testUpcyclePriorityOverBin() {
        // Contains UPCYCLE ('craft', 'pot') + BIN ('bin')
        val q1 = "Can I craft a flower pot instead of tossing in the bin?"
        assertEquals(AssistantIntent.UPCYCLE, IntentDetector.detect(q1))

        val q2 = "Are there any DIY reuse ideas for this container?"
        assertEquals(AssistantIntent.UPCYCLE, IntentDetector.detect(q2))
    }

    @Test
    fun testBinLocation() {
        val q1 = "Which bin color does this item belong to?"
        assertEquals(AssistantIntent.BIN_LOCATION, IntentDetector.detect(q1))

        val q2 = "Where should I throw this?"
        assertEquals(AssistantIntent.BIN_LOCATION, IntentDetector.detect(q2))
    }

    @Test
    fun testGeneralFallback() {
        val q1 = "Tell me about this item."
        assertEquals(AssistantIntent.GENERAL, IntentDetector.detect(q1))

        val q2 = ""
        assertEquals(AssistantIntent.GENERAL, IntentDetector.detect(q2))

        val q3 = null
        assertEquals(AssistantIntent.GENERAL, IntentDetector.detect(q3))
    }

    @Test
    fun testWholeWordMatching() {
        // 'pot' is a keyword, but 'spot' or 'potato' should not trigger UPCYCLE
        val q1 = "I found this near a potato field."
        assertEquals(AssistantIntent.GENERAL, IntentDetector.detect(q1))

        // 'bin' is a keyword, but 'combine' should not trigger BIN_LOCATION
        val q2 = "Can I combine these two items?"
        assertEquals(AssistantIntent.GENERAL, IntentDetector.detect(q2))
    }
}

