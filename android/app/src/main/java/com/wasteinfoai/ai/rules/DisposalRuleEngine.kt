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
        private const val MIN_PARTIAL_MATCH_LEN = 6
    }

    constructor(rulesJsonString: String) : this(null, "") {
        loadFromJson(rulesJsonString)
    }

    constructor(rulesList: List<DisposalRule>) : this(null, "") {
        loadRulesList(rulesList)
    }

    @Volatile private var allRules: List<DisposalRule> = emptyList()
    @Volatile private var rulesByCategory: Map<String, DisposalRule> = emptyMap()
    @Volatile private var rulesBySpecificItem: Map<String, DisposalRule> = emptyMap()
    @Volatile private var rulesByMaterialCode: Map<String, DisposalRule> = emptyMap()
    @Volatile private var rulesVersion: String = "2.0.0"
    @Volatile private var isLoaded = false
    private var loadAttempted = false // a missing/bad asset used to be re-read (and re-logged) on every lookup

    init {
        if (context != null) {
            loadRules()
        }
    }

    @Synchronized
    fun loadRules() {
        val ctx = context ?: return
        if (isLoaded || loadAttempted) return
        loadAttempted = true
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
                try { // one malformed rule must not discard the whole rule set
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
                } catch (e: Exception) {
                    runCatching { Log.w(TAG, "Skipping malformed rule at index $i: ${e.message}") }
                }
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
     * 1. Item-specific rule (same coarse category first; jurisdiction-specific preferred)
     * 2. Material / RIC code rule (same coarse category first)
     * 3. Generic category rule for the jurisdiction, then any generic category rule
     * 4. Conservative fallback
     *
     * Matching is category-aware, so e.g. a "cardboard" barcode result can never resolve to a glass rule,
     * and partial item matches need >= 6 characters on both sides (no more "can" matching everything).
     */
    fun getHierarchicalRule(
        category: String,
        specificItem: String? = null,
        materialCode: String? = null,
        jurisdiction: String? = null
    ): DisposalRule {
        if (!isLoaded) loadRules()

        val targetJur = jurisdiction?.uppercase()?.trim()?.takeIf { it.isNotEmpty() }
        val rules = allRules
        val sameCategory = rules.filter { it.category.equals(category, ignoreCase = true) }

        // 1. Specific item
        if (!specificItem.isNullOrBlank()) {
            val query = specificItem.lowercase().trim()
            (bestItemMatch(sameCategory, query, targetJur)
                ?: bestItemMatch(rules.filter { it.specificItem?.lowercase()?.trim() == query }, query, targetJur)
                )?.let { return preserveSafety(it, category) }
        }

        // 2. Material code
        if (!materialCode.isNullOrBlank()) {
            val code = materialCode.uppercase().trim()
            fun pick(pool: List<DisposalRule>): DisposalRule? {
                val m = pool.filter { it.materialCode?.uppercase() == code }
                return m.firstOrNull { targetJur != null && it.jurisdiction.equals(targetJur, ignoreCase = true) }
                    ?: m.firstOrNull { it.specificItem == null }
                    ?: m.firstOrNull()
            }
            (pick(sameCategory) ?: pick(rules))?.let { return preserveSafety(it, category) }
        }

        // 3. Generic category rule
        if (targetJur != null) {
            sameCategory.firstOrNull { it.jurisdiction.equals(targetJur, ignoreCase = true) && it.specificItem == null }
                ?.let { return it }
        }
        getRuleForCategory(category)?.let { return it }

        // 4. Conservative unknown fallback
        return DisposalRule(
            id = "RULE-FALLBACK-UNKNOWN",
            category = category,
            jurisdiction = targetJur ?: "INDIA_SWM_2016",
            disposalMethod = "Segregate into Domestic Non-Biodegradable Dry Waste or consult local civic ward",
            isRecyclable = false,
            isHazardous = false,
            preparationInstructions = "Keep item clean, dry, and contained. Do not mix with organic wet waste.",
            safetyWarnings = "Unidentified waste item. Do not incinerate, crush, or discard in open drains.",
            targetBinColor = "BLACK",
            source = "SWM 2016 Conservative Precautionary Principle"
        )
    }

    /** Best match by: jurisdiction > exact > longest partial overlap. */
    private fun bestItemMatch(pool: List<DisposalRule>, query: String, jurisdiction: String?): DisposalRule? =
        pool.mapNotNull { rule ->
            val item = rule.specificItem?.lowercase()?.trim() ?: return@mapNotNull null
            val score = when {
                item == query -> 100
                minOf(item.length, query.length) >= MIN_PARTIAL_MATCH_LEN &&
                        (query.contains(item) || item.contains(query)) -> 50 + minOf(item.length, query.length)
                else -> return@mapNotNull null
            }
            val jurBonus = if (jurisdiction != null && rule.jurisdiction.equals(jurisdiction, ignoreCase = true)) 1000 else 0
            rule to (score + jurBonus)
        }.maxByOrNull { it.second }?.first

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
