package com.wasteinfoai.ai.llm

import com.wasteinfoai.ai.rules.DisposalRule
import com.wasteinfoai.ai.rules.DisposalRuleEngine
import com.wasteinfoai.ai.rules.SafetyGuard
import com.wasteinfoai.features.assistant.ChatMessage
import kotlinx.coroutines.withTimeoutOrNull

data class AssistantResponse(
    val text: String,
    val isLlmGenerated: Boolean,
    val isHazardous: Boolean,
    val groundedCategory: String?,
    val targetBinColor: String?
)

/**
 * Core assistant coordinator for "Bottle".
 * Coordinates Cloud LLM (optional hybrid), on-device Llama 3 via ExecuTorch,
 * and the deterministic rule-grounded OfflineResponder.
 * Strict invariant: Hazardous items ALWAYS receive prominent safety advisories on all paths.
 */
class AssistantService(
    private val llamaEngine: LlamaEngine,
    private val ruleEngine: DisposalRuleEngine,
    private val cloudClient: CloudAssistantClient = CloudAssistantClient(),
    private val offlineResponder: OfflineResponder = OfflineResponder()
) {

    companion object {
        private const val LLM_TIMEOUT_MS = 30_000L
        private const val MIN_LLM_CHARS = 15
    }

    data class MentionedItem(val category: String, val specificItem: String?)

    private fun detectMentionedItem(message: String): MentionedItem? {
        val q = message.lowercase()
        return when {
            // Battery & Cells
            q.contains(Regex("\\b(battery|batteries|cell|cells)\\b")) ||
                    q.contains("బ్యాటరీ") || q.contains("బैटरी") || q.contains("பேட்டரி") || q.contains("बॅटरी") ->
                MentionedItem("hazardous", "Battery")

            // E-waste & Electronics
            q.contains(Regex("\\b(laptop|computer|phone|mobile|mouse|keyboard|charger|cable|wire|electronic|e-waste|ewaste)\\b")) ||
                    q.contains("మౌస్") || q.contains("ల్యాప్‌టాప్") || q.contains("ఫోన్") || q.contains("ఈ-వ్యర్థ") ||
                    q.contains("फोन") || q.contains("कंप्यूटर") || q.contains("ई-कचरा") || q.contains("மின்னணு") ->
                MentionedItem("E-waste", "Electronic Device")

            // Organic / Biodegradable
            q.contains(Regex("\\b(apple|banana|fruit|vegetable|peel|peels|food|compost|leaf|leaves|organic|biodegradable)\\b")) ||
                    q.contains("పండు") || q.contains("కూరగాయ") || q.contains("తొక్క") || q.contains("సేంద్రీయ") ||
                    q.contains("फल") || q.contains("सब्जी") || q.contains("छिलका") || q.contains("खाद") ||
                    q.contains("உணவு") || q.contains("காய்கறி") ->
                MentionedItem("biodegradable", "Organic Waste")

            // Paper & Cardboard
            q.contains(Regex("\\b(paper|cardboard|carton|box|newspaper|book|magazine)\\b")) ||
                    q.contains("కాగితం") || q.contains("కార్డ్‌బోర్డ్") || q.contains("పుస్తకం") ||
                    q.contains("कागज") || q.contains("गत्ता") || q.contains("अखबार") || q.contains("காகிதம்") ->
                MentionedItem("recyclable", "Cardboard Box")

            // Glass
            q.contains(Regex("\\b(glass|jar|mirror)\\b")) ||
                    q.contains("గాజు") || q.contains("कांच") || q.contains("கண்ணாடி") ->
                MentionedItem("recyclable", "Glass Beverage Bottle")

            // Plastic
            q.contains(Regex("\\b(plastic|polythene|polybag|wrapper|pet bottle)\\b")) ||
                    q.contains("ప్లాస్టిక్") || q.contains("प्लास्टिक") || q.contains("पॉलिथीन") || q.contains("பிளாஸ்டிக்") ->
                MentionedItem("recyclable", "PET Beverage Bottle")

            // Chemical & Hazardous
            q.contains(Regex("\\b(chemical|paint|pesticide|poison|medicine|syringe|bulb|tube light)\\b")) ||
                    q.contains("రసాయనం") || q.contains("మందులు") || q.contains("బల్బ్") ||
                    q.contains("रसायन") || q.contains("दवा") || q.contains("पेंट") ->
                MentionedItem("hazardous", "Hazardous Chemical")

            else -> null
        }
    }

    /**
     * Comprehensive multi-turn reply function grounded in rich [AssistantContext].
     */
    suspend fun reply(
        context: AssistantContext,
        message: String,
        history: List<ChatMessage> = emptyList(),
        onToken: (String) -> Unit = {}
    ): AssistantResponse {
        val lang = context.languageCode ?: "en"
        ruleEngine.loadRulesForLanguage(lang)

        val detected = detectMentionedItem(message)
        val effectiveCategory = detected?.category ?: context.category
        val effectiveItem = detected?.specificItem ?: context.specificItem
        val effectiveContext = if (detected != null) {
            context.copy(category = effectiveCategory, specificItem = effectiveItem)
        } else {
            context
        }

        val rule: DisposalRule? = if (!effectiveItem.isNullOrBlank() || !effectiveContext.materialCode.isNullOrBlank()) {
            ruleEngine.getHierarchicalRule(effectiveCategory, effectiveItem, effectiveContext.materialCode, effectiveContext.jurisdiction, lang)
        } else {
            ruleEngine.getRuleForCategory(effectiveCategory, lang)
        }

        val safety = SafetyGuard.evaluateSafety(effectiveCategory, isUncertain = false)
        val isHazardous = safety.isHazardous || rule?.isHazardous == true
        val warning = rule?.safetyWarnings?.takeIf { it.isNotBlank() } ?: safety.primaryWarning

        var candidateText: String? = null
        var isLlm = false

        // 1. Cloud Assistant (if enabled & online)
        if (cloudClient.isEligible()) {
            val cloudReply = cloudClient.generateReply(effectiveContext, rule, message, history)
            if (isValidLlmText(cloudReply)) {
                candidateText = sanitizeLlmText(cloudReply!!)
                isLlm = true
                onToken(candidateText)
            }
        }

        // 2. On-Device Llama Engine (ExecuTorch)
        if (candidateText == null && llamaEngine.isModelAvailable) {
            val llamaReply = generateWithLlama(effectiveContext, rule, message, history, onToken)
            if (isValidLlmText(llamaReply)) {
                candidateText = sanitizeLlmText(llamaReply!!)
                isLlm = true
            }
        }

        // 3. Deterministic Offline Responder
        if (candidateText == null) {
            candidateText = offlineResponder.respond(effectiveContext, rule, message, history)
            isLlm = false
            onToken(candidateText)
        }

        // 4. Strict Safety Enforcement: Hazardous items must always feature the safety advisory
        val finalResponseText = if (isHazardous && !candidateText.contains("SAFETY", ignoreCase = true)) {
            "⚠️ SAFETY ADVISORY (Hazardous Waste):\n$warning\n\n$candidateText"
        } else {
            candidateText
        }

        return AssistantResponse(
            text = finalResponseText,
            isLlmGenerated = isLlm,
            isHazardous = isHazardous,
            groundedCategory = rule?.category ?: effectiveCategory,
            targetBinColor = rule?.targetBinColor
        )
    }

    /**
     * Backward-compatible entry point (category only).
     */
    suspend fun getGuidance(
        category: String,
        userQuery: String? = null,
        onPartialToken: (String) -> Unit = {}
    ): AssistantResponse = reply(
        context = AssistantContext(category = category),
        message = userQuery ?: "How should I dispose of $category?",
        history = emptyList(),
        onToken = onPartialToken
    )

    /**
     * Backward-compatible entry point with full item specifics.
     */
    suspend fun getGuidanceFor(
        category: String,
        specificItem: String?,
        materialCode: String?,
        jurisdiction: String?,
        userQuery: String?,
        onPartialToken: (String) -> Unit = {}
    ): AssistantResponse = reply(
        context = AssistantContext(
            category = category,
            specificItem = specificItem,
            materialCode = materialCode,
            jurisdiction = jurisdiction ?: "INDIA_SWM_2016"
        ),
        message = userQuery ?: "How should I dispose of ${specificItem ?: category}?",
        history = emptyList(),
        onToken = onPartialToken
    )

    private suspend fun generateWithLlama(
        context: AssistantContext,
        rule: DisposalRule?,
        query: String,
        history: List<ChatMessage>,
        onToken: (String) -> Unit
    ): String? {
        if (!llamaEngine.isReady && llamaEngine.initialize().isFailure) return null

        val prompt = PromptBuilder.buildMultiTurnPrompt(
            context = context,
            rule = rule,
            history = history,
            currentMessage = query
        )

        val result = withTimeoutOrNull(LLM_TIMEOUT_MS) {
            llamaEngine.generate(prompt, 256, onToken)
        }
        if (result == null) {
            llamaEngine.stop()
            return null
        }
        return result.getOrNull()?.trim()
    }

    fun stop() {
        llamaEngine.stop()
    }

    private fun isValidLlmText(text: String?): Boolean {
        if (text == null) return false
        val trimmed = text.trim()
        return trimmed.length >= MIN_LLM_CHARS && !trimmed.startsWith("<|")
    }

    private fun sanitizeLlmText(text: String): String {
        return text
            .replace("<|begin_of_text|>", "")
            .replace("<|end_of_text|>", "")
            .replace("<|eot_id|>", "")
            .replace("<|start_header_id|>", "")
            .replace("<|end_header_id|>", "")
            .replace("system\n\n", "")
            .replace("assistant\n\n", "")
            .trim()
    }
}
