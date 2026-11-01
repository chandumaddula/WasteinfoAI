package com.wasteinfoai.ai.llm

import com.wasteinfoai.ai.rules.DisposalRule
import com.wasteinfoai.features.assistant.ChatMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineResolverTest {

    private val responder = OfflineResponder()

    private val glassRule = DisposalRule(
        id = "RULE-GLASS-001",
        category = "glass",
        specificItem = "Glass Beverage Bottle",
        disposalMethod = "Dry Recyclables Collection",
        isRecyclable = true,
        isHazardous = false,
        preparationInstructions = "Empty and rinse clean.",
        safetyWarnings = "Handle with care to prevent cuts.",
        targetBinColor = "BLUE",
        source = "SWM 2016",
        reuseIdeas = listOf("Kitchen storage jar", "Flower vase"),
        cleaningTips = "Rinse thoroughly with water.",
        conditionNotes = mapOf(
            "broken" to "Wrap broken glass shards securely in newspaper or cardboard labeled 'BROKEN GLASS' for worker safety.",
            "crushed" to "Place crushed glass inside a sealed rigid box."
        )
    )

    private val petBottleRule = DisposalRule(
        id = "RULE-PLASTIC-PET",
        category = "plastic",
        specificItem = "PET Beverage Bottle",
        disposalMethod = "Dry Recyclable Waste",
        isRecyclable = true,
        isHazardous = false,
        preparationInstructions = "Flatten and cap.",
        safetyWarnings = "Do not burn.",
        targetBinColor = "BLUE",
        source = "PWM 2016",
        reuseIdeas = listOf("Self-watering planter", "Desk organizer", "Bird feeder"),
        cleaningTips = "Rinse with cold water.",
        conditionNotes = mapOf(
            "broken" to "Broken pieces can still go into the blue bin.",
            "greasy" to "Wash with soap before recycling."
        )
    )

    @Test
    fun testPronounAndConditionResolution() {
        val context = AssistantContext(category = "glass", specificItem = "Glass Beverage Bottle")

        // First turn: "Can I recycle it?"
        val history = listOf(
            ChatMessage(text = "Can I recycle it?", isUser = true),
            ChatMessage(text = "Yes, Glass Beverage Bottle is recyclable in the BLUE bin.", isUser = false)
        )

        // Second turn: "What if it's broken?"
        val response = responder.respond(
            context = context,
            rule = glassRule,
            query = "What if it's broken?",
            history = history
        )

        // Must resolve "it" and return the specific condition handling for broken glass
        assertTrue("Response must mention broken condition handling", response.contains("broken", ignoreCase = true))
        assertTrue("Response must contain the condition advice from rule", response.contains("Wrap broken glass shards securely"))
    }

    @Test
    fun testPetBottleUpcycleQuestionReturnsReuseIdeasNotBinTextOnly() {
        val context = AssistantContext(category = "plastic", specificItem = "PET Beverage Bottle")

        // "Can I make a flower pot out of this?"
        val response = responder.respond(
            context = context,
            rule = petBottleRule,
            query = "Can I make a flower pot out of this?"
        )

        assertTrue("Response must identify upcycling intent and return reuse ideas", response.contains("planter", ignoreCase = true) || response.contains("Upcycling", ignoreCase = true))
        assertTrue("Response must suggest creative reuse", response.contains("Self-watering planter"))
    }
}

