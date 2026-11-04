package com.wasteinfoai.ai.rules

/**
 * Safety assessment result returned by [SafetyGuard].
 */
data class SafetyAssessment(
    val isHazardous: Boolean,
    val severityLevel: HazardSeverity,
    val primaryWarning: String,
    val recommendedActions: List<String>,
    val requiresSpecializedFacility: Boolean
)

enum class HazardSeverity {
    LOW,
    MODERATE,
    HIGH,
    CRITICAL
}

/**
 * Conservative safety system ensuring users are not directed to handle hazardous waste unsafely.
 */
object SafetyGuard {

    private val CRITICAL_HAZARDS = setOf("Battery", "PCB", "chemicals", "biohazard", "asbestos", "hazardous")
    private val HIGH_HAZARDS = setOf("Microwave", "Mobile", "Television", "Printer")
    private val MODERATE_HAZARDS = setOf("glass", "metal")
    // Previously fell through to "standard domestic waste ... flatten cardboard" advice.
    private val E_WASTE_GENERAL = setOf("Keyboard", "Mouse", "Player", "Washing Machine", "E-waste")

    fun evaluateSafety(category: String, isUncertain: Boolean): SafetyAssessment {
        if (isUncertain) {
            return SafetyAssessment(
                isHazardous = false,
                severityLevel = HazardSeverity.MODERATE,
                primaryWarning = "Uncertain material identity. The classification confidence is insufficient for verified safety guarantees.",
                recommendedActions = listOf(
                    "Do not burn, crush, or mix unknown waste materials.",
                    "Handle with protective gloves if sharp, wet, or chemical residue is present.",
                    "If unsure, bring item to a local municipal sorting facility or consult official waste guidelines."
                ),
                requiresSpecializedFacility = false
            )
        }

        val normalized = category.trim()

        if (CRITICAL_HAZARDS.any { it.equals(normalized, ignoreCase = true) }) {
            return SafetyAssessment(
                isHazardous = true,
                severityLevel = HazardSeverity.CRITICAL,
                primaryWarning = "CRITICAL HAZARD: Item contains corrosive chemicals, heavy metals, or thermal runaway risks.",
                recommendedActions = listOf(
                    "DO NOT place in regular household trash, recycling, or incinerator.",
                    "Tape battery terminals with non-conductive tape.",
                    "Store in a cool, dry, flame-resistant location away from children and pets.",
                    "Deliver to a certified hazardous waste or electronic waste recycling depot."
                ),
                requiresSpecializedFacility = true
            )
        }

        if (HIGH_HAZARDS.any { it.equals(normalized, ignoreCase = true) }) {
            return SafetyAssessment(
                isHazardous = true,
                severityLevel = HazardSeverity.HIGH,
                primaryWarning = "ELECTRICAL / HIGH-VOLTAGE HAZARD: Electronic device contains capacitors or toxic components.",
                recommendedActions = listOf(
                    "DO NOT attempt to disassemble, open, or break open display screens.",
                    "Keep intact and transport safely to an official e-waste collection center.",
                    "Remove loose batteries or toner cartridges separately if safe to do so."
                ),
                requiresSpecializedFacility = true
            )
        }

        if (E_WASTE_GENERAL.any { it.equals(normalized, ignoreCase = true) }) {
            return SafetyAssessment(
                isHazardous = false,
                severityLevel = HazardSeverity.MODERATE,
                primaryWarning = "E-WASTE: Electronic item. Do not place in household trash or recycling bins.",
                recommendedActions = listOf(
                    "Take it to an authorized e-waste collection center or producer take-back point.",
                    "Remove loose batteries first and keep them separate.",
                    "Do not open or crush the device."
                ),
                requiresSpecializedFacility = true
            )
        }

        if (MODERATE_HAZARDS.any { it.equals(normalized, ignoreCase = true) }) {
            return SafetyAssessment(
                isHazardous = false,
                severityLevel = HazardSeverity.MODERATE,
                primaryWarning = "PHYSICAL CUT / LACERATION HAZARD: Material may have sharp edges or shattered fragments.",
                recommendedActions = listOf(
                    "Handle carefully to avoid cuts or punctures.",
                    "Wrap broken glass in cardboard or paper before handling.",
                    "Push sharp can lids down inside the container."
                ),
                requiresSpecializedFacility = false
            )
        }

        return SafetyAssessment(
            isHazardous = false,
            severityLevel = HazardSeverity.LOW,
            primaryWarning = "Standard domestic waste material. Follow routine sorting and recycling segregation.",
            recommendedActions = listOf(
                "Ensure material is empty, clean, and dry before bin placement.",
                "Flatten bulky paper, cardboard, or plastic containers to conserve collection space."
            ),
            requiresSpecializedFacility = false
        )
    }

    fun isHazardous(category: String): Boolean {
        val normalized = category.trim()
        return CRITICAL_HAZARDS.any { it.equals(normalized, ignoreCase = true) } ||
                HIGH_HAZARDS.any { it.equals(normalized, ignoreCase = true) }
    }
}
