package com.wasteinfoai.core.language

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Display names for categories and bin colours. Unknown names fall back to English, so a missing translation
 * never breaks an answer. Translations are a starting point: have a native speaker review them.
 */
object CategoryLocalization {

    private val CATEGORY_NAMES: Map<String, Map<String, String>> = mapOf(
        "plastic" to mapOf(
            "hi" to "प्लास्टिक", "te" to "ప్లాస్టిక్", "ta" to "பிளாஸ்டிக்", "mr" to "प्लास्टिक",
            "kn" to "ಪ್ಲಾಸ್ಟಿಕ್", "ml" to "പ്ലാസ്റ്റിക്", "bn" to "প্লাস্টিক", "gu" to "પ્લાસ્ટિક"
        ),
        "metal" to mapOf(
            "hi" to "धातु", "te" to "లోహం", "ta" to "உலோகம்", "mr" to "धातू",
            "kn" to "ಲೋಹ", "ml" to "ലോഹം", "bn" to "ধাতু", "gu" to "ધાતુ"
        ),
        "glass" to mapOf(
            "hi" to "काँच", "te" to "గాజు", "ta" to "கண்ணாடி", "mr" to "काच",
            "kn" to "ಗಾಜು", "ml" to "ഗ്ലാസ്", "bn" to "কাঁচ", "gu" to "કાચ"
        ),
        "paper" to mapOf(
            "hi" to "कागज़", "te" to "కాగితం", "ta" to "காகிதம்", "mr" to "कागद",
            "kn" to "ಕಾಗದ", "ml" to "കടലാസ്", "bn" to "কাগজ", "gu" to "કાગળ"
        ),
        "cardboard" to mapOf(
            "hi" to "गत्ता", "te" to "అట్ట", "ta" to "அட்டை", "mr" to "पुठ्ठा",
            "kn" to "ರಟ್ಟಿನ ಪೆಟ್ಟಿಗೆ", "ml" to "കാർഡ്ബോർഡ്", "bn" to "কার্ডবোর্ড", "gu" to "પૂઠું"
        ),
        "organic" to mapOf(
            "hi" to "जैविक कचरा", "te" to "సేంద్రీయ వ్యర్థాలు", "ta" to "கரிமக் கழிவு", "mr" to "सेंद्रिय कचरा",
            "kn" to "ಸಾವಯವ ತ್ಯಾಜ್ಯ", "ml" to "ജൈവ മാലിന്യം", "bn" to "জৈব বর্জ্য", "gu" to "જૈવિક કચરો"
        ),
        "battery" to mapOf(
            "hi" to "बैटरी", "te" to "బ్యాటరీ", "ta" to "பேட்டரி", "mr" to "बॅटरी",
            "kn" to "ಬ್ಯಾಟರಿ", "ml" to "ബാറ്ററി", "bn" to "ব্যাটারি", "gu" to "બેટરી"
        ),
        "mouse" to mapOf(
            "hi" to "माउस (Mouse)", "te" to "మౌస్ (Mouse)", "ta" to "மவுஸ் (Mouse)", "mr" to "माउस (Mouse)",
            "kn" to "ಮೌಸ್ (Mouse)", "ml" to "മൗസ് (Mouse)", "bn" to "মাউস (Mouse)", "gu" to "માઉસ (Mouse)"
        ),
        "keyboard" to mapOf(
            "hi" to "कीबोर्ड (Keyboard)", "te" to "కీబోర్డ్ (Keyboard)", "ta" to "விசைப்பலகை (Keyboard)", "mr" to "कीबोर्ड (Keyboard)",
            "kn" to "ಕೀಬೋರ್ಡ್ (Keyboard)", "ml" to "കീബോർഡ് (Keyboard)", "bn" to "কীবোর্ড (Keyboard)", "gu" to "કીબોર્ડ (Keyboard)"
        ),
        "mobile" to mapOf(
            "hi" to "मोबाइल (Mobile)", "te" to "మొబైల్ (Mobile)", "ta" to "மொபைல் (Mobile)", "mr" to "मोबाईल (Mobile)",
            "kn" to "ಮೊಬೈಲ್ (Mobile)", "ml" to "മൊബൈൽ (Mobile)", "bn" to "মোবাইল (Mobile)", "gu" to "મોબાઇલ (Mobile)"
        ),
        "e-waste" to mapOf(
            "hi" to "ई-कचरा (E-waste)", "te" to "ఇ-వ్యర్థాలు (E-waste)", "ta" to "மின்-கழிவு (E-waste)", "mr" to "ई-कचरा (E-waste)",
            "kn" to "ಇ-ತ್ಯಾಜ್ಯ (E-waste)", "ml" to "ഇ-മാലിന്യം (E-waste)", "bn" to "ই-বর্জ্য (E-waste)", "gu" to "ઈ-કચરો (E-waste)"
        ),
        "biodegradable" to mapOf(
            "hi" to "जैविक कचरा (Biodegradable)", "te" to "సేంద్రీయ వ్యర్థాలు (Biodegradable)", "ta" to "மக்கும் கழிவு (Biodegradable)", "mr" to "सेंद्रिय कचरा (Biodegradable)",
            "kn" to "ಕೊಳೆಯುವ ತ್ಯಾಜ್ಯ (Biodegradable)", "ml" to "ദ്രവിക്കുന്ന മാലിന്യം (Biodegradable)", "bn" to "পচনশীল বর্জ্য (Biodegradable)", "gu" to "વિઘટનશીલ કચરો (Biodegradable)"
        ),
        "recyclables" to mapOf(
            "hi" to "पुनर्चक्रण योग्य (Recyclables)", "te" to "రీసైకిల్ చేయదగినవి (Recyclables)", "ta" to "மறுசுழற்சி செய்யக்கூடியவை (Recyclables)", "mr" to "पुनर्वापरयोग्य (Recyclables)",
            "kn" to "ಮರುಬಳಕೆ ಮಾಡಬಹುದಾದ (Recyclables)", "ml" to "പുനരുപയോഗിക്കാവുന്നത് (Recyclables)", "bn" to "পুনর্ব্যবহারযোগ্য (Recyclables)", "gu" to "પુનઃઉપયોગી (Recyclables)"
        ),
        "hazardous" to mapOf(
            "hi" to "खतरनाक कचरा (Hazardous)", "te" to "ప్రమాదకర వ్యర్థాలు (Hazardous)", "ta" to "அபாயகரமான கழிவு (Hazardous)", "mr" to "धोकादायक कचरा (Hazardous)",
            "kn" to "ಅಪಾಯಕಾರಿ ತ್ಯಾಜ್ಯ (Hazardous)", "ml" to "അപകടകരമായ മാലിന്യം (Hazardous)", "bn" to "বিপজ্জনক বর্জ্য (Hazardous)", "gu" to "જોખમી કચરો (Hazardous)"
        ),
        "trash" to mapOf(
            "hi" to "कचरा", "te" to "చెత్త", "ta" to "குப்பை", "mr" to "कचरा",
            "kn" to "ಕಸ", "ml" to "മാലിന്യം", "bn" to "আবর্জনা", "gu" to "કચરો"
        )
    )

