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
    private var loadGeneration = 0     // bumped on every successful rule load

    /** Language of the rules currently loaded ("en", "hi", "te", ...). */
    @Volatile var currentLanguage: String = "en"
        private set
    /** Last language asked for, even when it had no file and English was used (stops reload on every lookup). */
    @Volatile private var requestedLanguage: String = "en"

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

    /**
     * Loads the rules for [languageCode] (e.g. "hi", "te-IN"): `rules/disposal_rules_<lang>.json` from assets when that
     * file exists and parses, otherwise the base `disposal_rules.json`. Safe to call repeatedly, for example when the
     * user changes the app language. Returns true when a language-specific file was used.
     */
    @Synchronized
    fun loadRulesForLanguage(languageCode: String? = null): Boolean {
        val lang = languageCode?.trim()?.lowercase()?.take(2)?.takeIf { it.length == 2 } ?: "en"
        requestedLanguage = lang
        val ctx = context
        if (ctx == null) {            // engines built from a JSON string / rule list (tests) have no assets
            currentLanguage = lang
            return false
        }
        loadAttempted = true

        if (lang != "en") {
            val localizedPath = rulesAssetPath.replace(".json", "_$lang.json")
            val localizedJson = readAssetOrNull(ctx, localizedPath)
            if (localizedJson != null && applyJson(localizedJson)) {
                currentLanguage = lang
                return true
            }
            runCatching { Log.i(TAG, "No usable $localizedPath; using the base rules") }
        }
        val baseJson = readAssetOrNull(ctx, rulesAssetPath)
        if (baseJson != null && applyJson(baseJson)) {
            currentLanguage = "en"
        } else {
            runCatching { Log.e(TAG, "Could not load $rulesAssetPath") }
        }
        return false
    }

    /** Lookups may carry the user's language; rules are switched only when it actually changed. */
    private fun ensureLanguage(languageCode: String?) {
        val lang = languageCode?.trim()?.lowercase()?.take(2)?.takeIf { it.length == 2 } ?: return
        if (context != null && lang != requestedLanguage) loadRulesForLanguage(lang)
    }

    private fun readAssetOrNull(ctx: Context, path: String): String? =
        try {
            ctx.assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }

    /** Parses and installs [json]; true only if the rules were actually replaced (a bad file keeps the old rules). */
    private fun applyJson(json: String): Boolean {
        val before = loadGeneration
        loadFromJson(json)
        return loadGeneration != before
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

                val conditionNotes = mutableMapOf<String, String>()
                obj.optJSONObject("condition_notes")?.let { notes ->
                    val keys = notes.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        notes.optString(k).takeIf { it.isNotBlank() }?.let { conditionNotes[k.lowercase()] = it }
                    }
                }
                val reuseIdeas = mutableListOf<String>()
                obj.optJSONArray("reuse_ideas")?.let { arr ->
                    for (j in 0 until arr.length()) arr.getString(j).takeIf { it.isNotBlank() }?.let { reuseIdeas.add(it) }
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
                    source = obj.optString("source", "India Solid Waste Management Rules 2016"),
                    conditionNotes = conditionNotes,
                    cleaningTips = obj.optString("cleaning_tips").takeIf { it.isNotBlank() },
                    reuseIdeas = reuseIdeas,
                    environmentalImpact = obj.optString("environmental_impact").takeIf { it.isNotBlank() }
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
        loadGeneration++
    }

    /**
     * Backward-compatible category lookup.
     */
    fun getRuleForCategory(category: String, languageCode: String? = null): DisposalRule? {
        if (!isLoaded) loadRules()
        ensureLanguage(languageCode)
        return rulesByCategory[category] ?: rulesByCategory[category.lowercase()]
    }

    private fun normalizeStreamCategory(category: String): String {
        val clean = category.trim().lowercase()
        return when {
            clean == "e-waste" || clean == "ewaste" || clean.contains("electronic") -> "E-waste"
            clean == "biodegradable" || clean == "organic" || clean == "wet" || clean == "food" -> "biodegradable"
            clean == "recyclables" || clean == "recyclable" || clean == "dry" -> "Recyclables"
            clean == "hazardous" || clean == "hazard" -> "hazardous"
            clean == "trash" || clean == "non-recyclable" -> "trash"
            else -> category.trim()
        }
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
        jurisdiction: String? = null,
        languageCode: String? = null
    ): DisposalRule {
        if (!isLoaded) loadRules()
        ensureLanguage(languageCode)

        val targetJur = jurisdiction?.uppercase()?.trim()?.takeIf { it.isNotEmpty() }
        val rules = allRules
        val normCat = normalizeStreamCategory(category)
        val sameCategory = rules.filter {
            it.category.equals(category, ignoreCase = true) || it.category.equals(normCat, ignoreCase = true)
        }

        // 1. Specific item
        if (!specificItem.isNullOrBlank()) {
            val query = specificItem.lowercase().trim()
            (bestItemMatch(sameCategory, query, targetJur)
                ?: bestItemMatch(rules.filter { it.specificItem?.lowercase()?.trim() == query || it.category.lowercase().trim() == query }, query, targetJur)
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
        (getRuleForCategory(category) ?: getRuleForCategory(normCat))?.let { return it }

        // 4. Determine appropriate waste stream and bin colour dynamically (never default to black for identifiable waste)
        val fallbackBin = when {
            normCat.equals("E-waste", ignoreCase = true) || category.contains("e-waste", ignoreCase = true) || category.contains("ewaste", ignoreCase = true) || category.contains("electronic", ignoreCase = true) -> "ORANGE"
            normCat.equals("biodegradable", ignoreCase = true) || category.contains("bio", ignoreCase = true) || category.contains("organic", ignoreCase = true) || category.contains("wet", ignoreCase = true) -> "GREEN"
            normCat.equals("hazardous", ignoreCase = true) || category.contains("hazard", ignoreCase = true) || category.contains("battery", ignoreCase = true) -> "RED"
            normCat.equals("Recyclables", ignoreCase = true) || category.contains("recyc", ignoreCase = true) || category.contains("plastic", ignoreCase = true) || category.contains("paper", ignoreCase = true) || category.contains("metal", ignoreCase = true) || category.contains("glass", ignoreCase = true) || category.contains("cardboard", ignoreCase = true) -> "BLUE"
            else -> "BLACK"
        }

        val lang = currentLanguage
        val isRecyc = fallbackBin == "BLUE" || fallbackBin == "GREEN" || fallbackBin == "ORANGE"
        val isHaz = fallbackBin == "RED"

        val (fallbackMethod, fallbackPrep) = when (lang) {
            "te" -> Pair(
                if (fallbackBin == "ORANGE") "అధికారిక ఇ-వ్యర్థాల సేకరణ కేంద్రం / రీసైక్లర్ (నారింజ డబ్బా)"
                else if (fallbackBin == "GREEN") "సేంద్రీయ తడి వ్యర్థాలు (ఆకుపచ్చ డబ్బా)"
                else if (fallbackBin == "BLUE") "పొడి రీసైకిల్ వ్యర్థాలు (నీలం డబ్బా)"
                else if (fallbackBin == "RED") "గృహ ప్రమాదకర వ్యర్థాల సేకరణ (ఎరుపు డబ్బా)"
                else "పునర్వినియోగించలేని పొడి వ్యర్థాల డబ్బా (నలుపు డబ్బా)",
                "వస్తువును పొడిగా మరియు శుభ్రంగా ఉంచండి. తడి వ్యర్థాలతో కలపవద్దు."
            )
            "hi" -> Pair(
                if (fallbackBin == "ORANGE") "अधिकृत ई-कचरा संग्रह केंद्र (नारंगी डिब्बा)"
                else if (fallbackBin == "GREEN") "गीला जैविक कचरा (हरा डिब्बा)"
                else if (fallbackBin == "BLUE") "सूखा पुनर्चक्रण योग्य कचरा (नीला डिब्बा)"
                else if (fallbackBin == "RED") "घरेलू खतरनाक कचरा (लाल डिब्बा)"
                else "पुनर्चक्रण अयोग्य कचरा (काला डिब्बा)",
                "वस्तु को साफ और सूखा रखें। गीले कचरे के साथ न मिलाएं।"
            )
            "ta" -> Pair(
                if (fallbackBin == "ORANGE") "அங்கீகரிக்கப்பட்ட மின்-கழிவு மையம் (ஆரஞ்சு தொட்டி)"
                else if (fallbackBin == "GREEN") "ஈரக் கரிமக் கழிவு (பச்சை தொட்டி)"
                else if (fallbackBin == "BLUE") "உலர் மறுசுழற்சி கழிவு (நீலம் தொட்டி)"
                else if (fallbackBin == "RED") "அபாயகரமான கழிவு (சிவப்பு தொட்டி)"
                else "மறுசுழற்சி செய்ய முடியாத கழிவு (கருப்பு தொட்டி)",
                "பொருளை உலர்வாகவும் சுத்தமாகவும் வைக்கவும். ஈரமான கழிவுகளுடன் கலக்க வேண்டாம்."
            )
            "mr" -> Pair(
                if (fallbackBin == "ORANGE") "अधिकृत ई-कचरा संकलन केंद्र (नारिंगी कुंडी)"
                else if (fallbackBin == "GREEN") "ओला सेंद्रिय कचरा (हिरवा कुंडी)"
                else if (fallbackBin == "BLUE") "सुका पुनर्वापरयोग्य कचरा (निळा कुंडी)"
                else if (fallbackBin == "RED") "धोकादायक कचरा (लाल कुंडी)"
                else "पुनर्वापर न करता येणारा कचरा (काळा कुंडी)",
                "वस्तू कोरडी आणि स्वच्छ ठेवा. ओल्या कचऱ्यात मिसळू नका."
            )
            "kn" -> Pair(
                if (fallbackBin == "ORANGE") "ಅಧಿಕೃತ ಇ-ತ್ಯಾಜ್ಯ ಸಂಗ್ರಹ ಕೇಂದ್ರ (ಕಿತ್ತಳೆ ಬುಟ್ಟಿ)"
                else if (fallbackBin == "GREEN") "ಹಸಿ ಸಾವಯವ ತ್ಯಾಜ್ಯ (ಹಸಿರು ಬುಟ್ಟಿ)"
                else if (fallbackBin == "BLUE") "ಒಣ ಮರುಬಳಕೆ ತ್ಯಾಜ್ಯ (ನೀಲಿ ಬುಟ್ಟಿ)"
                else if (fallbackBin == "RED") "ಅಪಾಯಕಾರಿ ತ್ಯಾಜ್ಯ ಸಂಗ್ರಹ (ಕೆಂಪು ಬುಟ್ಟಿ)"
                else "ಮರುಬಳಕೆಯಾಗದ ಒಣ ತ್ಯಾಜ್ಯ (ಕಪ್ಪು ಬುಟ್ಟಿ)",
                "ವಸ್ತುವನ್ನು ಸ್ವಚ್ಛವಾಗಿ ಮತ್ತು ಒಣಗಿಸಿ ಇರಿಸಿ. ಹಸಿ ತ್ಯಾಜ್ಯದೊಂದಿಗೆ ಬೆರೆಸಬೇಡಿ."
            )
            "ml" -> Pair(
                if (fallbackBin == "ORANGE") "അംഗീകൃത ഇ-മാലിന്യ കേന്ദ്രം (ഓറഞ്ച് തൊട്ടി)"
                else if (fallbackBin == "GREEN") "നനഞ്ഞ ജൈവ മാലിന്യം (പച്ച തൊട്ടി)"
                else if (fallbackBin == "BLUE") "ഉണങ്ങിയ പുനരുപയോഗ മാലിന്യം (നീല തൊട്ടി)"
                else if (fallbackBin == "RED") "അപകടകരമായ മാലിന്യ കേന്ദ്രം (ചുവന്ന തൊട്ടി)"
                else "പുനരുപയോഗിക്കാൻ കഴിയാത്ത മാലിന്യം (കറുത്ത തൊട്ടി)",
                "ഇനം വൃത്തിയായും ഉണക്കിയും സൂക്ഷിക്കുക. ജൈവ മാലിന്യങ്ങളുമായി കലർത്തരുത്."
            )
            "bn" -> Pair(
                if (fallbackBin == "ORANGE") "অনুমোদিত ই-বর্জ্য সংগ্রহ কেন্দ্র (কমলা পাত্র)"
                else if (fallbackBin == "GREEN") "ভেজা জৈব বর্জ্য সংগ্রহ (সবুজ পাত্র)"
                else if (fallbackBin == "BLUE") "শুকনো পুনর্ব্যবহারযোগ্য বর্জ্য (নীল পাত্র)"
                else if (fallbackBin == "RED") "বিপজ্জনক বর্জ্য সংগ্রহ (লাল পাত্র)"
                else "অপুনর্ব্যবহারযোগ্য শুকনো বর্জ্য (কালো পাত্র)",
                "বস্তুটি পরিষ্কার এবং শুকনো রাখুন। ভেজা জৈব বর্জ্যের সাথে মেশাবেন না।"
            )
            "gu" -> Pair(
                if (fallbackBin == "ORANGE") "અધિકૃત ઇ-કચરો સંગ્રહ કેન્દ્ર (નારંગી ડબ્બો)"
                else if (fallbackBin == "GREEN") "ભીનો જૈવિક કચરો (લીલો ડબ્બો)"
                else if (fallbackBin == "BLUE") "સૂકો રિસાયક્લેબલ કચરો (વાદળી ડબ્બો)"
                else if (fallbackBin == "RED") "જોખમી કચરો સંગ્રહ (લાલ ડબ્બો)"
                else "બિન-પુનઃઉપયોગી સૂકો કચરો (કાળો ડબ્બો)",
                "વસ્તુને સ્વચ્છ અને સૂકી રાખો. ભીના કચરા સાથે ભેળવશો નહીં."
            )
            else -> Pair(
                if (fallbackBin == "ORANGE") "Authorized E-Waste Collection Point / Recycler (Orange Bin)"
                else if (fallbackBin == "GREEN") "Wet Waste Collection (Green Bin)"
                else if (fallbackBin == "BLUE") "Dry Recyclable Waste (Blue Bin)"
                else if (fallbackBin == "RED") "Special Care / Hazardous Waste Collection (Red Bin)"
                else "Segregate into Domestic Non-Biodegradable Dry Waste (Black Bin)",
                "Keep item clean, dry, and contained. Do not mix with organic wet waste."
            )
        }

        return DisposalRule(
            id = "RULE-FALLBACK-UNKNOWN",
            category = category,
            jurisdiction = targetJur ?: "INDIA_SWM_2016",
            disposalMethod = fallbackMethod,
            isRecyclable = isRecyc,
            isHazardous = isHaz,
            preparationInstructions = fallbackPrep,
            safetyWarnings = if (isHaz) "Handle with care. Do not burn or pour into drains." else "Keep clean and dry. Segregate appropriately.",
            targetBinColor = fallbackBin,
            source = "SWM 2016 Precautionary Categorization"
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
