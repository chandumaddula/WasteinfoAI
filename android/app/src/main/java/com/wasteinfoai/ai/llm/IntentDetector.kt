package com.wasteinfoai.ai.llm

/**
 * Whole-word, case-insensitive keyword matcher determining user intent for waste queries.
 * Strictly respects priority: SAFETY > CLEANING > UPCYCLE > BIN_LOCATION > GENERAL.
 */
object IntentDetector {

    // Whole-word regex patterns with word boundaries \b
    private val SAFETY_PATTERN = Regex(
        "\\b(burn|burns|burning|fire|fires|toxic|toxicity|poison|poisonous|poisons|child|children|kids|danger|dangerous|hazard|hazardous|lethal|acid|harmful)\\b",
        RegexOption.IGNORE_CASE
    )

    private val CLEANING_PATTERN = Regex(
        "\\b(clean|cleaned|cleaning|wash|washed|washes|washing|dirty|rinse|rinsed|rinses|rinsing|grease|greasy|oil|oily|food|soiled|residue)\\b",
        RegexOption.IGNORE_CASE
    )

    private val UPCYCLE_PATTERN = Regex(
        "\\b(reuse|reused|reusing|reusable|diy|craft|crafts|crafting|pot|pots|planter|planters|upcycle|upcycled|upcycling|repurpose|repurposed|make)\\b",
        RegexOption.IGNORE_CASE
    )

    private val BIN_PATTERN = Regex(
        "\\b(bin|bins|color|colors|where|throw|throws|throwing|toss|tossing|dump|dumping|put|segregate|segregation)\\b",
        RegexOption.IGNORE_CASE
    )

    fun detect(query: String?): AssistantIntent {
        if (query.isNullOrBlank()) return AssistantIntent.GENERAL

        val text = query.trim()

        // 1. Highest Priority: SAFETY
        if (SAFETY_PATTERN.containsMatchIn(text)) {
            return AssistantIntent.SAFETY
        }

        // 2. Second Priority: CLEANING
        if (CLEANING_PATTERN.containsMatchIn(text)) {
            return AssistantIntent.CLEANING
        }

        // 3. Third Priority: UPCYCLE
        if (UPCYCLE_PATTERN.containsMatchIn(text)) {
            return AssistantIntent.UPCYCLE
        }

        // 4. Fourth Priority: BIN_LOCATION
        if (BIN_PATTERN.containsMatchIn(text)) {
            return AssistantIntent.BIN_LOCATION
        }

        // 5. Default: GENERAL
        return AssistantIntent.GENERAL
    }
}

