package com.wasteinfoai.ai

import com.wasteinfoai.ai.rules.DisposalRuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests verification of localized disposal rules across all 5 supported languages:
 * 1. English (en)
 * 2. Hindi (hi)
 * 3. Telugu (te)
 * 4. Tamil (ta)
 * 5. Marathi (mr)
 */
class MultilingualDisposalRulesTest {

    private val supportedLanguages = listOf("en", "hi", "te", "ta", "mr")

    private fun findRuleFile(lang: String): File {
        val candidates = listOf(
            File("src/main/assets/rules/disposal_rules_$lang.json"),
            File("app/src/main/assets/rules/disposal_rules_$lang.json"),
            File("android/app/src/main/assets/rules/disposal_rules_$lang.json"),
            File("D:/PROJECTS/WasteinfoAI/android/app/src/main/assets/rules/disposal_rules_$lang.json")
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not locate disposal_rules_$lang.json")
    }

    @Test
    fun testAllFiveRuleFilesExistAndLoadSuccessfully() {
        for (lang in supportedLanguages) {
            val file = findRuleFile(lang)
            assertTrue("Rule file for $lang should exist", file.exists())
            val jsonContent = file.readText(Charsets.UTF_8)
            assertTrue("Rule file for $lang should not be empty", jsonContent.isNotBlank())

            val engine = DisposalRuleEngine(jsonContent)
            val rules = engine.getAllRules()
            assertEquals("File for $lang must contain exactly 27 rules", 27, rules.size)
        }
    }

    @Test
    fun testRuleIdentifiersAndInvariantsMatchEnglish() {
        val enEngine = DisposalRuleEngine(findRuleFile("en").readText(Charsets.UTF_8))
        val enRules = enEngine.getAllRules()
        val enIds = enRules.map { it.id }

        for (lang in listOf("hi", "te", "ta", "mr")) {
            val localizedEngine = DisposalRuleEngine(findRuleFile(lang).readText(Charsets.UTF_8))
            val localizedRules = localizedEngine.getAllRules()

            assertEquals("Rule count mismatch for $lang", enRules.size, localizedRules.size)
            assertEquals("Rule IDs must match English in order for $lang", enIds, localizedRules.map { it.id })

            for (i in enRules.indices) {
                val enRule = enRules[i]
                val locRule = localizedRules[i]

                assertEquals("Category must match for ${enRule.id} in $lang", enRule.category, locRule.category)
                assertEquals("Specific item must match for ${enRule.id} in $lang", enRule.specificItem, locRule.specificItem)
                assertEquals("Material code must match for ${enRule.id} in $lang", enRule.materialCode, locRule.materialCode)
                assertEquals("Jurisdiction must match for ${enRule.id} in $lang", enRule.jurisdiction, locRule.jurisdiction)
                assertEquals("isHazardous invariant must match for ${enRule.id} in $lang", enRule.isHazardous, locRule.isHazardous)
                assertEquals("isRecyclable invariant must match for ${enRule.id} in $lang", enRule.isRecyclable, locRule.isRecyclable)
                assertEquals("targetBinColor must match for ${enRule.id} in $lang", enRule.targetBinColor, locRule.targetBinColor)

                // Verify localized user-facing fields are present and not blank
                assertTrue("disposalMethod in $lang must not be blank", locRule.disposalMethod.isNotBlank())
                assertTrue("preparationInstructions in $lang must not be blank", locRule.preparationInstructions.isNotBlank())
                assertTrue("safetyWarnings in $lang must not be blank", locRule.safetyWarnings.isNotBlank())
                assertTrue("source in $lang must not be blank", locRule.source.isNotBlank())
            }
        }
    }

    @Test
    fun testAllSeventeenCategoriesAvailableInAllLanguages() {
        val expectedCategories = setOf(
            "Battery", "cardboard", "glass", "Keyboard", "metal",
            "Microwave", "Mobile", "Mouse", "organic", "paper",
            "PCB", "plastic", "Player", "Printer", "Television",
            "trash", "Washing Machine"
        )

        for (lang in supportedLanguages) {
            val engine = DisposalRuleEngine(findRuleFile(lang).readText(Charsets.UTF_8))
            for (category in expectedCategories) {
                val rule = engine.getRuleForCategory(category)
                assertNotNull("Category '$category' must resolve in language '$lang'", rule)
            }
        }
    }

    @Test
    fun testHazardousClassificationPreservedInAllLanguages() {
        for (lang in supportedLanguages) {
            val engine = DisposalRuleEngine(findRuleFile(lang).readText(Charsets.UTF_8))
            val batteryRule = engine.getHierarchicalRule("Battery")
            assertTrue("Battery must remain hazardous in $lang", batteryRule.isHazardous)
            assertEquals("Battery bin must be RED in $lang", "RED", batteryRule.targetBinColor)

            val pcbRule = engine.getHierarchicalRule("PCB")
            assertTrue("PCB must remain hazardous in $lang", pcbRule.isHazardous)
            assertEquals("PCB bin must be RED in $lang", "RED", pcbRule.targetBinColor)

            val brokenGlassRule = engine.getHierarchicalRule("glass", specificItem = "Broken Glass / Shards")
            assertTrue("Broken glass must remain hazardous in $lang", brokenGlassRule.isHazardous)
            assertEquals("Broken glass bin must be RED in $lang", "RED", brokenGlassRule.targetBinColor)
        }
    }
}

