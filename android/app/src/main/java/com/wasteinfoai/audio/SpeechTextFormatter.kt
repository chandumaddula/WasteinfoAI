package com.wasteinfoai.audio

import java.util.regex.Pattern

/**
 * Prepares assistant text for natural, human-like Text-to-Speech (TTS).
 * Converts bulleted lists into fluid spoken prose, removes markdown styling,
 * eliminates unpronounceable symbols/emojis, and ensures proper conversational pacing.
 */
object SpeechTextFormatter {

    private val URL_PATTERN = Pattern.compile("https?://\\S+|www\\.\\S+")
    private val MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\([^)]+\\)")

    fun stripEmojisAndSymbols(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            val isEmojiOrSymbol = cp in 0x1F000..0x1FAFF ||
                    cp in 0x2600..0x27BF ||
                    cp in 0xFE00..0xFE0F ||
                    cp == '•'.code || cp == '·'.code ||
                    cp == '—'.code || cp == '–'.code ||
                    cp == '―'.code || cp == '✓'.code ||
                    cp == '✔'.code || cp == '✕'.code ||
                    cp == '✖'.code
            if (!isEmojiOrSymbol) {
                sb.appendCodePoint(cp)
            } else {
                sb.append(' ')
            }
            i += charCount
        }
        return sb.toString()
    }

    /**
     * Automatically identifies the language/script of the text to ensure TTS engine
     * uses the matching pronunciation and voice model.
     */
    fun detectLanguage(text: String, fallbackLang: String? = null): String {
        var teCount = 0
        var taCount = 0
        var devCount = 0
        var knCount = 0
        var mlCount = 0
        var bnCount = 0
        var guCount = 0

        for (ch in text) {
            when (ch.code) {
                in 0x0C00..0x0C7F -> teCount++
                in 0x0B80..0x0BFF -> taCount++
                in 0x0900..0x097F -> devCount++
                in 0x0C80..0x0CFF -> knCount++
                in 0x0D00..0x0D7F -> mlCount++
                in 0x0980..0x09FF -> bnCount++
                in 0x0A80..0x0AFF -> guCount++
            }
        }

        return when {
            teCount > 5 -> "te"
            taCount > 5 -> "ta"
            knCount > 5 -> "kn"
            mlCount > 5 -> "ml"
            bnCount > 5 -> "bn"
            guCount > 5 -> "gu"
            devCount > 5 -> if (fallbackLang == "mr") "mr" else "hi"
            else -> fallbackLang?.trim()?.lowercase()?.take(2) ?: "en"
        }
    }

    fun formatForSpeech(text: String, languageCode: String? = null): String {
        if (text.isBlank()) return ""

        var processed = text.trim()

        // 1. Strip system attribution suffixes and rule citations so TTS speaks ONLY the message
        processed = processed
            .replace(Regex("(?m)^\\s*\\(.*?(?:Bottle AI|బాటిల్|बॉटल|பாட்டில்|Assistant|Rules|SWM|PWM|आधारित|ఆధారంగా|ஆதாரம்|based on|grounded in).*?\\)\\s*$"), "")
            .replace(Regex("\\(.*?(?:Bottle AI|బాటిల్|बॉटल|பாட்டில்|Assistant|Rules|SWM|PWM|आधारित|ఆధారంగా|ஆதாரம்|based on|grounded in).*?\\)"), "")

        // 2. Convert visual-only warning banners to fluid spoken warnings
        processed = processed
            .replace(Regex("(?i)⚠️?\\s*SAFETY\\s+REFUSAL.*?[:—\\n]"), "Safety warning: ")
            .replace(Regex("(?i)⚠️?\\s*SAFETY\\s+ADVISORY.*?[:—\\n]"), "Safety advisory: ")
            .replace(Regex("⚠️?\\s*భద్రతా\\s*(?:నిరాకరణ|సలహా).*?[:—\\n]"), "భద్రతా హెచ్చరిక: ")
            .replace(Regex("⚠️?\\s*सुरक्षा\\s*(?:अस्वीकृति|सलाह).*?[:—\\n]"), "सुरक्षा चेतावनी: ")
            .replace(Regex("⚠️?\\s*பாதுகாப்பு\\s*(?:மறுப்பு|ஆலோசனை).*?[:—\\n]"), "பாதுகாப்பு எச்சரிக்கை: ")
            .replace(Regex("⚠️?\\s*सुरक्षा\\s*(?:नकार|सूचना).*?[:—\\n]"), "सुरक्षा सूचना: ")
            .replace(Regex("⚠️?\\s*ಸುರಕ್ಷತಾ\\s*(?:ನಿರಾಕರಣೆ|ಸಲಹೆ|ಎಚ್ಚರಿಕೆ).*?[:—\\n]"), "ಸುರಕ್ಷತಾ ಎಚ್ಚರಿಕೆ: ")
            .replace(Regex("⚠️?\\s*സുരക്ഷാ\\s*(?:നിരസിക്കൽ|മുന്നറിയിപ്പ്).*?[:—\\n]"), "സുരക്ഷാ മുന്നറിയിപ്പ്: ")
            .replace(Regex("⚠️?\\s*নিরাপত্তা\\s*(?:প্রত্যাখ্যান|সতর্কতা).*?[:—\\n]"), "নিরাপত্তা সতর্কতা: ")
            .replace(Regex("⚠️?\\s*સુરક્ષા\\s*(?:ઇનકાર|સલાહ|ચેતવણી).*?[:—\\n]"), "સુરક્ષા ચેતવણી: ")

        // 3. Remove code blocks and inline code ticks
        processed = processed.replace(Regex("```[\\s\\S]*?```"), "")
        processed = processed.replace(Regex("`([^`]+)`"), "$1")

        // 4. Convert markdown links [Label](url) -> Label
        processed = MARKDOWN_LINK_PATTERN.matcher(processed).replaceAll("$1")

        // 5. Remove bare URLs
        processed = URL_PATTERN.matcher(processed).replaceAll("")

        // 6. Remove bold, italic, strikethrough markdown
        processed = processed.replace(Regex("(\\*\\*|__|\\*|_|~~)"), "")

        // 7. Remove markdown headers (# Header -> Header)
        processed = processed.replace(Regex("(?m)^#{1,6}\\s*"), "")

        // 8. Natural spoken transition for bullet points
        val lang = detectLanguage(processed, languageCode)
        processed = convertListsToSpeech(processed, lang)

        // 9. Remove remaining decorative emojis and symbols
        processed = stripEmojisAndSymbols(processed)

        // 10. Clean up whitespace, punctuation duplication, and empty lines
        processed = processed
            .replace(Regex("(?m)^\\s*[-*+]\\s*"), "") // remaining bullet symbols
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("([.!?])\\s*([.!?])+"), "$1")
            .replace(Regex("\\n{2,}"), ". ")
            .replace(Regex("\\n"), " ")
            .trim()

        return processed
    }

    private fun convertListsToSpeech(text: String, languageCode: String?): String {
        val lines = text.lines()
        val result = mutableListOf<String>()
        val bulletItems = mutableListOf<String>()

        val lang = languageCode?.trim()?.lowercase()?.take(2) ?: "en"

        fun flushBullets() {
            if (bulletItems.isEmpty()) return
            // If items are key-value attributes (like "Target Bin: ..."), do not prepend "First, Next, Finally"
            val isKeyValue = bulletItems.all { it.contains(":") }
            if (isKeyValue || bulletItems.size == 1) {
                bulletItems.forEach { item ->
                    result.add(item)
                }
            } else {
                when (lang) {
                    "te" -> {
                        bulletItems.forEachIndexed { idx, item ->
                            val prefix = when (idx) {
                                0 -> "మొదటగా, "
                                bulletItems.lastIndex -> "చివరగా, "
                                else -> "తరువాత, "
                            }
                            result.add("$prefix$item")
                        }
                    }
                    "hi" -> {
                        bulletItems.forEachIndexed { idx, item ->
                            val prefix = when (idx) {
                                0 -> "पहले, "
                                bulletItems.lastIndex -> "अंत में, "
                                else -> "फिर, "
                            }
                            result.add("$prefix$item")
                        }
                    }
                    "ta" -> {
                        bulletItems.forEachIndexed { idx, item ->
                            val prefix = when (idx) {
                                0 -> "முதலில், "
                                bulletItems.lastIndex -> "இறுதியாக, "
                                else -> "அடுத்து, "
                            }
                            result.add("$prefix$item")
                        }
                    }
                    "mr" -> {
                        bulletItems.forEachIndexed { idx, item ->
                            val prefix = when (idx) {
                                0 -> "प्रथम, "
                                bulletItems.lastIndex -> "शेवटी, "
                                else -> "नंतर, "
                            }
                            result.add("$prefix$item")
                        }
                    }
                    "kn" -> {
                        bulletItems.forEachIndexed { idx, item ->
                            val prefix = when (idx) {
                                0 -> "ಮೊದಲಿಗೆ, "
                                bulletItems.lastIndex -> "ಕೊನೆಯದಾಗಿ, "
                                else -> "ನಂತರ, "
                            }
                            result.add("$prefix$item")
                        }
                    }
                    "ml" -> {
                        bulletItems.forEachIndexed { idx, item ->
                            val prefix = when (idx) {
                                0 -> "ആദ്യമായി, "
                                bulletItems.lastIndex -> "അവസാനമായി, "
                                else -> "തുടർന്ന്, "
                            }
                            result.add("$prefix$item")
                        }
                    }
                    "bn" -> {
                        bulletItems.forEachIndexed { idx, item ->
                            val prefix = when (idx) {
                                0 -> "প্রথমে, "
                                bulletItems.lastIndex -> "অবশেষে, "
                                else -> "তারপর, "
                            }
                            result.add("$prefix$item")
                        }
                    }
                    "gu" -> {
                        bulletItems.forEachIndexed { idx, item ->
                            val prefix = when (idx) {
                                0 -> "પ્રથમ, "
                                bulletItems.lastIndex -> "છેલ્લે, "
                                else -> "પછી, "
                            }
                            result.add("$prefix$item")
                        }
                    }
                    else -> {
                        bulletItems.forEachIndexed { idx, item ->
                            val prefix = when (idx) {
                                0 -> "First, "
                                bulletItems.lastIndex -> "Finally, "
                                else -> "Next, "
                            }
                            result.add("$prefix$item")
                        }
                    }
                }
            }
            bulletItems.clear()
        }

        for (line in lines) {
            val trimmed = line.trim()
            val isBullet = trimmed.startsWith("•") ||
                    trimmed.startsWith("- ") ||
                    trimmed.startsWith("* ") ||
                    trimmed.matches(Regex("^\\d+[.)]\\s+.*"))

            if (isBullet) {
                val cleaned = trimmed.replace(Regex("^(•|[-*]|\\d+[.)])\\s*"), "").trim()
                if (cleaned.isNotBlank()) {
                    bulletItems.add(cleaned)
                }
            } else {
                flushBullets()
                if (trimmed.isNotBlank()) {
                    result.add(trimmed)
                }
            }
        }
        flushBullets()

        return result.joinToString(". ")
    }
}

