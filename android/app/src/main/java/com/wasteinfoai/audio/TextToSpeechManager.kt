package com.wasteinfoai.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * TextToSpeechManager providing multilingual offline audio guidance for classification and safety instructions.
 * Supports:
 * - English: Locale("en", "IN")
 * - Hindi: Locale("hi", "IN")
 * - Telugu: Locale("te", "IN")
 * - Tamil: Locale("ta", "IN")
 * - Marathi: Locale("mr", "IN")
 * - Kannada: Locale("kn", "IN")
 * - Malayalam: Locale("ml", "IN")
 * - Bengali: Locale("bn", "IN")
 * - Gujarati: Locale("gu", "IN")
 */
class TextToSpeechManager(private val context: Context) : TextToSpeech.OnInitListener, AutoCloseable {

    companion object {
        private const val TAG = "TextToSpeechManager"
        private const val UTTERANCE_ID = "wasteinfoai_audio"

        fun getTtsLocale(languageCode: String?): Locale {
            return when (languageCode?.trim()?.lowercase()?.take(2)) {
                "hi" -> Locale("hi", "IN")
                "te" -> Locale("te", "IN")
                "ta" -> Locale("ta", "IN")
                "mr" -> Locale("mr", "IN")
                "kn" -> Locale("kn", "IN")
                "ml" -> Locale("ml", "IN")
                "bn" -> Locale("bn", "IN")
                "gu" -> Locale("gu", "IN")
                else -> Locale("en", "IN")
            }
        }
    }

    private var tts: TextToSpeech? = null
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    @Volatile private var currentLanguageCode: String = "en"

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TextToSpeech engine", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            setLanguage(currentLanguageCode)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _isSpeaking.value = false
                    Log.w(TAG, "TTS Utterance error code: $errorCode")
                }
            })
            _isReady.value = true
            Log.i(TAG, "TextToSpeech engine ready for language: $currentLanguageCode")
        } else {
            Log.w(TAG, "TextToSpeech initialization failed with status $status")
            _isReady.value = false
        }
    }

    /**
     * Sets the active language for TTS speech output.
     * Returns true if the language is available, false if fallback was required.
     */
    fun setLanguage(languageCode: String?): Boolean {
        currentLanguageCode = languageCode?.trim()?.lowercase()?.take(2) ?: "en"
        val targetLocale = getTtsLocale(currentLanguageCode)
        val engine = tts ?: return false

        try {
            val availability = engine.isLanguageAvailable(targetLocale)
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = targetLocale
                Log.i(TAG, "TTS language set to $targetLocale")
                return true
            } else {
                Log.w(TAG, "TTS voice data unavailable for $targetLocale (code $availability); falling back to English (IN)")
                val fallbackLocale = Locale("en", "IN")
                val fallbackResult = engine.setLanguage(fallbackLocale)
                if (fallbackResult == TextToSpeech.LANG_MISSING_DATA || fallbackResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.US)
                }
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting TTS language to $targetLocale", e)
            return false
        }
    }

    /**
     * Checks if voice data is installed and available for the given language.
     */
    fun isLanguageAvailable(languageCode: String?): Boolean {
        val targetLocale = getTtsLocale(languageCode)
        val engine = tts ?: return false
        return try {
            engine.isLanguageAvailable(targetLocale) >= TextToSpeech.LANG_AVAILABLE
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Speaks the given text in the requested (or currently configured) language.
     */
    fun speak(
        text: String,
        languageCode: String? = null,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH
    ) {
        if (!_isReady.value || tts == null) {
            Log.w(TAG, "TTS not ready; speech dropped")
            return
        }

        if (languageCode != null && languageCode != currentLanguageCode) {
            setLanguage(languageCode)
        }

        try {
            tts?.speak(text, queueMode, null, UTTERANCE_ID)
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak failed", e)
        }
    }

    fun stop() {
        try {
            tts?.stop()
            _isSpeaking.value = false
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS", e)
        }
    }

    override fun close() {
        try {
            stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down TTS", e)
        } finally {
            tts = null
            _isReady.value = false
            _isSpeaking.value = false
        }
    }
}
