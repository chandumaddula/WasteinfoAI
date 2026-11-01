package com.wasteinfoai.ai.llm

import com.wasteinfoai.ai.rules.DisposalRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardousRefusalTest {

    private val responder = OfflineResponder()

    private val batteryRule = DisposalRule(
        id = "RULE-BATTERY-001",
        category = "Battery",
        specificItem = "Lithium-Ion Battery",
        disposalMethod = "Authorized E-Waste Center",
        isRecyclable = true,
        isHazardous = true,
        preparationInstructions = "Tape terminals.",
        safetyWarnings = "Fire and chemical burn hazard.",
        targetBinColor = "RED",
        source = "E-Waste Rules 2022"
    )

    @Test
    fun testBatteryReuseReturnsSafetyRefusal() {
        val context = AssistantContext(category = "Battery", specificItem = "Lithium-Ion Battery")

        val response = responder.respond(
            context = context,
            rule = batteryRule,
            query = "Can I reuse this?"
        )

        assertTrue("Must contain safety refusal advisory", response.contains("SAFETY REFUSAL", ignoreCase = true))
        assertTrue("Must state that item cannot be reused", response.contains("cannot be reused", ignoreCase = true))
        assertTrue("Must direct to authorized hazardous disposal", response.contains("RED BIN") || response.contains("Authorized E-Waste Center"))
    }

    @Test
    fun testBatteryBurnReturnsSafetyRefusal() {
        val context = AssistantContext(category = "Battery", specificItem = "Lithium-Ion Battery")

        val response = responder.respond(
            context = context,
            rule = batteryRule,
            query = "Can I burn this?"
        )

        assertTrue("Must refuse burning of battery", response.contains("SAFETY REFUSAL", ignoreCase = true))
        assertTrue("Must emphasize fire and chemical risks", response.contains("fire") || response.contains("hazard"))
    }
}

