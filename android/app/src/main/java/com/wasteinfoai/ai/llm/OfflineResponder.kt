package com.wasteinfoai.ai.llm

import com.wasteinfoai.ai.rules.DisposalRule
import com.wasteinfoai.ai.rules.SafetyGuard
import com.wasteinfoai.core.language.CategoryLocalization

/**
 * Deterministic offline response generator for Bottle.
 * Resolves pronouns and condition follow-ups, respects intent priority,
 * and strictly prevents hazardous items from being upcycled or burned.
 * Supports native language responses for English (en), Hindi (hi), Telugu (te), Tamil (ta), and Marathi (mr).
 */
class OfflineResponder {

    private val conditionKeywords = mapOf(
        "broken" to listOf("broken", "shattered", "cracked", "torn", "fractured", "shards", "टूटा", "टूटी", "పగిలిన", "உடைந்த", "फुटलेला", "फुटलेली"),
        "crushed" to listOf("crushed", "squashed", "flattened", "bent", "compacted", "कुचला", "कुचली", "నలిగిన", "நசுக்கப்பட்ட", "चिरडलेला"),
        "wet" to listOf("wet", "soaked", "damp", "moist", "गीला", "गीली", "తడి", "ஈரமான", "ओला", "ओली"),
        "greasy" to listOf("greasy", "oily", "food-stained", "cheese", "dirty", "soiled", "तेल", "चिकनाई", "జిడ్డు", "எண்ணெய்", "तेलकट"),
        "empty" to listOf("empty", "rinsed", "खाली", "ఖాళీ", "காலி", "रिकामा", "रिकामी"),
        "full" to listOf("full", "filled", "leftover", "भरा", "నిండిన", "முழு", "भरलेला")
    )

