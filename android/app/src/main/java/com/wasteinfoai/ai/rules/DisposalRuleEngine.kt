package com.wasteinfoai.ai.rules

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.InputStream

/**
 * Deterministic rule engine providing verified disposal instructions offline.
 */
class DisposalRuleEngine(
    private val context: Context,
    private val rulesAssetPath: String = "rules/disposal_rules.json"
) {

    companion object {
        private const val TAG = "DisposalRuleEngine"
    }

    private var rulesByCategory: Map<String, DisposalRule> = emptyMap()
    private var rulesVersion: String = "1.0.0"
    private var isLoaded = false

    init {
        loadRules()
    }

    @Synchronized
    fun loadRules() {
        if (isLoaded) return
        try {
            val jsonStream: InputStream = context.assets.open(rulesAssetPath)
            val jsonStr = jsonStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)

            rulesVersion = root.optString("version", "1.0.0")
            val rulesArray = root.getJSONArray("rules")

            val map = mutableMapOf<String, DisposalRule>()
            for (i in 0 until rulesArray.length()) {
                val obj = rulesArray.getJSONObject(i)
                val rule = DisposalRule(
                    id = obj.getString("id"),
                    category = obj.getString("category"),
                    jurisdiction = obj.optString("jurisdiction", "GLOBAL_STANDARD"),
                    disposalMethod = obj.getString("disposal_method"),
                    isRecyclable = obj.optBoolean("is_recyclable", false),
                    isHazardous = obj.optBoolean("is_hazardous", false),
                    preparationInstructions = obj.getString("preparation_instructions"),
                    safetyWarnings = obj.getString("safety_warnings"),
                    targetBinColor = obj.optString("target_bin_color", "BLACK"),
                    reviewStatus = obj.optString("review_status", "VERIFIED"),
                    source = obj.optString("source", "Standard Guidelines")
                )
                map[rule.category] = rule
                // Also index by lowercase for resilient lookup
                map[rule.category.lowercase()] = rule
            }

            rulesByCategory = map
            isLoaded = true
            Log.i(TAG, "Loaded ${rulesArray.length()} disposal rules (v$rulesVersion)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load disposal rules from asset $rulesAssetPath", e)
        }
    }

    fun getRuleForCategory(category: String): DisposalRule? {
        if (!isLoaded) loadRules()
        return rulesByCategory[category] ?: rulesByCategory[category.lowercase()]
    }

    fun getAllRules(): List<DisposalRule> {
        if (!isLoaded) loadRules()
        // Deduplicate distinct rule IDs
        return rulesByCategory.values.distinctBy { it.id }
    }

    fun getRulesVersion(): String = rulesVersion
}
