package com.wasteinfoai.ai.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.wasteinfoai.BuildConfig
import com.wasteinfoai.ai.rules.DisposalRule
import com.wasteinfoai.features.assistant.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class HybridConfig(
    val isEnabled: Boolean = true,
    val backendUrl: String = BuildConfig.CLOUD_ASSISTANT_URL
)

data class CloudChatRequest(
    @SerializedName("item") val item: String,
    @SerializedName("category") val category: String,
    @SerializedName("rule_summary") val ruleSummary: String,
    @SerializedName("history") val history: List<CloudChatMessagePayload>,
    @SerializedName("query") val query: String
)

data class CloudChatMessagePayload(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class CloudChatResponse(
    @SerializedName("reply") val reply: String?
)

// Gemini API REST payload models
data class GeminiRequest(
    @SerializedName("system_instruction") val systemInstruction: GeminiContent? = null,
    @SerializedName("contents") val contents: List<GeminiContent>,
    @SerializedName("generationConfig") val generationConfig: GeminiGenerationConfig? = null
)

data class GeminiContent(
    @SerializedName("role") val role: String? = null,
    @SerializedName("parts") val parts: List<GeminiPart>
)

data class GeminiPart(
    @SerializedName("text") val text: String
)

data class GeminiGenerationConfig(
    @SerializedName("temperature") val temperature: Float = 0.7f,
    @SerializedName("topK") val topK: Int = 40,
    @SerializedName("topP") val topP: Float = 0.95f,
    @SerializedName("maxOutputTokens") val maxOutputTokens: Int = 800
)

data class GeminiResponse(
    @SerializedName("candidates") val candidates: List<GeminiCandidate>? = null,
    @SerializedName("error") val error: GeminiError? = null
)

data class GeminiCandidate(
    @SerializedName("content") val content: GeminiContent? = null,
    @SerializedName("finishReason") val finishReason: String? = null
)

data class GeminiError(
    @SerializedName("code") val code: Int? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("status") val status: String? = null
)

/**
 * Intelligent Cloud Assistant client providing humanized generative reasoning.
 * Prioritizes Google Gemini 1.5 Flash for conversational, nuanced answers.
 * Falls back to custom cloud backend or deterministic offline responder seamlessly.
 */
class CloudAssistantClient(
    private val context: Context? = null,
    var config: HybridConfig = HybridConfig(isEnabled = true),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) {

    companion object {
        private const val TAG = "CloudAssistantClient"
        private const val PREFS_NAME = "bottle_ai_prefs"
        private const val PREF_GEMINI_KEY = "gemini_api_key"
        private const val GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    @Volatile var lastModelUsed: String? = null

    /** Retrieves the active Gemini API key from user prefs or BuildConfig. */
    fun getApiKey(): String {
        val userSavedKey = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(PREF_GEMINI_KEY, null)?.trim()
        if (!userSavedKey.isNullOrBlank()) return userSavedKey

        return BuildConfig.GEMINI_API_KEY.trim()
    }

    /** Stores user-provided Gemini API key in SharedPreferences. */
    fun saveApiKey(key: String) {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(PREF_GEMINI_KEY, key.trim())
            ?.apply()
    }

    /** Clears the user-provided Gemini API key. */
    fun clearApiKey() {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.remove(PREF_GEMINI_KEY)
            ?.apply()
    }

    fun isGeminiConfigured(): Boolean = getApiKey().isNotBlank()

    fun isEligible(): Boolean {
        if (!isOnline()) return false
        return isGeminiConfigured() || (config.isEnabled && config.backendUrl.isNotBlank())
    }

    private fun isOnline(): Boolean {
        val ctx = context ?: return true
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun generateReply(
        context: AssistantContext,
        rule: DisposalRule?,
        query: String,
        history: List<ChatMessage>
    ): String? = withContext(Dispatchers.IO) {
        if (!isEligible()) return@withContext null

        val geminiKey = getApiKey()
        if (geminiKey.isNotBlank()) {
            val geminiResult = queryGemini(geminiKey, context, rule, query, history)
            if (!geminiResult.isNullOrBlank()) {
                lastModelUsed = "Gemini 1.5 Flash"
                return@withContext geminiResult
            }
        }

        if (config.isEnabled && config.backendUrl.isNotBlank()) {
            val backendResult = queryCustomBackend(context, rule, query, history)
            if (!backendResult.isNullOrBlank()) {
                lastModelUsed = "Cloud AI"
                return@withContext backendResult
            }
        }

        null
    }

    private fun queryGemini(
        apiKey: String,
        context: AssistantContext,
        rule: DisposalRule?,
        query: String,
        history: List<ChatMessage>
    ): String? {
        try {
            val lang = context.languageCode ?: "en"
            val systemInstruction = buildGeminiSystemInstruction(context, rule, lang)

            val contents = mutableListOf<GeminiContent>()

            // Include last 6 conversational turns for context
            val historyTurns = history.takeLast(6)
            for (msg in historyTurns) {
                if (msg.text.isNotBlank()) {
                    val role = if (msg.isUser) "user" else "model"
                    contents.add(GeminiContent(role = role, parts = listOf(GeminiPart(msg.text))))
                }
            }

            // Current user query
            contents.add(GeminiContent(role = "user", parts = listOf(GeminiPart(query))))

            val geminiReq = GeminiRequest(
                systemInstruction = GeminiContent(parts = listOf(GeminiPart(systemInstruction))),
                contents = contents,
                generationConfig = GeminiGenerationConfig(
                    temperature = 0.7f,
                    topK = 40,
                    topP = 0.95f,
                    maxOutputTokens = 800
                )
            )

            val url = "$GEMINI_ENDPOINT?key=$apiKey"
            val body = gson.toJson(geminiReq).toRequestBody(JSON_MEDIA)
            val request = Request.Builder().url(url).post(body).build()

            val response = client.newCall(request).execute()
            val resBody = response.body?.string()

            if (!response.isSuccessful) {
                Log.w(TAG, "Gemini API HTTP ${response.code}: $resBody")
                return null
            }

            if (resBody.isNullOrBlank()) return null
            val geminiRes = gson.fromJson(resBody, GeminiResponse::class.java)

            val candidateText = geminiRes.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            return candidateText?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Gemini query failed or timed out: ${e.message}")
            return null
        }
    }

    private fun buildGeminiSystemInstruction(
        context: AssistantContext,
        rule: DisposalRule?,
        lang: String
    ): String {
        val langName = when (lang) {
            "hi" -> "Hindi (हिंदी)"
            "te" -> "Telugu (తెలుగు)"
            "ta" -> "Tamil (தமிழ்)"
            "mr" -> "Marathi (मराठी)"
            "kn" -> "Kannada (ಕನ್ನಡ)"
            "ml" -> "Malayalam (മലയാളം)"
            "bn" -> "Bengali (বাংলা)"
            "gu" -> "Gujarati (ગુજરાતી)"
            else -> "English"
        }

        val itemName = context.specificItem ?: context.category
        val ruleDetails = if (rule != null) {
            """
            Current Scanned Item: $itemName
            Target Bin: ${rule.targetBinColor}
            Disposal Method: ${rule.disposalMethod}
            Is Recyclable: ${rule.isRecyclable}
            Is Hazardous: ${rule.isHazardous}
            Safety Warnings: ${rule.safetyWarnings}
            Preparation Instructions: ${rule.preparationInstructions}
            Condition Notes: ${rule.conditionNotes.entries.joinToString("; ") { "${it.key}: ${it.value}" }}
            Environmental Impact: ${rule.environmentalImpact}
            Reuse / Upcycle Ideas: ${rule.reuseIdeas.joinToString("; ")}
            """.trimIndent()
        } else {
            "Current Item Category: $itemName (General classification)"
        }

        return """
        You are "Bottle AI", a friendly, knowledgeable, empathetic, and human-like sanitation & zero-waste companion in India.
        
        Personality & Tone:
        - Talk naturally like a real, enthusiastic human friend who cares about making sustainable living practical.
        - NEVER repeat robotic canned templates or recite the entire manual unprompted.
        - Tailor your reply directly and specifically to what the user actually asks (e.g. creative crafts, broken glass, pet/child safety, composting, local kabadiwala/scrap selling, or general questions).
        - Keep answers crisp, warm, and easy to read (1-3 short paragraphs or bullet points).
        - Ground your guidance in verified Indian Solid Waste Management Rules 2016 (Green = Wet/Organic, Blue = Dry Recyclables, Orange = E-Waste, Red = Hazardous/Sanitary, Black = Inert Non-Recyclable).
        - MANDATORY SAFETY INVARIANT: If the item or topic is hazardous (batteries, chemicals, broken tube lights/mercury, sanitary/medical waste), clearly advise NEVER to burn, crush, or dump into open drains.
        - LANGUAGE REQUIREMENT: You MUST respond fluently and naturally in $langName using conversational, natural phrasing.
        
        Active Item Context:
        $ruleDetails
        """.trimIndent()
    }

    private fun queryCustomBackend(
        context: AssistantContext,
        rule: DisposalRule?,
        query: String,
        history: List<ChatMessage>
    ): String? {
        try {
            val itemName = context.specificItem ?: context.category
            val ruleSummary = if (rule != null) {
                "${rule.category}: ${rule.disposalMethod}, Bin: ${rule.targetBinColor}. Prep: ${rule.preparationInstructions}"
            } else {
                "No rule registered for ${context.category}"
            }

            val payloadHistory = history.takeLast(4).map {
                CloudChatMessagePayload(
                    role = if (it.isUser) "user" else "assistant",
                    content = it.text
                )
            }

            val chatReq = CloudChatRequest(
                item = itemName,
                category = context.category,
                ruleSummary = ruleSummary,
                history = payloadHistory,
                query = query
            )

            val url = config.backendUrl.trimEnd('/') + "/api/v1/assistant/chat"
            val body = gson.toJson(chatReq).toRequestBody(JSON_MEDIA)
            val request = Request.Builder().url(url).post(body).build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Custom Cloud LLM returned HTTP ${response.code}")
                return null
            }

            val resBody = response.body?.string() ?: return null
            val chatRes = gson.fromJson(resBody, CloudChatResponse::class.java)
            return chatRes?.reply?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Custom cloud assistant query failed: ${e.message}")
            return null
        }
    }
}
