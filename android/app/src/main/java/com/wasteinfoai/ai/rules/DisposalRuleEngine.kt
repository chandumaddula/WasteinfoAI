package com.wasteinfoai.ai.rules

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.InputStream

/**
 * Deterministic rule engine providing verified hierarchical disposal instructions offline.
 * Supports exact item-specific rules, material code lookups, and category-level fallbacks.
 */
class DisposalRuleEngine(
    private val context: Context? = null,
    private val rulesAssetPath: String = "rules/disposal_rules.json"
) {

    companion object {
        private const val TAG = "DisposalRuleEngine"
    }

    constructor(rulesJsonString: String) : this(null, "") {
        loadFromJson(rulesJsonString)
    }

    constructor(rulesList: List<DisposalRule>) : this(null, "") {
        loadRulesList(rulesList)
    }

    private var allRules: List<DisposalRule> = emptyList()
    private var rulesByCategory: Map<String, DisposalRule> = emptyMap()
    private var rulesBySpecificItem: Map<String, DisposalRule> = emptyMap()
    private var rulesByMaterialCode: Map<String, DisposalRule> = emptyMap()
    private var rulesVersion: String = "2.0.0"
    private var isLoaded = false

    init {
        if (context != null) {
            loadRules()
        }
    }

    @Synchronized
    fun loadRules() {
        val ctx = context ?: return
        if (isLoaded) return
        try {
            val jsonStream: InputStream = ctx.assets.open(rulesAssetPath)
            val jsonStr = jsonStream.bufferedReader().use { it.readText() }
            loadFromJson(jsonStr)
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Failed to load disposal rules from asset $rulesAssetPath", e) }
        }
    }

    @Synchronized
    fun loadFromJson(jsonStr: String) {
        try {
            val root = JSONObject(jsonStr)

            rulesVersion = root.optString("version", "2.0.0")
            val rulesArray = root.getJSONArray("rules")

            val rulesList = mutableListOf<DisposalRule>()

            for (i in 0 until rulesArray.length()) {
                val obj = rulesArray.getJSONObject(i)
                val accepted = mutableListOf<String>()
                val acceptedArr = obj.optJSONArray("accepted_pathways")
                if (acceptedArr != null) {
                    for (j in 0 until acceptedArr.length()) accepted.add(acceptedArr.getString(j))
                }

                val prohibited = mutableListOf<String>()
                val prohibitedArr = obj.optJSONArray("prohibited_pathways")
                if (prohibitedArr != null) {
                    for (j in 0 until prohibitedArr.length()) prohibited.add(prohibitedArr.getString(j))
                }

                val rule = DisposalRule(
                    id = obj.getString("id"),
                    category = obj.getString("category"),
                    specificItem = obj.optString("specific_item").takeIf { it.isNotBlank() },
                    materialCode = obj.optString("material_code").takeIf { it.isNotBlank() },
                    jurisdiction = obj.optString("jurisdiction", "INDIA_SWM_2016"),
                    disposalMethod = obj.getString("disposal_method"),
                    isRecyclable = obj.optBoolean("is_recyclable", false),
                    isHazardous = obj.optBoolean("is_hazardous", false),
                    preparationInstructions = obj.getString("preparation_instructions"),
                    safetyWarnings = obj.getString("safety_warnings"),
                    targetBinColor = obj.optString("target_bin_color", "BLACK"),
                    acceptedPathways = accepted,
                    prohibitedPathways = prohibited,
                    reviewStatus = obj.optString("review_status", "VERIFIED"),
                    source = obj.optString("source", "India Solid Waste Management Rules 2016")
                )

                rulesList.add(rule)
            }

            loadRulesList(rulesList)
            runCatching { Log.i(TAG, "Loaded ${rulesList.size} hierarchical disposal rules (v$rulesVersion)") }
        } catch (e: Exception) {
            runCatching { Log.e(TAG, "Failed to load disposal rules from asset $rulesAssetPath", e) }
        }
    }

    @Synchronized
    fun loadRulesList(rulesList: List<DisposalRule>) {
        val catMap = mutableMapOf<String, DisposalRule>()
        val itemMap = mutableMapOf<String, DisposalRule>()
        val matMap = mutableMapOf<String, DisposalRule>()

        for (rule in rulesList) {
            val catKey = rule.category.lowercase()
            if (rule.specificItem == null || !catMap.containsKey(catKey)) {
                catMap[catKey] = rule
                catMap[rule.category] = rule
            }
            rule.specificItem?.let { item -> itemMap[item.lowercase()] = rule }
            rule.materialCode?.let { code -> matMap[code.uppercase()] = rule }
        }

        allRules = rulesList
        rulesByCategory = catMap
        rulesBySpecificItem = itemMap
        rulesByMaterialCode = matMap
        isLoaded = true
    }

    /**
     * Backward-compatible category lookup.
     */
    fun getRuleForCategory(category: String): DisposalRule? {
        if (!isLoaded) loadRules()
        return rulesByCategory[category] ?: rulesByCategory[category.lowercase()]
    }

    /**
     * Hierarchical lookup adhering to precedence:
     * 1. Verified Item-specific rule
     * 2. Verified Material / RIC code rule
     * 3. Verified Generic category rule
     * 4. Conservative fallback
     */
    fun getHierarchicalRule(
        category: String,
        specificItem: String? = null,
        materialCode: String? = null
    ): DisposalRule {
        if (!isLoaded) loadRules()

        // 1. Try specific item match
        if (!specificItem.isNullOrBlank()) {
            val lowerItem = specificItem.lowercase().trim()
            val exactMatch = rulesBySpecificItem[lowerItem]
            if (exactMatch != null) return preserveSafety(exactMatch, category)

            // Substring search in specific items
            val partialMatch = allRules.firstOrNull { rule ->
                rule.specificItem != null && (
                    lowerItem.contains(rule.specificItem.lowercase()) ||
                    rule.specificItem.lowercase().contains(lowerItem)
                )
            }
            if (partialMatch != null) return preserveSafety(partialMatch, category)
        }

        // 2. Try material code match (e.g., "PET 01", "ALU 41")
        if (!materialCode.isNullOrBlank()) {
            val upperCode = materialCode.uppercase().trim()
            val codeMatch = rulesByMaterialCode[upperCode]
                ?: allRules.firstOrNull { it.materialCode?.uppercase() == upperCode }
            if (codeMatch != null) return preserveSafety(codeMatch, category)
        }

        // 3. Fall back to generic category rule
        val catRule = getRuleForCategory(category)
        if (catRule != null) return catRule

        // 4. Conservative unknown fallback
        return DisposalRule(
            id = "RULE-FALLBACK-UNKNOWN",
            category = category,
            jurisdiction = "INDIA_SWM_2016",
            disposalMethod = "Segregate into Domestic Non-Biodegradable Dry Waste or consult local civic ward",
            isRecyclable = false,
            isHazardous = false,
            preparationInstructions = "Keep item clean, dry, and contained. Do not mix with organic wet waste.",
            safetyWarnings = "Unidentified waste item. Do not incinerate, crush, or discard in open drains.",
            targetBinColor = "BLACK",
            source = "SWM 2016 Conservative Precautionary Principle"
        )
    }

    /**
     * Ensures safety warnings are never demoted if the coarse category or item is hazardous.
     */
    private fun preserveSafety(resolvedRule: DisposalRule, coarseCategory: String): DisposalRule {
        val isCoarseHazardous = SafetyGuard.isHazardous(coarseCategory)
        if (isCoarseHazardous && !resolvedRule.isHazardous) {
            return resolvedRule.copy(
                isHazardous = true,
                targetBinColor = "RED",
                safetyWarnings = "HAZARDOUS COMPONENT DETECTED: ${resolvedRule.safetyWarnings} Do not dispose in regular household blue/green bins."
            )
        }
        return resolvedRule
    }

    fun getAllRules(): List<DisposalRule> {
        if (!isLoaded) loadRules()
        return allRules
    }

    fun getRulesVersion(): String = rulesVersion
}
