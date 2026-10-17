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
 * TextToSpeechManager providing offline audio guidance for classification and safety instructions.
 */
class TextToSpeechManager(private val context: Context) : TextToSpeech.OnInitListener, AutoCloseable {

    companion object {
        private const val TAG = "TextToSpeechManager"
        private const val UTTERANCE_ID = "wasteinfoai_audio"
    }

    private var tts: TextToSpeech? = null
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TextToSpeech engine", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback to English US
                tts?.setLanguage(Locale.US)
            }
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
            Log.i(TAG, "TextToSpeech engine ready")
        } else {
            Log.w(TAG, "TextToSpeech initialization failed with status $status")
            _isReady.value = false
        }
    }

    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!_isReady.value || tts == null) {
            Log.w(TAG, "TTS not ready; speech dropped")
            return
        }
        tts?.speak(text, queueMode, null, UTTERANCE_ID)
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
