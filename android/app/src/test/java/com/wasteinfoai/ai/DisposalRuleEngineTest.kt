package com.wasteinfoai.ai

import com.wasteinfoai.ai.rules.DisposalRule
import com.wasteinfoai.ai.rules.DisposalRuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DisposalRuleEngineTest {

    private val sampleRules = listOf(
        DisposalRule(
            id = "RULE-PLASTIC-001",
            category = "plastic",
            specificItem = null,
            materialCode = null,
            jurisdiction = "INDIA_SWM_2016",
            disposalMethod = "Dry Recyclables Collection",
            isRecyclable = true,
            isHazardous = false,
            preparationInstructions = "Empty and rinse clean.",
            safetyWarnings = "Do not burn.",
            targetBinColor = "BLUE",
            source = "SWM 2016"
        ),
        DisposalRule(
            id = "RULE-ITEM-PLASTIC-PET",
            category = "plastic",
            specificItem = "PET Beverage Bottle (RIC 1)",
            materialCode = "PET 01",
            jurisdiction = "INDIA_SWM_2016",
            disposalMethod = "Dry Recyclable Waste / Kabadiwala",
            isRecyclable = true,
            isHazardous = false,
            preparationInstructions = "Crush flat and replace cap.",
            safetyWarnings = "High-grade recyclable polymer.",
            targetBinColor = "BLUE",
            source = "PWM Rules 2016"
        ),
        DisposalRule(
            id = "RULE-CARDBOARD-001",
            category = "cardboard",
            specificItem = null,
            materialCode = null,
            jurisdiction = "INDIA_SWM_2016",
            disposalMethod = "Dry Paper Recycling",
            isRecyclable = true,
            isHazardous = false,
            preparationInstructions = "Flatten clean boxes.",
            safetyWarnings = "Contaminated cardboard cannot be pulped.",
            targetBinColor = "BLUE",
            source = "SWM 2016"
        ),
        DisposalRule(
            id = "RULE-ITEM-CARDBOARD-SOILED",
            category = "cardboard",
            specificItem = "Greasy / Food-Soiled Cardboard",
            materialCode = "PAP 20",
            jurisdiction = "INDIA_SWM_2016",
            disposalMethod = "Domestic Non-Recyclable Waste",
            isRecyclable = false,
            isHazardous = false,
            preparationInstructions = "Tear away greasy bottom.",
            safetyWarnings = "Food grease contaminates clean paper batches.",
            targetBinColor = "BLACK",
            source = "SWM 2016"
        ),
        DisposalRule(
            id = "RULE-BATTERY-001",
            category = "Battery",
            specificItem = null,
            materialCode = null,
            jurisdiction = "INDIA_SWM_2016",
            disposalMethod = "Domestic Hazardous Waste",
            isRecyclable = true,
            isHazardous = true,
            preparationInstructions = "Tape terminals.",
            safetyWarnings = "Thermal runaway hazard.",
            targetBinColor = "RED",
            source = "E-Waste 2022"
        )
    )

    private lateinit var engine: DisposalRuleEngine

    @Before
    fun setUp() {
        engine = DisposalRuleEngine(sampleRules)
    }

    @Test
    fun testExactItemPrecedenceOverCategory() {
        // Generic cardboard is recyclable in BLUE bin
        val categoryRule = engine.getRuleForCategory("cardboard")
        assertNotNull(categoryRule)
        assertTrue(categoryRule!!.isRecyclable)
        assertEquals("BLUE", categoryRule.targetBinColor)

        // Greasy/soiled cardboard is NOT recyclable and must go to BLACK bin
        val soiledRule = engine.getHierarchicalRule("cardboard", specificItem = "Greasy / Food-Soiled Cardboard")
        assertEquals("RULE-ITEM-CARDBOARD-SOILED", soiledRule.id)
        assertFalse(soiledRule.isRecyclable)
        assertEquals("BLACK", soiledRule.targetBinColor)
    }

    @Test
    fun testMaterialCodeLookup() {
        // Material code "PET 01" should resolve to PET item rule even if specificItem is null
        val petRule = engine.getHierarchicalRule("plastic", specificItem = null, materialCode = "PET 01")
        assertEquals("RULE-ITEM-PLASTIC-PET", petRule.id)
        assertEquals("PET 01", petRule.materialCode)
    }

    @Test
    fun testCategoryFallbackWhenSpecificItemUnknown() {
        val rule = engine.getHierarchicalRule("plastic", specificItem = "Unknown Plastic Trinket", materialCode = null)
        assertEquals("RULE-PLASTIC-001", rule.id)
        assertEquals("BLUE", rule.targetBinColor)
    }

    @Test
    fun testHazardousPreservation() {
        // Battery category is hazardous. Any resolved rule must preserve hazardous classification
        val batteryRule = engine.getHierarchicalRule("Battery", specificItem = "Generic Battery")
        assertTrue("Battery must always be classified as hazardous", batteryRule.isHazardous)
        assertEquals("RED", batteryRule.targetBinColor)
    }

    @Test
    fun testConservativeFallbackForUnmappedCategory() {
        val fallback = engine.getHierarchicalRule("completely_unseen_material")
        assertEquals("RULE-FALLBACK-UNKNOWN", fallback.id)
        assertEquals("BLACK", fallback.targetBinColor)
        assertFalse(fallback.isRecyclable)
    }
}
