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
    val isEnabled: Boolean = false,
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

/**
 * Optional cloud client for hybrid reasoning mode.
 * OFF by default behind [HybridConfig]. Strict 8-second timeout.
 * Sends text context only (never images or user PII).
 */
class CloudAssistantClient(
    private val context: Context? = null,
    var config: HybridConfig = HybridConfig(isEnabled = false),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) {

    companion object {
        private const val TAG = "CloudAssistantClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    fun isEligible(): Boolean {
        if (!config.isEnabled) return false
        if (config.backendUrl.isBlank()) return false
        return isOnline()
    }

    private fun isOnline(): Boolean {
        val ctx = context ?: return true // If no context provided, assume network available or let OkHttp handle
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
                Log.w(TAG, "Cloud LLM returned HTTP ${response.code}")
                return@withContext null
            }

            val resBody = response.body?.string() ?: return@withContext null
            val chatRes = gson.fromJson(resBody, CloudChatResponse::class.java)
            chatRes?.reply?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Cloud assistant query failed or timed out: ${e.message}")
            null
        }
    }
}

