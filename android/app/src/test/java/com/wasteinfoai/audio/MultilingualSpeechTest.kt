package com.wasteinfoai.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Locale

/**
 * Tests verification of STT and TTS locale mappings across all 5 supported languages.
 */
class MultilingualSpeechTest {

    @Test
    fun testSttLocaleMapping() {
        assertEquals("en-IN", SpeechInputHelper.getRecognitionLocaleTag("en"))
        assertEquals("hi-IN", SpeechInputHelper.getRecognitionLocaleTag("hi"))
        assertEquals("te-IN", SpeechInputHelper.getRecognitionLocaleTag("te"))
        assertEquals("ta-IN", SpeechInputHelper.getRecognitionLocaleTag("ta"))
        assertEquals("mr-IN", SpeechInputHelper.getRecognitionLocaleTag("mr"))

        // Fallbacks for null or unknown locales
        assertEquals("en-IN", SpeechInputHelper.getRecognitionLocaleTag(null))
        assertEquals("en-IN", SpeechInputHelper.getRecognitionLocaleTag("fr"))
        assertEquals("en-IN", SpeechInputHelper.getRecognitionLocaleTag(""))
    }

    @Test
    fun testTtsLocaleMapping() {
        val enLocale = TextToSpeechManager.getTtsLocale("en")
        assertEquals("en", enLocale.language)
        assertEquals("IN", enLocale.country)

        val hiLocale = TextToSpeechManager.getTtsLocale("hi")
        assertEquals("hi", hiLocale.language)
        assertEquals("IN", hiLocale.country)

        val teLocale = TextToSpeechManager.getTtsLocale("te")
        assertEquals("te", teLocale.language)
        assertEquals("IN", teLocale.country)

        val taLocale = TextToSpeechManager.getTtsLocale("ta")
        assertEquals("ta", taLocale.language)
        assertEquals("IN", taLocale.country)

        val mrLocale = TextToSpeechManager.getTtsLocale("mr")
        assertEquals("mr", mrLocale.language)
        assertEquals("IN", mrLocale.country)

        // Fallbacks
        val defaultLocale = TextToSpeechManager.getTtsLocale(null)
        assertEquals("en", defaultLocale.language)
        assertEquals("IN", defaultLocale.country)

        val unknownLocale = TextToSpeechManager.getTtsLocale("de")
        assertEquals("en", unknownLocale.language)
        assertEquals("IN", unknownLocale.country)
    }

    @Test
    fun testIsoLanguageCodesAreValid() {
        val languages = listOf("en", "hi", "te", "ta", "mr")
        for (lang in languages) {
            val locale = Locale(lang, "IN")
            assertNotNull(locale.isO3Language)
            assertNotNull(locale.isO3Country)
        }
    }
}

