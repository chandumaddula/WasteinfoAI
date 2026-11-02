package com.wasteinfoai.core.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests verification of LanguageManager supported languages and fallbacks.
 */
class LanguageManagerTest {

    @Test
    fun testFiveSupportedLanguagesConfigured() {
        val langs = LanguageManager.SUPPORTED_LANGUAGES
        assertEquals("Must support exactly 5 languages", 5, langs.size)

        val codes = langs.map { it.code }
        assertTrue("Must include English (en)", codes.contains("en"))
        assertTrue("Must include Hindi (hi)", codes.contains("hi"))
        assertTrue("Must include Telugu (te)", codes.contains("te"))
        assertTrue("Must include Tamil (ta)", codes.contains("ta"))
        assertTrue("Must include Marathi (mr)", codes.contains("mr"))

        assertEquals("First/default language must be English", "en", langs[0].code)
    }

    @Test
    fun testNativeNamesAndDisplayStrings() {
        val hi = LanguageManager.findSupportedLanguage("hi")
        assertEquals("हिन्दी", hi.nativeName)
        assertEquals("Hindi", hi.englishName)
        assertTrue(hi.displayName.contains("हिन्दी"))

        val te = LanguageManager.findSupportedLanguage("te")
        assertEquals("తెలుగు", te.nativeName)
        assertEquals("Telugu", te.englishName)
        assertTrue(te.displayName.contains("తెలుగు"))

        val ta = LanguageManager.findSupportedLanguage("ta")
        assertEquals("தமிழ்", ta.nativeName)
        assertEquals("Tamil", ta.englishName)
        assertTrue(ta.displayName.contains("தமிழ்"))

        val mr = LanguageManager.findSupportedLanguage("mr")
        assertEquals("मराठी", mr.nativeName)
        assertEquals("Marathi", mr.englishName)
        assertTrue(mr.displayName.contains("मराठी"))
    }

    @Test
    fun testFallbackToEnglishForUnknownOrNull() {
        val nullLang = LanguageManager.findSupportedLanguage(null)
        assertEquals("en", nullLang.code)

        val unknownLang = LanguageManager.findSupportedLanguage("es")
        assertEquals("en", unknownLang.code)

        val emptyLang = LanguageManager.findSupportedLanguage("")
        assertEquals("en", emptyLang.code)
    }
}