    fun respond(
        context: AssistantContext,
        rule: DisposalRule?,
        query: String,
        history: List<*> = emptyList<Any>(),
        previousIntent: AssistantIntent? = null
    ): String {
        val rawItem = context.specificItem ?: context.category
        val lang = context.languageCode?.trim()?.lowercase()?.take(2) ?: "en"
        val itemName = CategoryLocalization.getCategoryDisplayName(rawItem, lang)
        val safety = SafetyGuard.evaluateSafety(context.category, isUncertain = false)
        val isHazardous = safety.isHazardous || rule?.isHazardous == true
        val warning = rule?.safetyWarnings?.takeIf { it.isNotBlank() } ?: safety.primaryWarning
        val targetBin = CategoryLocalization.getBinColorDisplayName(rule?.targetBinColor ?: "", lang)

        if (rule == null) {
            return when (lang) {
                "te" -> "'${context.category}' వర్గం కోసం ప్రస్తుతం ధృవీకరించబడిన మునిసిపల్ నియమం నమోదు కాలేదు. దయచేసి పారవేసే ముందు మీ స్థానిక వ్యర్థాల అధికారిని సంప్రదించండి."
                "hi" -> "'${context.category}' श्रेणी के लिए वर्तमान में कोई सत्यापित नगरपालिका नियम पंजीकृत नहीं है। कृपया निपटान से पहले अपने स्थानीय अपशिष्ट प्राधिकरण से परामर्श लें।"
                "ta" -> "'${context.category}' பிரிவுக்கு தற்போது சரிபார்க்கப்பட்ட நகராட்சி விதி எதுவும் பதிவு செய்யப்படவில்லை. அகற்றுவதற்கு முன் உங்கள் உள்ளூர் கழிவு அதிகாரியை அணுகவும்."
                "mr" -> "'${context.category}' वर्गासाठी सध्या कोणताही पडताळणी केलेला महापालिका नियम नोंदणीकृत नाही. कृपया विल्हेवाट लावण्यापूर्वी स्थानिक अधिकाऱ्यांशी संपर्क साधा."
                else -> "No verified municipal rule is currently registered for category '${context.category}'. Please consult your local waste authority before disposal."
            }
        }

        // 1. Condition Follow-Up Check ("what if it's broken / greasy / crushed / wet?")
        // Detection runs on what the USER typed. The item name must never be injected first: "Food Storage Glass Jar"
        // would trigger CLEANING and "Broken Glass" would trigger a "broken" note on every question.
        val detectedCondition = detectCondition(query)
        val conditionAdvice = detectedCondition?.let { rule.conditionNotes[it] }

        // 2. Intent Detection
        val detectedIntent = IntentDetector.detect(query)
        // Short follow-ups ("and if it's wet?", "what about glass?") inherit the previous intent.
        val intent = if (detectedIntent == AssistantIntent.GENERAL && conditionAdvice == null &&
            previousIntent != null && query.trim().split(Regex("\\s+")).size <= 5
        ) previousIntent else detectedIntent

        // 3. Strict Safety Refusal: Hazardous items cannot be upcycled, reused, or burned!
        if (isHazardous && (intent == AssistantIntent.UPCYCLE || query.contains(Regex("\\b(burn|fire|diy|reuse|craft|pot|upcycle)\\b", RegexOption.IGNORE_CASE)))) {
            return when (lang) {
                "te" -> buildString {
                    append("⚠️ SAFETY REFUSAL — భద్రతా నిరాకరణ (ప్రమాదకర వ్యర్థాలు):\n")
                    append("తీవ్రమైన ప్రమాదాల కారణంగా $itemName ని తిరిగి ఉపయోగించడం, క్రాఫ్ట్ చేయడం లేదా కాల్చడం చేయకూడదు!\n\n")
                    append("భద్రతా హెచ్చరిక: $warning\n\n")
                    append("సిఫార్సు చేసిన పారవేత: కేవలం ${rule.disposalMethod} (${rule.targetBinColor} డబ్బా) కు మాత్రమే అప్పగించండి.")
                }.trim()
                "hi" -> buildString {
                    append("⚠️ SAFETY REFUSAL — सुरक्षा अस्वीकृति (खतरनाक कचरा):\n")
                    append("गंभीर सुरक्षा खतरों के कारण $itemName को दोबारा उपयोग, क्राफ्ट या जलाया नहीं जा सकता!\n\n")
                    append("सुरक्षा चेतावनी: $warning\n\n")
                    append("अनुशंसित निपटान: केवल ${rule.disposalMethod} (${rule.targetBinColor} डिब्बा) को सौंपें।")
                }.trim()
                "ta" -> buildString {
                    append("⚠️ SAFETY REFUSAL — பாதுகாப்பு மறுப்பு (அபாயகரமான கழிவு):\n")
                    append("தீவிர பாதுகாப்பு ஆபத்துகள் காரணமாக $itemName ஐ மீண்டும் பயன்படுத்தவோ, கைவினை செய்யவோ அல்லது எரிக்கவோ கூடாது!\n\n")
                    append("பாதுகாப்பு எச்சரிக்கை: $warning\n\n")
                    append("பரிந்துரைக்கப்பட்ட அகற்றல்: கண்டிப்பாக ${rule.disposalMethod} (${rule.targetBinColor} தொட்டி) இடம் ஒப்படைக்கவும்.")
                }.trim()
                "mr" -> buildString {
                    append("⚠️ SAFETY REFUSAL — सुरक्षा नकार (घातक कचरा):\n")
                    append("गंभीर सुरक्षा धोक्यांमुळे $itemName चा पुन्हा वापर, हस्तकला किंवा जाळणे करू नये!\n\n")
                    append("सुरक्षा चेतावणी: $warning\n\n")
                    append("शिफारस केलेली विल्हेवाट: केवळ ${rule.disposalMethod} (${rule.targetBinColor} कुंडी) कडे सोपवा.")
                }.trim()
                else -> buildString {
                    append("⚠️ SAFETY REFUSAL (Hazardous Waste):\n")
                    append("$itemName cannot be reused, repurposed, crafted, or burned due to severe safety risks!\n\n")
                    append("Safety Warning: $warning\n\n")
                    append("Recommended Disposal: Hand over strictly to ${rule.disposalMethod} (${rule.targetBinColor} BIN).")
                }.trim()
            }
        }

        // 4. Intent-specific response generation
        return when (intent) {
            AssistantIntent.SAFETY -> {
                when (lang) {
                    "te" -> buildString {
                        append("⚠️ $itemName కోసం భద్రతా సలహా:\n")
                        append("$warning\n\n")
                        if (safety.recommendedActions.isNotEmpty()) {
                            append("భద్రతా మార్గదర్శకాలు:\n")
                            safety.recommendedActions.forEach { append("• $it\n") }
                            append("\n")
                        }
                        append("కేటాయించిన డబ్బా: ${rule.targetBinColor} డబ్బా (${rule.disposalMethod})")
                        if (conditionAdvice != null) {
                            append("\n\nస్థితి సమాచారం ($detectedCondition): $conditionAdvice")
                        }
                    }.trim()
                    "hi" -> buildString {
                        append("⚠️ $itemName के लिए सुरक्षा चेतावनी:\n")
                        append("$warning\n\n")
                        if (safety.recommendedActions.isNotEmpty()) {
                            append("सुरक्षा प्रोटोकॉल:\n")
                            safety.recommendedActions.forEach { append("• $it\n") }
                            append("\n")
                        }
                        append("लक्षित डिब्बा: ${rule.targetBinColor} डिब्बा (${rule.disposalMethod})")
                        if (conditionAdvice != null) {
                            append("\n\nस्थिति नोट ($detectedCondition): $conditionAdvice")
                        }
                    }.trim()
                    "ta" -> buildString {
                        append("⚠️ $itemName க்கான பாதுகாப்பு எச்சரிக்கை:\n")
                        append("$warning\n\n")
                        if (safety.recommendedActions.isNotEmpty()) {
                            append("பாதுகாப்பு நெறிமுறை:\n")
                            safety.recommendedActions.forEach { append("• $it\n") }
                            append("\n")
                        }
                        append("இலக்கு தொட்டி: ${rule.targetBinColor} தொட்டி (${rule.disposalMethod})")
                        if (conditionAdvice != null) {
                            append("\n\nநிலை குறிப்பு ($detectedCondition): $conditionAdvice")
                        }
                    }.trim()
                    "mr" -> buildString {
                        append("⚠️ $itemName साठी सुरक्षा सूचना:\n")
                        append("$warning\n\n")
                        if (safety.recommendedActions.isNotEmpty()) {
                            append("सुरक्षा नियमावली:\n")
                            safety.recommendedActions.forEach { append("• $it\n") }
                            append("\n")
                        }
                        append("लक्षित कुंडी: ${rule.targetBinColor} कुंडी (${rule.disposalMethod})")
                        if (conditionAdvice != null) {
                            append("\n\nस्थिती नोंद ($detectedCondition): $conditionAdvice")
                        }
                    }.trim()
                    else -> buildString {
                        append("⚠️ SAFETY ADVISORY for $itemName:\n")
                        append("$warning\n\n")
                        if (safety.recommendedActions.isNotEmpty()) {
                            append("Safety Protocol:\n")
                            safety.recommendedActions.forEach { append("• $it\n") }
                            append("\n")
                        }
                        append("Target Bin: ${rule.targetBinColor} BIN (${rule.disposalMethod})")
                        if (conditionAdvice != null) {
                            append("\n\nCondition Note ($detectedCondition): $conditionAdvice")
                        }
                    }.trim()
                }
            }

            AssistantIntent.CLEANING -> {
                val tips = rule.cleaningTips?.takeIf { it.isNotBlank() } ?: rule.preparationInstructions
                when (lang) {
                    "te" -> buildString {
                        append("🧼 $itemName శుభ్రపరచడం మరియు సన్నాహక సూచనలు:\n\n")
                        append("$tips\n\n")
                        if (conditionAdvice != null) {
                            append("స్థితి నిర్వహణ ($detectedCondition):\n$conditionAdvice\n\n")
                        }
                        append("పారవేసే డబ్బా: ${rule.targetBinColor} డబ్బా (${if (rule.isRecyclable) "రీసైకిల్ చేయగలదు" else "రీసైకిల్ చేయలేనిది"}).")
                    }.trim()
                    "hi" -> buildString {
                        append("🧼 $itemName की सफाई और तैयारी के निर्देश:\n\n")
                        append("$tips\n\n")
                        if (conditionAdvice != null) {
                            append("स्थिति प्रबंधन ($detectedCondition):\n$conditionAdvice\n\n")
                        }
                        append("लक्षित निपटान: ${rule.targetBinColor} डिब्बा (${if (rule.isRecyclable) "पुनर्चक्रण योग्य" else "गैर-पुनर्चक्रण योग्य"}).")
                    }.trim()
                    "ta" -> buildString {
                        append("🧼 $itemName சுத்தம் செய்தல் மற்றும் தயாரிப்பு வழிமுறைகள்:\n\n")
                        append("$tips\n\n")
                        if (conditionAdvice != null) {
                            append("நிலை கையாளுதல் ($detectedCondition):\n$conditionAdvice\n\n")
                        }
                        append("இலக்கு அகற்றல்: ${rule.targetBinColor} தொட்டி (${if (rule.isRecyclable) "மறுசுழற்சி செய்யக்கூடியது" else "மறுசுழற்சி செய்ய முடியாதது"}).")
                    }.trim()
                    "mr" -> buildString {
                        append("🧼 $itemName स्वच्छता आणि तयारीच्या सूचना:\n\n")
                        append("$tips\n\n")
                        if (conditionAdvice != null) {
                            append("स्थिती हाताळणी ($detectedCondition):\n$conditionAdvice\n\n")
                        }
                        append("विल्हेवाट कुंडी: ${rule.targetBinColor} कुंडी (${if (rule.isRecyclable) "पुनर्वापरयोग्य" else "पुनर्वापर न करता येणारा"}).")
                    }.trim()
                    else -> buildString {
                        append("🧼 Cleaning & Preparation Instructions for $itemName:\n\n")
                        append("$tips\n\n")
                        if (conditionAdvice != null) {
                            append("Condition Handling ($detectedCondition):\n$conditionAdvice\n\n")
                        }
                        append("Target Disposal: ${rule.targetBinColor} BIN (${if (rule.isRecyclable) "Recyclable" else "Non-Recyclable"}).")
                    }.trim()
                }
            }

            AssistantIntent.UPCYCLE -> {
                when (lang) {
                    "te" -> buildString {
                        append("♻️ $itemName కోసం పునర్వినియోగం & సృజనాత్మక ఆలోచనలు:\n\n")
                        if (rule.reuseIdeas.isNotEmpty()) {
                            rule.reuseIdeas.forEach { idea -> append("• $idea\n") }
                        } else {
                            append("• శుభ్రం చేసి నిల్వ డబ్బాలుగా, తోట కుండీలుగా లేదా క్రాఫ్ట్ వస్తువులుగా ఉపయోగించండి.\n")
                        }
                        if (!rule.environmentalImpact.isNullOrBlank()) {
                            append("\nపర్యావరణ సమాచారం: ${rule.environmentalImpact}\n")
                        }
                        if (conditionAdvice != null) {
                            append("\nస్థితి సమాచారం ($detectedCondition): $conditionAdvice\n")
                        }
                        append("\nవస్తువును పూర్తిగా ఉపయోగించిన తర్వాత, ${rule.targetBinColor} డబ్బాలో వేయండి.")
                    }.trim()
                    "hi" -> buildString {
                        append("♻️ $itemName के लिए पुनः उपयोग और रचनात्मक विचार:\n\n")
                        if (rule.reuseIdeas.isNotEmpty()) {
                            rule.reuseIdeas.forEach { idea -> append("• $idea\n") }
                        } else {
                            append("• साफ करके घरेलू भंडारण, गमलों या क्राफ्ट प्रोजेक्ट्स के रूप में उपयोग करें।\n")
                        }
                        if (!rule.environmentalImpact.isNullOrBlank()) {
                            append("\nपर्यावरण प्रभाव: ${rule.environmentalImpact}\n")
                        }
                        if (conditionAdvice != null) {
                            append("\nस्थिति नोट ($detectedCondition): $conditionAdvice\n")
                        }
                        append("\nउपयोग समाप्त होने पर, इसे ${rule.targetBinColor} डिब्बे में डालें।")
                    }.trim()
                    "ta" -> buildString {
                        append("♻️ $itemName க்கான மறுபயன்பாடு மற்றும் கைவினை யோசனைகள்:\n\n")
                        if (rule.reuseIdeas.isNotEmpty()) {
                            rule.reuseIdeas.forEach { idea -> append("• $idea\n") }
                        } else {
                            append("• சுத்தம் செய்து வீட்டு சேமிப்பு, செடி தொட்டிகள் அல்லது கைவினைப் பொருட்களாகப் பயன்படுத்தவும்.\n")
                        }
                        if (!rule.environmentalImpact.isNullOrBlank()) {
                            append("\nசுற்றுச்சூழல் தாக்கம்: ${rule.environmentalImpact}\n")
                        }
                        if (conditionAdvice != null) {
                            append("\nநிலை குறிப்பு ($detectedCondition): $conditionAdvice\n")
                        }
                        append("\nபயன்பாடு முடிந்ததும், ${rule.targetBinColor} தொட்டியில் போடவும்.")
                    }.trim()
                    "mr" -> buildString {
                        append("♻️ $itemName साठी पुनर्वापर आणि सर्जनशील कल्पना:\n\n")
                        if (rule.reuseIdeas.isNotEmpty()) {
                            rule.reuseIdeas.forEach { idea -> append("• $idea\n") }
                        } else {
                            append("• स्वच्छ करून घरगुती साठवणूक, झाडांची कुंडी किंवा हस्तकला वस्तूंसाठी वापरा.\n")
                        }
                        if (!rule.environmentalImpact.isNullOrBlank()) {
                            append("\nपर्यावरणीय प्रभाव: ${rule.environmentalImpact}\n")
                        }
                        if (conditionAdvice != null) {
                            append("\nस्थिती नोंद ($detectedCondition): $conditionAdvice\n")
                        }
                        append("\nवापर संपल्यावर, ${rule.targetBinColor} कुंडीत टाका.")
                    }.trim()
                    else -> buildString {
                        append("♻️ Upcycling & Creative Reuse Ideas for $itemName:\n\n")
                        if (rule.reuseIdeas.isNotEmpty()) {
                            rule.reuseIdeas.forEach { idea -> append("• $idea\n") }
                        } else {
                            append("• Clean and repurpose for household storage, gardening pots, or craft projects.\n")
                        }
                        if (!rule.environmentalImpact.isNullOrBlank()) {
                            append("\nEnvironmental Impact: ${rule.environmentalImpact}\n")
                        }
                        if (conditionAdvice != null) {
                            append("\nCondition Note ($detectedCondition): $conditionAdvice\n")
                        }
                        append("\nWhen finished with the item, dispose in ${rule.targetBinColor} BIN.")
                    }.trim()
                }
            }

            AssistantIntent.BIN_LOCATION -> {
                when (lang) {
                    "te" -> buildString {
                        append("📍 $itemName పారవేత & డబ్బా వర్గీకరణ:\n\n")
                        append("• కేటాయించిన డబ్బా: $targetBin\n")
                        append("• పారవేత విధానం: ${rule.disposalMethod}\n")
                        append("• రీసైకిల్ చేయగలదా: ${if (rule.isRecyclable) "అవును" else "కాదు"}\n")
                        if (rule.acceptedPathways.isNotEmpty()) {
                            append("• స్వీకరించబడే ప్రదేశాలు: ${rule.acceptedPathways.joinToString(", ")}\n")
                        }
                        if (rule.prohibitedPathways.isNotEmpty()) {
                            append("• నిషిద్ధం: ${rule.prohibitedPathways.joinToString("; ")}\n")
                        }
                        if (conditionAdvice != null) {
                            append("\nస్థితి సమాచారం ($detectedCondition): $conditionAdvice\n")
                        }
                    }.trim()
                    "hi" -> buildString {
                        append("📍 $itemName निपटान और डिब्बा पृथक्करण:\n\n")
                        append("• लक्षित डिब्बा: $targetBin\n")
                        append("• निपटान मार्ग: ${rule.disposalMethod}\n")
                        append("• पुनर्चक्रण योग्य: ${if (rule.isRecyclable) "हाँ" else "नहीं"}\n")
                        if (rule.acceptedPathways.isNotEmpty()) {
                            append("• स्वीकृत केंद्र: ${rule.acceptedPathways.joinToString(", ")}\n")
                        }
                        if (rule.prohibitedPathways.isNotEmpty()) {
                            append("• निषिद्ध: ${rule.prohibitedPathways.joinToString("; ")}\n")
                        }
                        if (conditionAdvice != null) {
                            append("\nस्थिति नोट ($detectedCondition): $conditionAdvice\n")
                        }
                    }.trim()
                    "ta" -> buildString {
                        append("📍 $itemName அகற்றல் மற்றும் தொட்டி தரம் பிரித்தல்:\n\n")
                        append("• இலக்கு தொட்டி: $targetBin\n")
                        append("• அகற்றும் வழிமுறை: ${rule.disposalMethod}\n")
                        append("• மறுசுழற்சி செய்யக்கூடியதா: ${if (rule.isRecyclable) "ஆம்" else "இல்லை"}\n")
                        if (rule.acceptedPathways.isNotEmpty()) {
                            append("• ஏற்றுக்கொள்ளப்படும் இடங்கள்: ${rule.acceptedPathways.joinToString(", ")}\n")
                        }
                        if (rule.prohibitedPathways.isNotEmpty()) {
                            append("• தடைசெய்யப்பட்டவை: ${rule.prohibitedPathways.joinToString("; ")}\n")
                        }
                        if (conditionAdvice != null) {
                            append("\nநிலை குறிப்பு ($detectedCondition): $conditionAdvice\n")
                        }
                    }.trim()
                    "mr" -> buildString {
                        append("📍 $itemName विल्हेवाट आणि कचराकुंडी वर्गीकरण:\n\n")
                        append("• लक्षित कुंडी: $targetBin\n")
                        append("• विल्हेवाट मार्ग: ${rule.disposalMethod}\n")
                        append("• पुनर्वापर करण्यायोग्य: ${if (rule.isRecyclable) "होय" else "नाही"}\n")
                        if (rule.acceptedPathways.isNotEmpty()) {
                            append("• स्वीकार्य ठिकाणे: ${rule.acceptedPathways.joinToString(", ")}\n")
                        }
                        if (rule.prohibitedPathways.isNotEmpty()) {
                            append("• निषिद्ध: ${rule.prohibitedPathways.joinToString("; ")}\n")
                        }
                        if (conditionAdvice != null) {
                            append("\nस्थिती नोंद ($detectedCondition): $conditionAdvice\n")
                        }
                    }.trim()
                    else -> buildString {
                        append("📍 Disposal & Bin Segregation for $itemName:\n\n")
                        append("• Target Bin: $targetBin\n")
                        append("• Disposal Pathway: ${rule.disposalMethod}\n")
                        append("• Recyclable: ${if (rule.isRecyclable) "Yes" else "No"}\n")
                        if (rule.acceptedPathways.isNotEmpty()) {
                            append("• Accepted At: ${rule.acceptedPathways.joinToString(", ")}\n")
                        }
                        if (rule.prohibitedPathways.isNotEmpty()) {
                            append("• Prohibited: ${rule.prohibitedPathways.joinToString("; ")}\n")
                        }
                        if (conditionAdvice != null) {
                            append("\nCondition Note ($detectedCondition): $conditionAdvice\n")
                        }
                    }.trim()
                }
            }

            else -> {
                if (conditionAdvice != null) {
                    when (lang) {
                        "te" -> buildString {
                            append("$detectedCondition $itemName కోసం నిర్వహణ:\n\n")
                            append("$conditionAdvice\n\n")
                            append("ప్రామాణిక మార్గం: ${rule.disposalMethod} (${rule.targetBinColor} డబ్బా).")
                        }.trim()
                        "hi" -> buildString {
                            append("$detectedCondition $itemName के लिए प्रबंधन:\n\n")
                            append("$conditionAdvice\n\n")
                            append("मानक मार्ग: ${rule.disposalMethod} (${rule.targetBinColor} डिब्बा).")
                        }.trim()
                        "ta" -> buildString {
                            append("$detectedCondition $itemName க்கான கையாளுதல்:\n\n")
                            append("$conditionAdvice\n\n")
                            append("நிலையான முறை: ${rule.disposalMethod} (${rule.targetBinColor} தொட்டி).")
                        }.trim()
                        "mr" -> buildString {
                            append("$detectedCondition $itemName साठी हाताळणी:\n\n")
                            append("$conditionAdvice\n\n")
                            append("मानक पद्धत: ${rule.disposalMethod} (${rule.targetBinColor} कुंडी).")
                        }.trim()
                        else -> buildString {
                            append("Handling for $detectedCondition $itemName:\n\n")
                            append("$conditionAdvice\n\n")
                            append("Standard Pathway: ${rule.disposalMethod} (${rule.targetBinColor} BIN).")
                        }.trim()
                    }
                } else {
                    buildGeneralExplanation(itemName, rule, isHazardous, warning, lang)
                }
            }
        }
    }

