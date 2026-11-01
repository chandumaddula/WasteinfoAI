package com.wasteinfoai.ai.llm

import com.wasteinfoai.ai.rules.DisposalRule
import com.wasteinfoai.ai.rules.SafetyGuard
import com.wasteinfoai.features.assistant.ChatMessage

/**
 * Deterministic offline response generator for Bottle.
 * Resolves pronouns and condition follow-ups, respects intent priority,
 * and strictly prevents hazardous items from being upcycled or burned.
 */
class OfflineResponder {

    private val conditionKeywords = mapOf(
        "broken" to listOf("broken", "shattered", "cracked", "torn", "fractured", "shards"),
        "crushed" to listOf("crushed", "squashed", "flattened", "bent", "compacted"),
        "wet" to listOf("wet", "soaked", "damp", "moist", "washed"),
        "greasy" to listOf("greasy", "oily", "food-stained", "cheese", "dirty", "soiled"),
        "empty" to listOf("empty", "rinsed"),
        "full" to listOf("full", "filled", "leftover")
    )

    fun respond(
        context: AssistantContext,
        rule: DisposalRule?,
        query: String,
        history: List<ChatMessage> = emptyList()
    ): String {
        val itemName = context.specificItem ?: context.category
        val resolvedQuery = resolvePronouns(query, itemName)
        val safety = SafetyGuard.evaluateSafety(context.category, isUncertain = false)
        val isHazardous = safety.isHazardous || rule?.isHazardous == true
        val warning = rule?.safetyWarnings?.takeIf { it.isNotBlank() } ?: safety.primaryWarning

        if (rule == null) {
            return "No verified municipal rule is currently registered for category '${context.category}'. Please consult your local waste authority before disposal."
        }

        // 1. Condition Follow-Up Check ("what if it's broken / greasy / crushed / wet?")
        val detectedCondition = detectCondition(resolvedQuery)
        val conditionAdvice = detectedCondition?.let { rule.conditionNotes[it] }

        // 2. Intent Detection
        val intent = IntentDetector.detect(resolvedQuery)

        // 3. Strict Safety Refusal: Hazardous items cannot be upcycled, reused, or burned!
        if (isHazardous && (intent == AssistantIntent.UPCYCLE || resolvedQuery.contains(Regex("\\b(burn|fire|diy|reuse|craft|pot|upcycle)\\b", RegexOption.IGNORE_CASE)))) {
            return buildString {
                append("⚠️ SAFETY REFUSAL (Hazardous Waste):\n")
                append("$itemName cannot be reused, repurposed, crafted, or burned due to severe safety risks!\n\n")
                append("Safety Warning: $warning\n\n")
                append("Recommended Disposal: Hand over strictly to ${rule.disposalMethod} (${rule.targetBinColor} BIN).")
            }.trim()
        }

        // 4. Intent-specific response generation
        return when (intent) {
            AssistantIntent.SAFETY -> {
                buildString {
                    append("⚠️ SAFETY ADVISORY for $itemName:\n")
                    append("$warning\n\n")
                    if (safety.recommendedActions.isNotEmpty()) {
                        append("Safety Protocol:\n")
                        safety.recommendedActions.forEach { append("• $it\n") }
                        append("\n")
                    }
                    append("Target Bin: ${rule.targetBinColor} BIN (${rule.disposalMethod})")
                    if (conditionAdvice != null) {
                        append("\n\nCondition Note ($detectedCondition): $conditionAdvice")
                    }
                }.trim()
            }

            AssistantIntent.CLEANING -> {
                buildString {
                    append("🧼 Cleaning & Preparation Instructions for $itemName:\n\n")
                    if (!rule.cleaningTips.isNullOrBlank()) {
                        append("${rule.cleaningTips}\n\n")
                    } else {
                        append("${rule.preparationInstructions}\n\n")
                    }
                    if (conditionAdvice != null) {
                        append("Condition Handling ($detectedCondition):\n$conditionAdvice\n\n")
                    }
                    append("Target Disposal: ${rule.targetBinColor} BIN (${if (rule.isRecyclable) "Recyclable" else "Non-Recyclable"}).")
                }.trim()
            }

            AssistantIntent.UPCYCLE -> {
                buildString {
                    append("♻️ Upcycling & Creative Reuse Ideas for $itemName:\n\n")
                    if (rule.reuseIdeas.isNotEmpty()) {
                        rule.reuseIdeas.forEach { idea -> append("• $idea\n") }
                    } else {
                        append("• Clean and repurpose for household storage, gardening pots, or craft projects.\n")
                    }
                    if (rule.environmentalImpact != null) {
                        append("\nEnvironmental Impact: ${rule.environmentalImpact}\n")
                    }
                    if (conditionAdvice != null) {
                        append("\nCondition Note ($detectedCondition): $conditionAdvice\n")
                    }
                    append("\nWhen finished with the item, dispose in ${rule.targetBinColor} BIN.")
                }.trim()
            }

            AssistantIntent.BIN_LOCATION -> {
                buildString {
                    append("📍 Disposal & Bin Segregation for $itemName:\n\n")
                    append("• Target Bin: ${rule.targetBinColor} BIN\n")
                    append("• Disposal Pathway: ${rule.disposalMethod}\n")
                    append("• Recyclable: ${if (rule.isRecyclable) "Yes" else "No"}\n")
                    if (rule.acceptedPathways.isNotEmpty()) {
                        append("• Accepted At: ${rule.acceptedPathways.joinToString(", ")}\n")
                    }
                    if (rule.prohibitedPathways.isNotEmpty()) {
                        append("• Prohibited: ${rule.prohibitedPathways.joinToString("; ")}\n")
                    }
                    if (conditionAdvice != null) {
                        append("\nCondition Note ($detectedCondition): $conditionAdvice\n")
                    }
                }.trim()
            }

            AssistantIntent.GENERAL -> {
                // If a specific condition was asked (e.g. "What if it's broken?")
                if (conditionAdvice != null) {
                    buildString {
                        append("Handling for $detectedCondition $itemName:\n\n")
                        append("$conditionAdvice\n\n")
                        append("Standard Pathway: ${rule.disposalMethod} (${rule.targetBinColor} BIN).")
                    }.trim()
                } else {
                    buildGeneralExplanation(itemName, rule, isHazardous, warning)
                }
            }
        }
    }

