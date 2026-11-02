package com.wasteinfoai.ai.llm

enum class AssistantIntent { SAFETY, CLEANING, UPCYCLE, BIN_LOCATION, GENERAL }

/**
 * Keyword intent detection. Priority: SAFETY > CLEANING > UPCYCLE > BIN_LOCATION > GENERAL.
 * English words match as whole words; Indic-script keywords match as substrings (word boundaries are unreliable
 * for combining marks). "food" is a weak cleaning hint, used only when nothing else matched, so
 * "Can I reuse this food container?" is an UPCYCLE question, not a cleaning one.
 * Indic keyword lists are short on purpose: have a native speaker extend them.
 */
object IntentDetector {

    private val SAFETY_EN = listOf("burn", "fire", "toxic", "poison", "child", "danger")
    private val CLEANING_EN = listOf("clean", "wash", "dirty", "rinse", "grease")
    private val CLEANING_WEAK_EN = listOf("food")
    private val UPCYCLE_EN = listOf("reuse", "diy", "craft", "pot", "upcycle")
    private val BIN_EN = listOf("bin", "color", "colour", "where", "throw")

    private val SAFETY_INDIC = listOf("खतरनाक", "जहरीला", "जलाना", "आग", "ప్రమాదకర", "విషపూరిత", "కాల్చ", "మంట",
        "ஆபத்து", "விஷம்", "எரி", "தீ", "धोकादायक", "विषारी", "जाळ")
    private val CLEANING_INDIC = listOf("साफ", "धोना", "गंदा", "सफाई", "శుభ్రం", "కడగ", "మురికి",
        "சுத்தம்", "கழுவ", "அழுக்கு", "स्वच्छ", "धुणे", "घाण")
    private val UPCYCLE_INDIC = listOf("दोबारा उपयोग", "पुनः उपयोग", "गमला", "क्राफ्ट", "తిరిగి ఉపయోగ", "కుండీ",
        "క్రాఫ్ట్", "மறுபயன்பாடு", "கைவினை", "पुनर्वापर", "हस्तकला")
    private val BIN_INDIC = listOf("डिब्बा", "कहाँ", "फेंक", "డబ్బా", "ఎక్కడ", "పారవేయ", "தொட்டி", "எங்கே", "அகற்ற",
        "कुंडी", "कुठे", "टाकू")

    private fun hasWord(text: String, words: List<String>): Boolean =
        words.any { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }

    private fun hasSubstring(text: String, parts: List<String>): Boolean = parts.any { text.contains(it) }

    /** [languageCode] is accepted for callers that pass it; every language is checked regardless. */
    @Suppress("UNUSED_PARAMETER")
    fun detect(query: String, languageCode: String? = null): AssistantIntent {
        val q = query.trim()
        if (q.isEmpty()) return AssistantIntent.GENERAL
        return when {
            hasWord(q, SAFETY_EN) || hasSubstring(q, SAFETY_INDIC) -> AssistantIntent.SAFETY
            hasWord(q, CLEANING_EN) || hasSubstring(q, CLEANING_INDIC) -> AssistantIntent.CLEANING
            hasWord(q, UPCYCLE_EN) || hasSubstring(q, UPCYCLE_INDIC) -> AssistantIntent.UPCYCLE
            hasWord(q, BIN_EN) || hasSubstring(q, BIN_INDIC) -> AssistantIntent.BIN_LOCATION
            hasWord(q, CLEANING_WEAK_EN) -> AssistantIntent.CLEANING
            else -> AssistantIntent.GENERAL
        }
    }
}