    private fun buildGeneralExplanation(
        itemName: String,
        rule: DisposalRule,
        isHazardous: Boolean,
        warning: String,
        lang: String = "en"
    ): String {
        val targetBin = CategoryLocalization.getBinColorDisplayName(rule.targetBinColor, lang)
        return when (lang) {
        "te" -> buildString {
            if (isHazardous) {
                append("⚠️ భద్రతా సలహా (ప్రమాదకర వ్యర్థాలు):\n$warning\n\n")
            }
            append("$itemName కోసం మార్గదర్శకత్వం:\n")
            append("• సిఫార్సు చేసిన పారవేత విధానం: ${rule.disposalMethod}\n")
            append("• కేటాయించిన డబ్బా: $targetBin\n")
            append("• రీసైకిల్ చేయగలదా: ${if (rule.isRecyclable) "అవును" else "కాదు"}\n\n")
            if (rule.preparationInstructions.isNotBlank()) {
                append("సన్నాహక దశలు:\n• ${rule.preparationInstructions}\n")
            }
            if (rule.prohibitedPathways.isNotEmpty()) {
                append("\nచేయకూడనివి: ${rule.prohibitedPathways.joinToString("; ")}\n")
            }
            if (!rule.environmentalImpact.isNullOrBlank()) {
                append("\nపర్యావరణ సమాచారం: ${rule.environmentalImpact}\n")
            }
            append("\n(బాటిల్ ఆఫ్‌లైన్ అసిస్టెంట్ — ${rule.source} ఆధారంగా)")
        }.trim()
        "hi" -> buildString {
            if (isHazardous) {
                append("⚠️ सुरक्षा सलाह (खतरनाक कचरा):\n$warning\n\n")
            }
            append("$itemName के लिए मार्गदर्शन:\n")
            append("• अनुशंसित निपटान: ${rule.disposalMethod}\n")
            append("• लक्षित डिब्बा: $targetBin\n")
            append("• पुनर्चक्रण योग्य: ${if (rule.isRecyclable) "हाँ" else "नहीं"}\n\n")
            if (rule.preparationInstructions.isNotBlank()) {
                append("तैयारी के चरण:\n• ${rule.preparationInstructions}\n")
            }
            if (rule.prohibitedPathways.isNotEmpty()) {
                append("\nनिषिद्ध: ${rule.prohibitedPathways.joinToString("; ")}\n")
            }
            if (!rule.environmentalImpact.isNullOrBlank()) {
                append("\nपर्यावरण प्रभाव: ${rule.environmentalImpact}\n")
            }
            append("\n(बॉटल ऑफ़लाइन सहायक — ${rule.source} पर आधारित)")
        }.trim()
        "ta" -> buildString {
            if (isHazardous) {
                append("⚠️ பாதுகாப்பு ஆலோசனை (அபாயகரமான கழிவு):\n$warning\n\n")
            }
            append("$itemName க்கான வழிகாட்டுதல்:\n")
            append("• பரிந்துரைக்கப்பட்ட அகற்றல்: ${rule.disposalMethod}\n")
            append("• இலக்கு தொட்டி: $targetBin\n")
            append("• மறுசுழற்சி செய்யக்கூடியதா: ${if (rule.isRecyclable) "ஆம்" else "இல்லை"}\n\n")
            if (rule.preparationInstructions.isNotBlank()) {
                append("தயாரிப்பு படிகள்:\n• ${rule.preparationInstructions}\n")
            }
            if (rule.prohibitedPathways.isNotEmpty()) {
                append("\nசெய்யக்கூடாதவை: ${rule.prohibitedPathways.joinToString("; ")}\n")
            }
            if (!rule.environmentalImpact.isNullOrBlank()) {
                append("\nசுற்றுச்சூழல் தாக்கம்: ${rule.environmentalImpact}\n")
            }
            append("\n(பாட்டில் ஆஃப்லைன் உதவியாளர் — ${rule.source} அடிப்படையிலானது)")
        }.trim()
        "mr" -> buildString {
            if (isHazardous) {
                append("⚠️ सुरक्षा सल्ला (घातक कचरा):\n$warning\n\n")
            }
            append("$itemName साठी मार्गदर्शन:\n")
            append("• शिफारस केलेली विल्हेवाट: ${rule.disposalMethod}\n")
            append("• लक्षित कुंडी: $targetBin\n")
            append("• पुनर्वापर करण्यायोग्य: ${if (rule.isRecyclable) "होय" else "नाही"}\n\n")
            if (rule.preparationInstructions.isNotBlank()) {
                append("तयारीच्या पायऱ्या:\n• ${rule.preparationInstructions}\n")
            }
            if (rule.prohibitedPathways.isNotEmpty()) {
                append("\nनिषिद्ध: ${rule.prohibitedPathways.joinToString("; ")}\n")
            }
            if (!rule.environmentalImpact.isNullOrBlank()) {
                append("\nपर्यावरणीय प्रभाव: ${rule.environmentalImpact}\n")
            }
            append("\n(बॉटल ऑफलाइन सहाय्यक — ${rule.source} वर आधारित)")
        }.trim()
        else -> buildString {
            if (isHazardous) {
                append("⚠️ SAFETY ADVISORY (Hazardous Waste):\n$warning\n\n")
            }
            append("Guidance for $itemName:\n")
            append("• Recommended Disposal: ${rule.disposalMethod}\n")
            append("• Target Bin: $targetBin\n")
            append("• Recyclable: ${if (rule.isRecyclable) "Yes" else "No"}\n\n")
            if (rule.preparationInstructions.isNotBlank()) {
                append("Preparation Steps:\n• ${rule.preparationInstructions}\n")
            }
            if (rule.prohibitedPathways.isNotEmpty()) {
                append("\nDo NOT: ${rule.prohibitedPathways.joinToString("; ")}\n")
            }
            if (!rule.environmentalImpact.isNullOrBlank()) {
                append("\nEco Note: ${rule.environmentalImpact}\n")
            }
            append("\n(Bottle Offline Assistant — grounded in ${rule.source})")
        }.trim()
        }
    }

