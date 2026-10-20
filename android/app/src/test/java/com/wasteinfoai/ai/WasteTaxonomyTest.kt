package com.wasteinfoai.ai

import com.wasteinfoai.ai.taxonomy.HierarchicalIdentification
import com.wasteinfoai.ai.taxonomy.IdentificationSource
import com.wasteinfoai.ai.taxonomy.WasteTaxonomy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WasteTaxonomyTest {

    @Test
    fun testAllCanonicalCategoriesHaveCandidates() {
        val canonical17 = listOf(
            "plastic", "metal", "cardboard", "glass", "paper", "organic",
            "Battery", "Mobile", "Keyboard", "Mouse", "Microwave",
            "Television", "Printer", "Washing Machine", "PCB", "Player", "trash"
        )

        for (category in canonical17) {
            val candidates = WasteTaxonomy.getCandidates(category)
            assertTrue("Category '$category' should have specific candidate items", candidates.isNotEmpty())
        }
    }

    @Test
    fun testFindCandidateById() {
        val candidate = WasteTaxonomy.findCandidateById("item-pet-bottle")
        assertNotNull(candidate)
        assertEquals("plastic", candidate!!.coarseCategory)
        assertEquals("PET 01", candidate.materialCode)
        assertEquals("BLUE", candidate.targetBinColor)
    }

    @Test
    fun testHierarchicalIdentificationConfidenceThreshold() {
        // High confidence should not require immediate user confirmation
        val highConf = HierarchicalIdentification.fromVisionClassification("metal", 0.88f, isCropped = true)
        assertFalse(highConf.needsUserConfirmation)
        assertEquals(IdentificationSource.OBJECT_CROP_CLASSIFIER, highConf.identificationSource)

        // Low confidence should trigger user confirmation requirement
        val lowConf = HierarchicalIdentification.fromVisionClassification("plastic", 0.42f, isCropped = false)
        assertTrue(lowConf.needsUserConfirmation)
        assertEquals(IdentificationSource.VISION_CLASSIFIER, lowConf.identificationSource)
    }
}