    private fun buildGeneralExplanation(
        itemName: String,
        rule: DisposalRule,
        isHazardous: Boolean,
        warning: String
    ): String = buildString {
        if (isHazardous) {
            append("⚠️ SAFETY ADVISORY (Hazardous Waste):\n$warning\n\n")
        }
        append("Guidance for $itemName:\n")
        append("• Recommended Disposal: ${rule.disposalMethod}\n")
        append("• Target Bin: ${rule.targetBinColor} BIN\n")
        append("• Recyclable: ${if (rule.isRecyclable) "Yes" else "No"}\n\n")
        append("Preparation Steps:\n• ${rule.preparationInstructions}\n")
        if (rule.prohibitedPathways.isNotEmpty()) {
            append("\nDo NOT: ${rule.prohibitedPathways.joinToString("; ")}\n")
        }
        if (!rule.environmentalImpact.isNullOrBlank()) {
            append("\nEco Note: ${rule.environmentalImpact}\n")
        }
        append("\n(Bottle Offline Assistant — grounded in ${rule.source})")
    }.trim()

    /**
     * Resolves pronouns "it / this / that / these / the item" to the concrete item name.
     */
    fun resolvePronouns(query: String, itemName: String): String {
        val pronounRegex = Regex("\\b(it|this|that|these|the item|the object)\\b", RegexOption.IGNORE_CASE)
        return pronounRegex.replace(query, itemName)
    }

    /**
     * Detects condition keywords (broken, crushed, wet, greasy, empty, full) from text.
     */
    fun detectCondition(query: String): String? {
        val lower = query.lowercase()
        for ((key, synonyms) in conditionKeywords) {
            if (synonyms.any { syn -> Regex("\\b$syn\\b").containsMatchIn(lower) }) {
                return key
            }
        }
        return null
    }
}