    private val BIN_COLORS: Map<String, Map<String, String>> = mapOf(
        "BLUE" to mapOf(
            "hi" to "नीला", "te" to "నీలం", "ta" to "நீலம்", "mr" to "निळा",
            "kn" to "ನೀಲಿ", "ml" to "നീല", "bn" to "নীল", "gu" to "વાદળી"
        ),
        "GREEN" to mapOf(
            "hi" to "हरा", "te" to "ఆకుపచ్చ", "ta" to "பச்சை", "mr" to "हिरवा",
            "kn" to "ಹಸಿರು", "ml" to "പച്ച", "bn" to "সবুজ", "gu" to "લીલો"
        ),
        "RED" to mapOf(
            "hi" to "लाल", "te" to "ఎరుపు", "ta" to "சிவப்பு", "mr" to "लाल",
            "kn" to "ಕೆಂಪು", "ml" to "ചുവപ്പ്", "bn" to "লাল", "gu" to "લાલ"
        ),
        "BLACK" to mapOf(
            "hi" to "काला", "te" to "నలుపు", "ta" to "கருப்பு", "mr" to "काळा",
            "kn" to "ಕಪ್ಪು", "ml" to "കറുപ്പ്", "bn" to "কালো", "gu" to "કાળો"
        ),
        "ORANGE" to mapOf(
            "hi" to "नारंगी", "te" to "నారింజ", "ta" to "ஆரஞ்சு", "mr" to "नारिंगी",
            "kn" to "ಕಿತ್ತಳೆ", "ml" to "ഓറഞ്ച്", "bn" to "কমলা", "gu" to "નારંગી"
        )
    )

    private val BIN_WORD = mapOf(
        "hi" to "डिब्बा", "te" to "డబ్బా", "ta" to "தொட்டி", "mr" to "कुंडी",
        "kn" to "ಬುಟ್ಟಿ", "ml" to "തൊട്ടി", "bn" to "পাত্র", "gu" to "ડબ્બો"
    )

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
