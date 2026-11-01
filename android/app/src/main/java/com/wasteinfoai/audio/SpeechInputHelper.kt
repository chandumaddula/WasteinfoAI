package com.wasteinfoai.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Handles offline voice-to-text recognition using Android's native SpeechRecognizer.
 * Configured with [RecognizerIntent.EXTRA_PREFER_OFFLINE] for offline-first operation.
 */
class SpeechInputHelper(private val context: Context) {

    companion object {
        private const val TAG = "SpeechInputHelper"
    }

    private var speechRecognizer: SpeechRecognizer? = null

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onListeningStateChanged: (Boolean) -> Unit
    ) {
        if (!isAvailable) {
            onError("Speech recognition is not available on this device.")
            return
        }

        stopListening()

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        onListeningStateChanged(true)
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        onListeningStateChanged(false)
                    }

                    override fun onError(error: Int) {
                        onListeningStateChanged(false)
                        val message = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                            SpeechRecognizer.ERROR_CLIENT -> "Client error in speech recognition."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Audio permission required."
                            SpeechRecognizer.ERROR_NETWORK -> "Network required for online speech recognition."
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech timeout."
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Please try speaking again."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy."
                            SpeechRecognizer.ERROR_SERVER -> "Recognition server error."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard."
                            else -> "Voice input error ($error)."
                        }
                        Log.w(TAG, "SpeechRecognizer error: $message (code $error)")
                        onError(message)
                    }

                    override fun onResults(results: Bundle?) {
                        onListeningStateChanged(false)
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim()
                        if (!text.isNullOrBlank()) {
                            onResult(text)
                        } else {
                            onError("No speech recognized.")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SpeechRecognizer", e)
            onListeningStateChanged(false)
            onError("Failed to start speech recognizer: ${e.message}")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping SpeechRecognizer", e)
        } finally {
            speechRecognizer = null
        }
    }
}

