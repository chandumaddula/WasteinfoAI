package com.wasteinfoai.ai.llm

import com.wasteinfoai.ai.rules.DisposalRule
import com.wasteinfoai.ai.rules.DisposalRuleEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AirplaneModeTest {

    private lateinit var ruleEngine: DisposalRuleEngine
    private lateinit var responder: OfflineResponder

    private val rules = listOf(
        DisposalRule(
            id = "RULE-PLASTIC-001",
            category = "plastic",
            specificItem = "PET Beverage Bottle",
            disposalMethod = "Dry Recyclables Collection",
            isRecyclable = true,
            isHazardous = false,
            preparationInstructions = "Empty and rinse clean.",
            safetyWarnings = "Do not burn.",
            targetBinColor = "BLUE",
            source = "SWM 2016",
            reuseIdeas = listOf("Self-watering planter", "Desk organizer"),
            cleaningTips = "Rinse with water."
        ),
        DisposalRule(
            id = "RULE-BATTERY-001",
            category = "Battery",
            specificItem = "Lithium-Ion Battery",
            disposalMethod = "Authorized E-Waste Depot",
            isRecyclable = true,
            isHazardous = true,
            preparationInstructions = "Tape terminals.",
            safetyWarnings = "Fire and chemical burn hazard.",
            targetBinColor = "RED",
            source = "E-Waste 2022"
        )
    )

    @Before
    fun setup() {
        ruleEngine = DisposalRuleEngine(rules)
        responder = OfflineResponder()
    }

    @Test
    fun testAirplaneModeOperatesCompletelyOfflineWithoutNetwork() = runBlocking {
        // In airplane mode: hybrid is OFF, cloud client is disabled, no network access.
        val cloudClient = CloudAssistantClient(config = HybridConfig(isEnabled = false))
        assertFalse("Cloud client must not be eligible in airplane mode", cloudClient.isEligible())

        val context = AssistantContext(category = "plastic", specificItem = "PET Beverage Bottle")
        val response = responder.respond(
            context = context,
            rule = ruleEngine.getRuleForCategory("plastic"),
            query = "How to clean this item?"
        )

        assertNotNull(response)
        assertTrue("Must provide grounded cleaning tips offline", response.contains("Rinse with water"))
        assertTrue("Must provide target bin offline", response.contains("BLUE BIN"))
    }

    @Test
    fun testAirplaneModeHazardousSafetyRemainsIntact() = runBlocking {
        val context = AssistantContext(category = "Battery", specificItem = "Lithium-Ion Battery")
        val response = responder.respond(
            context = context,
            rule = ruleEngine.getRuleForCategory("Battery"),
            query = "Can I craft something with this?"
        )

        assertNotNull(response)
        assertTrue("Safety refusal must trigger without any network dependency", response.contains("SAFETY REFUSAL"))
        assertTrue("Red bin must be specified offline", response.contains("RED BIN"))
    }
}