    /**
     * Resolves pronouns "it / this / that / these / the item" to the concrete item name (display / LLM prompt use).
     * Indic tokens are replaced only when they stand alone, so "यहाँ" or "रहे" are never damaged.
     */
    fun resolvePronouns(query: String, itemName: String): String {
        val pronounRegex = Regex("\\b(it|this|that|these|the item|the object)\\b", RegexOption.IGNORE_CASE)
        val indicTokens = listOf(
            "यह वस्तु", "यह", "ఈ వస్తువు", "ఇది", "இந்த பொருள்", "இது", "ही वस्तू", "हे"
        )
        var text = query
        for (token in indicTokens) {
            val standalone = Regex("(?<![\\p{L}\\p{M}\\p{N}])" + Regex.escape(token) + "(?![\\p{L}\\p{M}\\p{N}])")
            text = standalone.replace(text, Regex.escapeReplacement(itemName))
        }
        return pronounRegex.replace(text, Regex.escapeReplacement(itemName))
    }

    /**
     * Detects condition keywords (broken, crushed, wet, greasy, empty, full) from text.
     */
    fun detectCondition(query: String): String? {
        val lower = query.lowercase()
        for ((key, synonyms) in conditionKeywords) {
            for (syn in synonyms) {
                val matches = if (syn.any { it.code > 127 }) {
                    lower.contains(syn)
                } else {
                    Regex("\\b$syn\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)
                }
                if (matches) return key
            }
        }
        return null
    }
}
