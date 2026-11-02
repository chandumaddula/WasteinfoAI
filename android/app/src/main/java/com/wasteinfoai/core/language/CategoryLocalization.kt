package com.wasteinfoai.core.language

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Display names for categories and bin colours. Unknown names fall back to English, so a missing translation
 * never breaks an answer. Translations are a starting point: have a native speaker review them.
 */
object CategoryLocalization {

    private val CATEGORY_NAMES: Map<String, Map<String, String>> = mapOf(
        "plastic" to mapOf("hi" to "प्लास्टिक", "te" to "ప్లాస్టిక్", "ta" to "பிளாஸ்டிக்", "mr" to "प्लास्टिक"),
        "metal" to mapOf("hi" to "धातु", "te" to "లోహం", "ta" to "உலோகம்", "mr" to "धातू"),
        "glass" to mapOf("hi" to "काँच", "te" to "గాజు", "ta" to "கண்ணாடி", "mr" to "काच"),
        "paper" to mapOf("hi" to "कागज़", "te" to "కాగితం", "ta" to "காகிதம்", "mr" to "कागद"),
        "cardboard" to mapOf("hi" to "गत्ता", "te" to "అట్ట", "ta" to "அட்டை", "mr" to "पुठ्ठा"),
        "organic" to mapOf("hi" to "जैविक कचरा", "te" to "సేంద్రీయ వ్యర్థాలు", "ta" to "கரிமக் கழிவு", "mr" to "सेंद्रिय कचरा"),
        "battery" to mapOf("hi" to "बैटरी", "te" to "బ్యాటరీ", "ta" to "பேட்டரி", "mr" to "बॅटरी"),
        "mouse" to mapOf("hi" to "माउस (Mouse)", "te" to "మౌస్ (Mouse)", "ta" to "மவுஸ் (Mouse)", "mr" to "माउस (Mouse)"),
        "keyboard" to mapOf("hi" to "कीबोर्ड (Keyboard)", "te" to "కీబోర్డ్ (Keyboard)", "ta" to "விசைப்பலகை (Keyboard)", "mr" to "कीबोर्ड (Keyboard)"),
        "mobile" to mapOf("hi" to "मोबाइल (Mobile)", "te" to "మొబైల్ (Mobile)", "ta" to "மொபைல் (Mobile)", "mr" to "मोबाईल (Mobile)"),
        "e-waste" to mapOf("hi" to "ई-कचरा (E-waste)", "te" to "ఇ-వ్యర్థాలు (E-waste)", "ta" to "மின்-கழிவு (E-waste)", "mr" to "ई-कचरा (E-waste)"),
        "trash" to mapOf("hi" to "कचरा", "te" to "చెత్త", "ta" to "குப்பை", "mr" to "कचरा")
    )

    private val BIN_COLORS: Map<String, Map<String, String>> = mapOf(
        "BLUE" to mapOf("hi" to "नीला", "te" to "నీలం", "ta" to "நீலம்", "mr" to "निळा"),
        "GREEN" to mapOf("hi" to "हरा", "te" to "ఆకుపచ్చ", "ta" to "பச்சை", "mr" to "हिरवा"),
        "RED" to mapOf("hi" to "लाल", "te" to "ఎరుపు", "ta" to "சிவப்பு", "mr" to "लाल"),
        "BLACK" to mapOf("hi" to "काला", "te" to "నలుపు", "ta" to "கருப்பு", "mr" to "काळा"),
        "ORANGE" to mapOf("hi" to "नारंगी", "te" to "నారింజ", "ta" to "ஆரஞ்சு", "mr" to "नारिंगी")
    )

    private val BIN_WORD = mapOf("hi" to "डिब्बा", "te" to "డబ్బా", "ta" to "தொட்டி", "mr" to "कुंडी")

    fun getCategoryDisplayName(name: String, lang: String): String {
        if (lang == "en") return name
        return CATEGORY_NAMES[name.trim().lowercase()]?.get(lang) ?: name
    }

    /** "BLUE BIN" in English, "नीला डिब्बा" in Hindi, and so on. Empty input gives an empty string. */
    fun getBinColorDisplayName(color: String, lang: String): String {
        val key = color.trim().uppercase()
        if (key.isEmpty()) return ""
        if (key == "SPECIAL_DEPOT") return if (lang == "en") "SPECIAL DEPOT" else "SPECIAL DEPOT"
        val word = BIN_WORD[lang] ?: "BIN"
        val name = if (lang == "en") key else (BIN_COLORS[key]?.get(lang) ?: key)
        return "$name $word"
    }

    @Composable
    fun localizedCategory(category: String): String {
        val config = LocalConfiguration.current
        val lang = config.locales[0]?.language ?: "en"
        return getCategoryDisplayName(category, lang)
    }

    @Composable
    fun localizedBinColor(binColor: String): String {
        val config = LocalConfiguration.current
        val lang = config.locales[0]?.language ?: "en"
        return getBinColorDisplayName(binColor, lang)
    }
}
