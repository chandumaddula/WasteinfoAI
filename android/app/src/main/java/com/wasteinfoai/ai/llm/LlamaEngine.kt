package com.wasteinfoai.ai.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File

/**
 * On-device Llama LLM engine using ExecuTorch Android runtime.
 * Explicitly reports when model weights or tokenizer are unavailable.
 */
class LlamaEngine(
    private val context: Context,
    private val modelFileName: String = "llama.pte",
    private val tokenizerFileName: String = "tokenizer.bin"
) : AutoCloseable {

    companion object {
        private const val TAG = "LlamaEngine"
    }

    private var llmModule: LlmModule? = null
    private var isInitialized = false

    val isModelAvailable: Boolean
        get() {
            val modelFile = File(context.filesDir, "llm/$modelFileName")
            val tokenizerFile = File(context.filesDir, "llm/$tokenizerFileName")
            return modelFile.exists() && tokenizerFile.exists()
        }

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        val modelFile = File(context.filesDir, "llm/$modelFileName")
        val tokenizerFile = File(context.filesDir, "llm/$tokenizerFileName")

        if (!modelFile.exists() || !tokenizerFile.exists()) {
            val msg = "Local Llama weights or tokenizer not found at ${modelFile.path}. Assistant will operate in deterministic rule-grounded mode."
            Log.i(TAG, msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        try {
            Log.i(TAG, "Initializing ExecuTorch LlmModule...")
            val module = LlmModule(modelFile.absolutePath, tokenizerFile.absolutePath, 0.7f)
            val loadCode = module.load()
            if (loadCode != 0) {
                return@withContext Result.failure(IllegalStateException("Failed to load LlmModule, error code: $loadCode"))
            }
            llmModule = module
            isInitialized = true
            Log.i(TAG, "ExecuTorch LlmModule loaded successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate ExecuTorch LlmModule", e)
            Result.failure(e)
        }
    }

    suspend fun generate(
        prompt: String,
        maxTokens: Int = 256,
        onToken: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.Default) {
        val module = llmModule ?: return@withContext Result.failure(
            IllegalStateException("Llama engine is not initialized or model artifact is missing")
        )

        val outputBuilder = StringBuilder()
        try {
            module.generate(prompt, maxTokens, object : LlmCallback {
                override fun onResult(token: String?) {
                    if (token != null) {
                        outputBuilder.append(token)
                        onToken(token)
                    }
                }

                override fun onStats(tokensPerSecond: Float) {
                    Log.d(TAG, "Llama generation speed: $tokensPerSecond tokens/sec")
                }
            })
            Result.success(outputBuilder.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Llama generation failed", e)
            Result.failure(e)
        }
    }

    fun stop() {
        try {
            llmModule?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping LLM generation", e)
        }
    }

    override fun close() {
        try {
            stop()
            llmModule?.resetNative()
        } catch (e: Exception) {
            Log.w(TAG, "Error resetting LlmModule", e)
        } finally {
            llmModule = null
            isInitialized = false
        }
    }
}
