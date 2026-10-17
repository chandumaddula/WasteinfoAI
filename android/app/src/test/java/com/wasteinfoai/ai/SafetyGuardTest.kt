package com.wasteinfoai.ai

import com.wasteinfoai.ai.rules.HazardSeverity
import com.wasteinfoai.ai.rules.SafetyGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGuardTest {

    @Test
    fun `critical hazard item is correctly identified as critical`() {
        val batteryAssessment = SafetyGuard.evaluateSafety("Battery", isUncertain = false)
        assertTrue(batteryAssessment.isHazardous)
        assertEquals(HazardSeverity.CRITICAL, batteryAssessment.severityLevel)
        assertTrue(batteryAssessment.requiresSpecializedFacility)

        val pcbAssessment = SafetyGuard.evaluateSafety("PCB", isUncertain = false)
        assertTrue(pcbAssessment.isHazardous)
        assertEquals(HazardSeverity.CRITICAL, pcbAssessment.severityLevel)
    }

    @Test
    fun `high hazard electronics require specialized facility`() {
        val microwaveAssessment = SafetyGuard.evaluateSafety("Microwave", isUncertain = false)
        assertTrue(microwaveAssessment.isHazardous)
        assertEquals(HazardSeverity.HIGH, microwaveAssessment.severityLevel)

        val mobileAssessment = SafetyGuard.evaluateSafety("Mobile", isUncertain = false)
        assertTrue(mobileAssessment.isHazardous)
        assertEquals(HazardSeverity.HIGH, mobileAssessment.severityLevel)
    }

    @Test
    fun `physical hazards are flagged with moderate severity`() {
        val glassAssessment = SafetyGuard.evaluateSafety("glass", isUncertain = false)
        assertFalse(glassAssessment.isHazardous)
        assertEquals(HazardSeverity.MODERATE, glassAssessment.severityLevel)

        val metalAssessment = SafetyGuard.evaluateSafety("metal", isUncertain = false)
        assertFalse(metalAssessment.isHazardous)
        assertEquals(HazardSeverity.MODERATE, metalAssessment.severityLevel)
    }

    @Test
    fun `standard recyclable item is evaluated as low severity`() {
        val plasticAssessment = SafetyGuard.evaluateSafety("plastic", isUncertain = false)
        assertFalse(plasticAssessment.isHazardous)
        assertEquals(HazardSeverity.LOW, plasticAssessment.severityLevel)

        val cardboardAssessment = SafetyGuard.evaluateSafety("cardboard", isUncertain = false)
        assertFalse(cardboardAssessment.isHazardous)
        assertEquals(HazardSeverity.LOW, cardboardAssessment.severityLevel)
    }

    @Test
    fun `uncertain classification triggers conservative cautionary guidance`() {
        val uncertainAssessment = SafetyGuard.evaluateSafety("unknown_item", isUncertain = true)
        assertEquals(HazardSeverity.MODERATE, uncertainAssessment.severityLevel)
        assertTrue(uncertainAssessment.primaryWarning.contains("Uncertain", ignoreCase = true))
    }
}
