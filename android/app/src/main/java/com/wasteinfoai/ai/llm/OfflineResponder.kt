package com.wasteinfoai.ai.llm

import com.wasteinfoai.ai.rules.DisposalRule
import com.wasteinfoai.ai.rules.SafetyGuard
import com.wasteinfoai.core.language.CategoryLocalization
import java.util.Locale

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

        // Conversational greetings, identity & segregation overview inquiries
        if (isGreeting(query)) return buildGreetingResponse(lang, itemName)
        if (isGratitude(query)) return buildGratitudeResponse(lang)
        if (isIdentity(query)) return buildIdentityResponse(lang)
        if (isHelp(query)) return buildHelpResponse(lang)
        if (isFarewell(query)) return buildFarewellResponse(lang)
        if (isSegregationOverviewQuery(query)) return buildSegregationOverviewResponse(lang)

        if (rule == null) {
            return buildFriendlyFallbackResponse(lang, query)
        }

        val safety = SafetyGuard.evaluateSafety(context.category, isUncertain = false)
        val isHazardous = safety.isHazardous || rule.isHazardous
        val warning = rule.safetyWarnings.takeIf { it.isNotBlank() } ?: safety.primaryWarning
        val targetBin = CategoryLocalization.getBinColorDisplayName(rule.targetBinColor, lang)

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
        if (isHazardous && (intent == AssistantIntent.UPCYCLE || isBurnQuery(query) || query.contains(Regex("\\b(burn|fire|diy|reuse|craft|pot|upcycle)\\b", RegexOption.IGNORE_CASE)))) {
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

        // 4. Universal Anti-Burning Advice (Non-hazardous items)
        if (isBurnQuery(query)) {
            return when (lang) {
                "te" -> buildString {
                    append("⚠️ వద్దు, దయచేసి $itemName ని కాల్చవద్దు!\n\n")
                    append("వ్యర్థాలను బహిరంగంగా కాల్చడం వల్ల విషపూరిత వాయువులు, పొగ విడుదలవుతాయి. ఇది పర్యావరణానికి మరియు శ్వాసకోశ ఆరోగ్యానికి తీవ్ర హానికరం.\n\n")
                    append("సురక్షిత పారవేత విధానం: $targetBin లో (${rule.disposalMethod}) వేయండి.")
                }.trim()
                "hi" -> buildString {
                    append("⚠️ नहीं, कृपया $itemName को न जलाएं!\n\n")
                    append("कचरा खुले में जलाने से जहरीला धुआं और हानिकारक प्रदूषक निकलते हैं, जो स्वास्थ्य और पर्यावरण के लिए बेहद खतरनाक हैं।\n\n")
                    append("सुरक्षित निपटान: इसे $targetBin (${rule.disposalMethod}) में डालें।")
                }.trim()
                "ta" -> buildString {
                    append("⚠️ வேண்டாம், தயவுசெய்து $itemName ஐ எரிக்க வேண்டாம்!\n\n")
                    append("குப்பைகளை திறந்தவெளியில் எரிப்பதால் நச்சுப் புகையும் ஆபத்தான வாயுக்களும் காற்றில் பரவி உடல்நலக் கேடு விளைவிக்கும்.\n\n")
                    append("பாதுகாப்பான அகற்றல்: $targetBin (${rule.disposalMethod}) இல் ஒப்படைக்கவும்.")
                }.trim()
                "mr" -> buildString {
                    append("⚠️ नाही, कृपया $itemName जाळू नका!\n\n")
                    append("कचरा उघड्यावर जाळल्याने विषारी धूर आणि घातक वायू पसरतात, ज्यामुळे आरोग्याला आणि पर्यावरणाला मोठी हानी पोहोचते.\n\n")
                    append("सुरक्षित विल्हेवाट: $targetBin (${rule.disposalMethod}) मध्ये टाका.")
                }.trim()
                else -> buildString {
                    append("⚠️ No, please do not burn $itemName!\n\n")
                    append("Burning waste releases toxic fumes, particulates, and hazardous pollutants that harm your health and the environment.\n\n")
                    append("Safe Disposal: Place it in $targetBin (${rule.disposalMethod}).")
                }.trim()
            }
        }

        // 5. Battery Follow-up Questions
        if (isBatteryQuery(query)) {
            return when (lang) {
                "te" -> buildString {
                    append("🔋 $itemName బ్యాటరీ నిర్వహణ:\n\n")
                    append("మీ పరికరంలో తొలగించగల బ్యాటరీ ఉంటే, పారవేసే ముందు దాన్ని జాగ్రత్తగా తీసివేయండి.\n\n")
                    append("• బ్యాటరీలను పగలగొట్టడం, నొక్కడం లేదా కాల్చడం చేయవద్దు.\n")
                    append("• బ్యాటరీలను ప్రత్యేక ఇ-వ్యర్థాల సేకరణ కేంద్రం లేదా బ్యాటరీ డబ్బాలో ఇవ్వండి.\n\n")
                    append("పరికరంలోని మిగిలిన భాగాన్ని ${rule.disposalMethod} (${targetBin}) లో వేయండి.")
                }.trim()
                "hi" -> buildString {
                    append("🔋 $itemName बैटरी प्रबंधन:\n\n")
                    append("यदि आपके उपकरण में निकालने योग्य बैटरी है, तो फेंकने से पहले उसे अलग कर लें।\n\n")
                    append("• बैटरी को कभी भी तोड़ें, कुचलें या जलाएं नहीं।\n")
                    append("• इसे किसी अधिकृत बैटरी या ई-कचरा संग्रहण केंद्र पर जमा करें।\n\n")
                    append("उपकरण के बाकी हिस्से को ${rule.disposalMethod} ($targetBin) में निपटाएं।")
                }.trim()
                "ta" -> buildString {
                    append("🔋 $itemName பேட்டரி கையாளுதல்:\n\n")
                    append("உங்கள் சாதனத்தில் கழற்றக்கூடிய பேட்டரி இருந்தால், அதை அகற்றுவதற்கு முன் பேட்டரியை வெளியே எடுக்கவும்.\n\n")
                    append("• பேட்டரியை உடைக்கவோ, அழுத்தவோ அல்லது எரிக்கவோ கூடாது.\n")
                    append("• அதை பிரத்யேக பேட்டரி அல்லது மின்-கழிவு சேகரிப்பு மையத்தில் கொடுக்கவும்.\n\n")
                    append("மீதமுள்ள சாதனத்தை ${rule.disposalMethod} ($targetBin) இல் ஒப்படைக்கவும்.")
                }.trim()
                "mr" -> buildString {
                    append("🔋 $itemName बॅटरी व्यवस्थापन:\n\n")
                    append("तुमच्या उपकरणात काढता येण्याजोगी बॅटरी असल्यास, विल्हेवाट लावण्यापूर्वी ती वेगळी करा.\n\n")
                    append("• बॅटरी कधीही फोडू नका, दाबू नका किंवा जाळू नका.\n")
                    append("• ती अधिकृत बॅटरी किंवा ई-कचरा संकलन केंद्रात जमा करा.\n\n")
                    append("बाकी उपकरण ${rule.disposalMethod} ($targetBin) मध्ये टाका.")
                }.trim()
                else -> buildString {
                    append("🔋 Battery Guidance for $itemName:\n\n")
                    append("If your device has a removable battery, take it out first before disposing of the device.\n\n")
                    append("• Never puncture, crush, or burn batteries—they pose severe fire and chemical burn hazards.\n")
                    append("• Drop batteries off at an authorized battery recycling depot or e-waste collection point.\n\n")
                    append("Dispose of the remaining device via ${rule.disposalMethod} ($targetBin).")
                }.trim()
            }
        }

        // 6. Decomposition Time Follow-up
        if (isDecomposeQuery(query)) {
            return when (lang) {
                "te" -> buildString {
                    append("⏳ $itemName కుళ్ళిపోవడానికి పట్టే సమయం:\n\n")
                    if (!rule.environmentalImpact.isNullOrBlank()) {
                        append("పర్యావరణ సమాచారం: ${rule.environmentalImpact}\n\n")
                    }
                    append("• సేంద్రీయ వ్యర్థాలు కంపోస్ట్‌లో కొన్ని వారాల నుండి నెలల్లో సహజంగా కలిసిపోతాయి.\n")
                    append("• ప్లాస్టిక్ మరియు ఎలక్ట్రానిక్ వ్యర్థాలు విచ్ఛిన్నం కావడానికి వందల సంవత్సరాలు పడుతుంది.\n\n")
                    append("సరియైన పారవేత కోసం: $targetBin (${rule.disposalMethod}).")
                }.trim()
                "hi" -> buildString {
                    append("⏳ $itemName के विघटन का समय:\n\n")
                    if (!rule.environmentalImpact.isNullOrBlank()) {
                        append("पर्यावरण प्रभाव: ${rule.environmentalImpact}\n\n")
                    }
                    append("• जैविक कचरा खाद में कुछ हफ़्तों से महीनों में पूरी तरह गल जाता है।\n")
                    append("• प्लास्टिक और ई-कचरे को प्रकृति में विघटित होने में सैकड़ों साल लगते हैं।\n\n")
                    append("उचित निपटान: $targetBin (${rule.disposalMethod})।")
                }.trim()
                "ta" -> buildString {
                    append("⏳ $itemName மட்கும் காலம்:\n\n")
                    if (!rule.environmentalImpact.isNullOrBlank()) {
                        append("சுற்றுச்சூழல் தாக்கம்: ${rule.environmentalImpact}\n\n")
                    }
                    append("• மக்கும் கழிவுகள் உரத்தொட்டியில் சில வாரங்கள் முதல் மாதங்களுக்குள் மட்கிவிடும்.\n")
                    append("• பிளாஸ்டிக் மற்றும் மின்னணுக் கழிவுகள் மட்க நூற்றுக்கணக்கான ஆண்டுகள் ஆகும்.\n\n")
                    append("சரியான அகற்றல்: $targetBin (${rule.disposalMethod}).")
                }.trim()
                "mr" -> buildString {
                    append("⏳ $itemName विघटन कालावधी:\n\n")
                    if (!rule.environmentalImpact.isNullOrBlank()) {
                        append("पर्यावरणीय प्रभाव: ${rule.environmentalImpact}\n\n")
                    }
                    append("• सेंद्रिय कचरा खत खड्ड्यात काही आठवड्यांत किंवा महिन्यांत नैसर्गिकरित्या कुजतो.\n")
                    append("• प्लॅस्टिक आणि ई-कचरा विघटित होण्यासाठी शेकडो वर्षे लागतात.\n\n")
                    append("योग्य विल्हेवाट: $targetBin (${rule.disposalMethod}).")
                }.trim()
                else -> buildString {
                    append("⏳ Decomposition Information for $itemName:\n\n")
                    if (!rule.environmentalImpact.isNullOrBlank()) {
                        append("Environmental Impact: ${rule.environmentalImpact}\n\n")
                    }
                    append("• Organic items decompose naturally in compost within a few weeks to months.\n")
                    append("• Plastics and e-waste can take 400 to 1,000 years to break down in landfills.\n\n")
                    append("Target Disposal: $targetBin (${rule.disposalMethod}).")
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
                val emoji = getBinEmoji(rule.targetBinColor)
                when (lang) {
                    "te" -> buildString {
                        append("$itemName ని ఖచ్చితంగా ఈ డబ్బాలో వేయాలి:\n\n")
                        append("$emoji కేటాయించిన డబ్బా: $targetBin\n")
                        append("• పారవేత విధానం: ${rule.disposalMethod}\n")
                        append("• రీసైకిల్ చేయగలదా: ${if (rule.isRecyclable) "అవును, రీసైకిల్ అవుతుంది" else "కాదు"}\n\n")
                        if (rule.acceptedPathways.isNotEmpty()) {
                            append("స్వీకరించబడే ప్రదేశాలు:\n${rule.acceptedPathways.joinToString("\n") { "• $it" }}\n\n")
                        }
                        if (rule.prohibitedPathways.isNotEmpty()) {
                            append("చేయకూడనివి: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                        }
                        if (conditionAdvice != null) {
                            append("స్థితి సమాచారం ($detectedCondition): $conditionAdvice\n\n")
                        }
                        append("మీ పరిసరాలను పరిశుభ్రంగా ఉంచడంలో భాగస్వామ్యం అయినందుకు ధన్యవాదాలు! 😊")
                    }.trim()
                    "hi" -> buildString {
                        append("$itemName को सही तरीके से इस डिब्बे में डालें:\n\n")
                        append("$emoji लक्षित डिब्बा: $targetBin\n")
                        append("• निपटान मार्ग: ${rule.disposalMethod}\n")
                        append("• पुनर्चक्रण योग्य: ${if (rule.isRecyclable) "हाँ, पुनर्चक्रण योग्य है" else "नहीं"}\n\n")
                        if (rule.acceptedPathways.isNotEmpty()) {
                            append("स्वीकृत केंद्र:\n${rule.acceptedPathways.joinToString("\n") { "• $it" }}\n\n")
                        }
                        if (rule.prohibitedPathways.isNotEmpty()) {
                            append("क्या न करें: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                        }
                        if (conditionAdvice != null) {
                            append("स्थिति नोट ($detectedCondition): $conditionAdvice\n\n")
                        }
                        append("स्वच्छता में सहयोग देने के लिए धन्यवाद! 😊")
                    }.trim()
                    "ta" -> buildString {
                        append("$itemName ஐ இந்த தொட்டியில் சரியாக போடவும்:\n\n")
                        append("$emoji இலக்கு தொட்டி: $targetBin\n")
                        append("• அகற்றும் வழிமுறை: ${rule.disposalMethod}\n")
                        append("• மறுசுழற்சி செய்யக்கூடியதா: ${if (rule.isRecyclable) "ஆம்" else "இல்லை"}\n\n")
                        if (rule.acceptedPathways.isNotEmpty()) {
                            append("ஏற்றுக்கொள்ளப்படும் இடங்கள்:\n${rule.acceptedPathways.joinToString("\n") { "• $it" }}\n\n")
                        }
                        if (rule.prohibitedPathways.isNotEmpty()) {
                            append("செய்யக்கூடாதவை: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                        }
                        if (conditionAdvice != null) {
                            append("நிலை குறிப்பு ($detectedCondition): $conditionAdvice\n\n")
                        }
                        append("சுற்றுப்புற தூய்மைக்கு ஒத்துழைத்ததற்கு நன்றி! 😊")
                    }.trim()
                    "mr" -> buildString {
                        append("$itemName ची योग्य विल्हेवाट या कुंडीत करावी:\n\n")
                        append("$emoji लक्षित कुंडी: $targetBin\n")
                        append("• विल्हेवाट मार्ग: ${rule.disposalMethod}\n")
                        append("• पुनर्वापर करण्यायोग्य: ${if (rule.isRecyclable) "होय" else "नाही"}\n\n")
                        if (rule.acceptedPathways.isNotEmpty()) {
                            append("स्वीकार्य ठिकाणे:\n${rule.acceptedPathways.joinToString("\n") { "• $it" }}\n\n")
                        }
                        if (rule.prohibitedPathways.isNotEmpty()) {
                            append("काय करू नये: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                        }
                        if (conditionAdvice != null) {
                            append("स्थिती नोंद ($detectedCondition): $conditionAdvice\n\n")
                        }
                        append("परिसर स्वच्छ ठेवण्यात सहकार्य केल्याबद्दल धन्यवाद! 😊")
                    }.trim()
                    else -> buildString {
                        append("Here is where $itemName belongs:\n\n")
                        append("$emoji Target Bin: $targetBin\n")
                        append("• Disposal Pathway: ${rule.disposalMethod}\n")
                        append("• Recyclable: ${if (rule.isRecyclable) "Yes, highly recyclable" else "No"}\n\n")
                        if (rule.acceptedPathways.isNotEmpty()) {
                            append("Accepted At:\n${rule.acceptedPathways.joinToString("\n") { "• $it" }}\n\n")
                        }
                        if (rule.prohibitedPathways.isNotEmpty()) {
                            append("Please avoid: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                        }
                        if (conditionAdvice != null) {
                            append("Condition Note ($detectedCondition): $conditionAdvice\n\n")
                        }
                        append("Thank you for segregating your waste responsibly! 😊")
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
                    buildGeneralExplanation(itemName, rule, isHazardous, warning, lang, isDetailRequested(query))
                }
            }
        }
    }

    private fun isGreeting(query: String): Boolean {
        val q = query.trim().lowercase()
        val words = listOf("hi", "hello", "hey", "good morning", "good afternoon", "good evening", "greetings")
        val indic = listOf("నమస్కారం", "హలో", "హాయ్", "బాగున్నారా", "नमस्ते", "नमस्कार", "हेलो", "हाय", "வணக்கம்", "हॅलो")
        return words.any { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(q) } ||
                indic.any { q.contains(it) }
    }

    private fun isGratitude(query: String): Boolean {
        val q = query.trim().lowercase()
        val words = listOf("thank you", "thanks", "thank u", "thx")
        val indic = listOf("ధన్యవాదాలు", "థాంక్స్", "धन्यवाद", "शुक्रिया", "நன்றி", "आभार")
        return words.any { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(q) } ||
                indic.any { q.contains(it) }
    }

    private fun isIdentity(query: String): Boolean {
        val q = query.trim().lowercase()
        val phrases = listOf("who are you", "what is your name", "who made you", "what are you")
        val indic = listOf("మీరు ఎవరు", "నీ పేరేంటి", "నువ్వు ఎవరు", "నీ పేరు ఏమిటి", "आप कौन हैं", "तुम्हारा नाम क्या है", "तुम कौन हो", "நீங்கள் யார்", "உன் பெயர் என்ன", "तू कोण आहेस")
        return phrases.any { q.contains(it) } || indic.any { q.contains(it) }
    }

    private fun isHelp(query: String): Boolean {
        val q = query.trim().lowercase()
        val phrases = listOf("help", "what can you do", "how to use", "how do you work", "commands")
        val indic = listOf("సహాయం", "నువ్వు ఏమి చేయగలవు", "ఎలా ఉపయోగించాలి", "मदद", "आप क्या कर सकते हैं", "उपयोग कैसे करें", "உதவி", "மदत")
        return phrases.any { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(q) } || indic.any { q.contains(it) }
    }

    private fun isFarewell(query: String): Boolean {
        val q = query.trim().lowercase()
        val words = listOf("bye", "goodbye", "see you", "cya")
        val indic = listOf("టాటా", "వెళ్లొస్తా", "अलविदा", "बाय", "பிரியாவிடை")
        return words.any { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(q) } || indic.any { q.contains(it) }
    }

    private fun buildGreetingResponse(lang: String, itemName: String): String = when (lang) {
        "te" -> "నమస్కారం! నేను బాటిల్ AI, మీ పర్యావరణ వ్యర్థాల సహాయకుడిని. ఈరోజు $itemName పారవేత, రీసైక్లింగ్ లేదా శుభ్రపరచడంలో నేను మీకు ఎలా సహాయపడగలను?"
        "hi" -> "नमस्ते! मैं बॉटल AI हूँ, आपका पर्यावरण और पुनर्चक्रण सहायक। $itemName के सुरक्षित निपटान, पुनर्चक्रण या सफाई में मैं आपकी क्या मदद करूँ?"
        "ta" -> "வணக்கம்! நான் பாட்டில் AI, உங்கள் சுற்றுச்சூழல் கழிவு உதவியாளர். $itemName ஐ பாதுகாப்பாக நிர்வகிக்க அல்லது மறுசுழற்சி செய்ய நான் எவ்வாறு உதவ முடியும்?"
        "mr" -> "नमस्कार! मी बॉटल AI आहे, तुमचा पर्यावरण सहाय्यक. $itemName ची योग्य विल्हेवाट, पुनर्वापर किंवा स्वच्छतेमध्ये मी कशी मदत करू?"
        else -> "Hello! I'm Bottle AI, your friendly waste & recycling assistant. How can I help you manage, clean, or dispose of $itemName today?"
    }

    private fun buildGratitudeResponse(lang: String): String = when (lang) {
        "te" -> "మీకు స్వాగతం! బాధ్యతాయుతంగా వ్యర్థాలను వేరు చేయడం మన పర్యావరణానికి ఎంతో మేలు చేస్తుంది. ఇంకేమైనా సందేహాలు ఉంటే అడగండి!"
        "hi" -> "आपका बहुत-बहुत स्वागत है! जिम्मेदारी से कचरा अलग करना पर्यावरण के लिए बहुत महत्वपूर्ण है। कोई अन्य सवाल हो तो बेझिझक पूछें!"
        "ta" -> "மகிழ்ச்சி! கழிவுகளை பொறுப்புடன் பிரிப்பது நமது பூமிக்கு மிகப்பெரிய நன்மையைத் தரும். மேலும் கேள்விகள் இருந்தால் கேளுங்கள்!"
        "mr" -> "आपले स्वागत आहे! कचऱ्याचे योग्य वर्गीकरण आपल्या पर्यावरणासाठी मोलाचे आहे. इतर काही प्रश्न असल्यास नक्की विचारा!"
        else -> "You're very welcome! Every small step towards proper waste segregation makes a big difference for our environment. Let me know if you have any other questions!"
    }

    private fun buildIdentityResponse(lang: String): String = when (lang) {
        "te" -> "నేను బాటిల్ AI, మీ ఆఫ్‌లైన్ పర్యావరణ వ్యర్థాల సహాయకుడిని! వస్తువులను ఏ డబ్బాలో వేయాలో, ఎలా రీసైకిల్ చేయాలో మరియు ప్రమాదకర వ్యర్థాలను ఎలా సురక్షితంగా నిర్వహించాలో నేను మీకు మార్గనిర్దేశం చేస్తాను."
        "hi" -> "मैं बॉटल AI हूँ, आपका ऑफ़लाइन पर्यावरण और पुनर्चक्रण सहायक! मैं आपको कचरे की सही डिब्बे में पहचान, पुनर्चक्रण और सुरक्षा नियमों में मार्गदर्शन करता हूँ।"
        "ta" -> "நான் பாட்டில் AI, உங்கள் சுற்றுச்சூழல் கழிவு வழிகாட்டி! குப்பைகளை சரியான தொட்டியில் போடவும், பாதுகாப்பாக மறுசுழற்சி செய்யவும் நான் உதவுகிறேன்."
        "mr" -> "मी बॉटल AI आहे, तुमचा पर्यावरण कचरा व्यवस्थापन सहाय्यक! कचऱ्याचे योग्य कुंडीत वर्गीकरण आणि पुनर्वापरात मी मार्गदर्शन करतो."
        else -> "I'm Bottle AI, your on-device environmental sanitation and recycling assistant! I help you identify waste, choose the right municipal bins, learn upcycling ideas, and handle hazardous materials safely."
    }

    private fun buildHelpResponse(lang: String): String = when (lang) {
        "te" -> "నేను మీకు ఈ క్రింది విధాలుగా సహాయపడగలను:\n• సరైన రంగు డబ్బాను ఎంచుకోవడం (రీసైకిల్ కోసం నీలం, తడి చెత్త కోసం ఆకుపచ్చ, ప్రమాదకరమైన వాటికి ఎరుపు, ఇ-వ్యర్థాలకు నారింజ).\n• రీసైక్లింగ్ చేసే ముందు శుభ్రపరిచే సూచనలు.\n• సృజనాత్మక పునర్వినియోగ ఆలోచనలు.\n• ప్రమాదకర వ్యర్థాల సురక్షిత నిర్వహణ."
        "hi" -> "मैं आपकी इन मामलों में मदद कर सकता हूँ:\n• सही रंग का डिब्बा चुनना (पुनर्चक्रण के लिए नीला, गीले कचरे के लिए हरा, खतरनाक के लिए लाल, ई-कचरे के लिए नारंगी)।\n• फेंकने से पहले सफाई और तैयारी के टिप्स।\n• रचनात्मक पुनः उपयोग के विचार।\n• खतरनाक या टूटी हुई वस्तुओं की सुरक्षा।"
        "ta" -> "நான் உங்களுக்கு உதவக்கூடிய வழிகள்:\n• சரியான வண்ணத் தொட்டியைத் தேர்ந்தெடுப்பது.\n• மறுசுழற்சிக்கு முன் சுத்தம் செய்யும் குறிப்புகள்.\n• பயனுள்ள மறுபயன்பாட்டு யோசனைகள்.\n• அபாயகரமான பொருட்களுக்கான பாதுகாப்பு நெறிமுறைகள்."
        "mr" -> "मी आपल्याला पुढील प्रकारे मदत करू शकतो:\n• योग्य रंगाची कुंडी निवडणे.\n• पुनर्वापरापूर्वी स्वच्छतेच्या टिप्स.\n• सर्जनशील पुनर्वापर कल्पना.\n• घातक वस्तूंची सुरक्षित हाताळणी."
        else -> "I can help you in several ways:\n• Find the right colored bin (Blue for dry recyclables, Green for wet organic, Red for hazardous, Orange for e-waste).\n• Give cleaning and preparation steps before recycling.\n• Suggest creative DIY upcycling ideas.\n• Guide safe handling for broken or hazardous items."
    }

    private fun buildFarewellResponse(lang: String): String = when (lang) {
        "te" -> "వెళ్లొస్తాను! మన భూమిని కాపాడుతున్నందుకు ధన్యవాదాలు. బాధ్యతాయుతంగా వ్యర్థాలను వేరు చేయండి!"
        "hi" -> "अलविदा! हमारे पर्यावरण का ध्यान रखने के लिए धन्यवाद। जिम्मेदारी से कचरा अलग करते रहें!"
        "ta" -> "பிரியாவிடை! நமது பூமியை பாதுகாப்பதற்கு நன்றி. தொடர்ந்து கழிவுகளை முறையாகப் பிரிக்கவும்!"
        "mr" -> "निरोप! पर्यावरणाची काळजी घेतल्याबद्दल धन्यवाद. कचऱ्याचे योग्य वर्गीकरण करत राहा!"
        else -> "Goodbye! Thank you for taking care of our planet. Keep segregating waste responsibly!"
    }

    private fun isDetailRequested(query: String): Boolean {
        val q = query.lowercase()
        return q.contains(Regex("\\b(detail|details|step|steps|explain|comprehensive|full|more|how exactly)\\b")) ||
                q.contains("వివరంగా") || q.contains("దశలు") || q.contains("వివరించు") ||
                q.contains("विस्तार") || q.contains("चरण") ||
                q.contains("விளக்கம்") || q.contains("படிகள்") ||
                q.contains("सविस्तर") || q.contains("पायऱ्या")
    }

    private fun isBurnQuery(query: String): Boolean {
        val q = query.lowercase()
        return q.contains(Regex("\\b(burn|fire|incinerat|smoke)\\b")) ||
                q.contains("जलाना") || q.contains("आग") ||
                q.contains("కాల్చ") || q.contains("మంట") ||
                q.contains("எரி") || q.contains("தீ") ||
                q.contains("जाळ")
    }

    private fun isBatteryQuery(query: String): Boolean {
        val q = query.lowercase()
        return q.contains(Regex("\\b(battery|batteries|cell|cells)\\b")) ||
                q.contains("बैटरी") || q.contains("బ్యాటరీ") || q.contains("பேட்டரி") || q.contains("बॅटरी")
    }

    private fun isDecomposeQuery(query: String): Boolean {
        val q = query.lowercase()
        return q.contains(Regex("\\b(decompose|decay|rot|break down|how long)\\b")) ||
                q.contains("गलने") || q.contains("सड़ने") ||
                q.contains("కుళ్ళిపోవడానికి") || q.contains("మட்க") ||
                q.contains("विघटन") || q.contains("कुजण्यासाठी")
    }

    private fun getBinEmoji(binColor: String): String = when (binColor.uppercase(Locale.US)) {
        "GREEN" -> "🟢"
        "BLUE" -> "🔵"
        "ORANGE" -> "🟠"
        "RED" -> "🔴"
        else -> "⚫"
    }

    private fun isSegregationOverviewQuery(query: String): Boolean {
        val q = query.trim().lowercase()
        val enPhrases = listOf(
            "segregat", "separate", "all bin", "bin color", "bin colour", "which bin", "how to segregate",
            "waste management", "waste types", "types of waste", "color code", "colour code", "rules", "4 bin", "5 bin"
        )
        val indic = listOf(
            "వేరుచేయడం", "ఎలా వేరు చేయాలి", "డబ్బాల రంగులు", "అన్ని డబ్బాలు", "వ్యర్థాల రకాలు", "డబ్బాల",
            "कचरा अलग", "पृथक्करण", "डिब्बों के रंग", "कचरे के प्रकार", "नियम", "अलग कैसे",
            "தரம் பிரித்தல்", "தொட்டிகளின் நிறங்கள்", "கழிவு வகைகள்",
            "कचरा वर्गीकरण", "कचरा कसा वेगळा", "कुंड्यांचे रंग", "कचऱ्याचे प्रकार"
        )
        return enPhrases.any { q.contains(it) } || indic.any { q.contains(it) }
    }

    private fun buildFriendlyFallbackResponse(lang: String, query: String): String = when (lang) {
        "te" -> """
            నమస్కారం! మీరు అడిగిన వస్తువు గురించి వివరాలు చూస్తున్నాను.

            మన దేశంలో సాధారణ వ్యర్థాల వర్గీకరణ పద్ధతి ఇలా ఉంటుంది:
            🟢 ఆకుపచ్చ డబ్బా (Green): వంటింటి తడి వ్యర్థాలు, పండ్లు, కూరగాయల తొక్కలు.
            🔵 నీలం డబ్బా (Blue): శుభ్రమైన ప్లాస్టిక్, కాగితం, అట్టపెట్టెలు, గాజు సీసాలు, టిన్లు.
            🟠 నారింజ డబ్బా (Orange): పాత మొబైల్స్, ఛార్జర్లు, వైర్లు, ఎలక్ట్రానిక్స్.
            🔴 ఎరుపు డబ్బా (Red): బ్యాటరీలు, మందులు, శానిటరీ ప్యాడ్‌లు, విష రసాయనాలు.
            ⚫ నలుపు డబ్బా (Black): పునర్వినియోగించలేని నేల దుమ్ము, థర్మాకోల్.

            మీరు పారవేయాలనుకుంటున్న నిర్దిష్ట వస్తువు పేరు చెప్పండి లేదా కెమెరాతో స్కాన్ చేయండి, నేను ఖచ్చితంగా మార్గదర్శకత్వం ఇస్తాను! 😊
        """.trimIndent()
        "hi" -> """
            नमस्ते! मैं आपकी वस्तु की सही जानकारी देख रहा हूँ।

            कचरा अलग करने के मुख्य नियम इस प्रकार हैं:
            🟢 हरा डिब्बा (Green): गीला कचरा, सब्जी-फलों के छिलके, बचा खाना।
            🔵 नीला डिब्बा (Blue): सूखा कचरा जैसे प्लास्टिक, कागज, गत्ते, धातु के डिब्बे, कांच।
            🟠 नारंगी डिब्बा (Orange): पुराने मोबाइल, चार्जर, तार, इलेक्ट्रॉनिक सामान।
            🔴 लाल डिब्बा (Red): पुरानी बैटरियां, दवाइयां, सैनिटरी कचरा, पेंट, रसायन।
            ⚫ काला डिब्बा (Black): झाड़ू की धूल, थर्माकोल, न गलने वाला अक्रिय कचरा।

            कृपया उस वस्तु का नाम बताएं या फोटो स्कैन करें, मैं सटीक तरीका बताऊंगा! 😊
        """.trimIndent()
        "ta" -> """
            வணக்கம்! நீங்கள் குறிப்பிட்ட பொருளைப் பற்றிய வழிகாட்டல்:

            பொதுவான கழிவு தரம் பிரித்தல் முறைகள்:
            🟢 பச்சை தொட்டி (Green): ஈரக் கரிமக் கழிவு, காய்கறித் தோல்கள், உணவு.
            🔵 நீல தொட்டி (Blue): உலர் பிளாஸ்டிக், காகிதம், அட்டை, கண்ணாடி, தகர டப்பா.
            🟠 ஆரஞ்சு தொட்டி (Orange): பழைய எலக்ட்ரானிக்ஸ், போன், சார்ஜர், ஒயர்.
            🔴 சிவப்பு தொட்டி (Red): பேட்டரிகள், மருந்துகள், சானிட்டரி கழிவுகள், ரசாயனம்.
            ⚫ கருப்பு தொட்டி (Black): கூட்டப்பட்ட தூசு, தெர்மாகோல்.

            நீங்கள் அகற்ற விரும்பும் பொருளைப் பற்றி கூறுங்கள் அல்லது ஸ்கேன் செய்யுங்கள், நான் உதவுகிறேன்! 😊
        """.trimIndent()
        "mr" -> """
            नमस्कार! तुम्ही विचारलेल्या वस्तूची माहिती खालीलप्रमाणे:

            कचरा वर्गीकरणाचे मुख्य नियम:
            🟢 हिरवी कुंडी (Green): ओला कचरा, फळे-भाज्यांची साले, उरलेले अन्न.
            🔵 निळी कुंडी (Blue): सुका पुनर्वापर कचरा (प्लास्टिक, कागद, पुठ्ठा, काच, धातू).
            🟠 नारिंगी कुंडी (Orange): जुने इलेक्ट्रॉनिक्स, मोबाईल, चार्जर, वायर.
            🔴 लाल कुंडी (Red): बॅटऱ्या, औषधे, सॅनिटरी कचरा, रसायने.
            ⚫ काळी कुंडी (Black): केर/धूळ, थर्माकोल, न कुजणारा कचरा.

            कृपया वस्तूचे नाव सांगा किंवा फोटो स्कॅन करा, मी योग्य मार्गदर्शन देईन! 😊
        """.trimIndent()
        else -> """
            Hello! Here is a simple guide on how waste should be segregated:

            🟢 Green Bin: Wet organic kitchen scraps, fruit/vegetable peels, leftover food.
            🔵 Blue Bin: Clean dry recyclables like plastic bottles, paper, cardboard, cans, glass jars.
            🟠 Orange Bin: E-waste like old phones, wires, chargers, headphones, gadgets.
            🔴 Red Bin: Hazardous & sanitary waste like batteries, medicines, paint, diapers, sanitary pads.
            ⚫ Black Bin: Inert non-recyclable refuse like floor sweepings and styrofoam.

            Tell me the exact name of your item or scan it, and I'll give you step-by-step guidance! 😊
        """.trimIndent()
    }

    private fun buildSegregationOverviewResponse(lang: String): String = when (lang) {
        "te" -> """
            మన ఇల్లు మరియు సమాజంలో చెత్తను సరిగ్గా వేరు చేసే 5 ప్రధాన రంగు డబ్బాల మార్గదర్శకత్వం:

            🟢 1. ఆకుపచ్చ డబ్బా (Green Bin) — తడి / సేంద్రీయ వ్యర్థాలు
            • వేయవలసినవి: కూరగాయలు మరియు పండ్ల తొక్కలు, మిగిలిన ఆహారం, టీ పొడి, పూలు, ఆకులు.
            • వేయకూడనివి: ప్లాస్టిక్ కవర్లు, పాల ప్యాకెట్లు, రబ్బర్ బ్యాండ్లు అస్సలు వేయవద్దు.
            • ఎందుకు?: ఈ తడి చెత్త కుళ్ళిపోయి రైతులకు మేలైన సేంద్రీయ ఎరువుగా లేదా బయోగ్యాస్‌గా మారుతుంది!

            🔵 2. నీలం డబ్బా (Blue Bin) — పొడి పునర్వినియోగ వ్యర్థాలు
            • వేయవలసినవి: ప్లాస్టిక్ సీసాలు, వార్తాపత్రికలు, పుస్తకాలు, డెలివరీ అట్టపెట్టెలు, గాజు సీసాలు, కూల్‌డ్రింక్ టిన్లు.
            • సులభమైన చిట్కా: ఆహారం ఉన్న డబ్బాలను కొద్దిగా నీటితో కడిగి ఆరబెట్టి వేయండి.
            • ఎందుకు?: ఇవి రీసైక్లింగ్ ఫ్యాక్టరీలకు వెళ్లి కొత్త వస్తువులుగా తయారవుతాయి. చెట్లను, పర్యావరణాన్ని కాపాడతాయి!

            🟠 3. నారింజ డబ్బా (Orange Bin) — ఇ-వ్యర్థాలు / ఎలక్ట్రానిక్స్
            • వేయవలసినవి: పాత మొబైల్స్, ఛార్జర్లు, వైర్లు, హెడ్‌ఫోన్లు, కంప్యూటర్ మౌస్, కీబోర్డులు, రిమోట్లు.
            • సులభమైన చిట్కా: పరికరాలను పగలగొట్టవద్దు, కాల్చవద్దు. 
            • ఎందుకు?: వీటిలో రాగి, వెండి వంటి విలువైన లోహాలతో పాటు సీసం, పాదరసం వంటి విష రసాయనాలు ఉంటాయి. సరైన రీసైక్లింగ్ వల్ల భూగర్భ జలాలు కలుషితం కావు.

            🔴 4. ఎరుపు డబ్బా (Red Bin) — ప్రమాదకర & శానిటరీ వ్యర్థాలు
            • వేయవలసినవి: వాడిన బ్యాటరీలు, ట్యూబ్‌లైట్లు/బల్బులు, రంగుల డబ్బాలు, మందులు, సిరంజిలు, శానిటరీ ప్యాడ్‌లు & డైపర్లు.
            • సులభమైన చిట్కా: శానిటరీ వ్యర్థాలను న్యూస్‌పేపర్‌లో చుట్టి వేయండి; బ్యాటరీల చివరలకు టేప్ వేయండి.
            • ఎందుకు?: పారిశుద్ధ్య కార్మికుల ఆరోగ్యాన్ని కాపాడుతుంది మరియు అంటువ్యాధులు, అగ్ని ప్రమాదాలను నివారిస్తుంది.

            ⚫ 5. నలుపు డబ్బా (Black Bin) — పునర్వినియోగించలేని నిష్క్రియ వ్యర్థాలు
            • వేయవలసినవి: ఊడ్చిన దుమ్ము, పగిలిన సిరామిక్ కప్పులు, థర్మాకోల్, బాగా మురికిగా ఉన్న వ్యర్థాలు.
            • ఎందుకు?: ఇది శాస్త్రీయ ల్యాండ్‌ఫిల్‌కు పంపబడుతుంది.

            ఇంట్లోనే ఈ చిన్న విభజన చేయడం వల్ల మన ఊరు శుభ్రంగా, మన కుటుంబం ఆరోగ్యంగా ఉంటుంది! ఏదైనా వస్తువు గురించి సందేహం ఉంటే అడగండి! 😊
        """.trimIndent()

        "hi" -> """
            कचरा अलग करने (पृथक्करण) की आसान और सही 5 डिब्बों की मार्गदर्शिका:

            🟢 1. हरा डिब्बा (Green Bin) — गीला जैविक कचरा
            • क्या डालें: फल और सब्जियों के छिलके, बचा हुआ खाना, चायपत्ती, अंडे के छिलके, बगीचे के पत्ते।
            • क्या न डालें: प्लास्टिक थैली, दूध की थैली या रबर बैंड बिल्कुल न डालें।
            • क्यों?: यह कचरा सड़कर उत्तम प्राकृतिक खाद या बायोगैस बनाता है!

            🔵 2. नीला डिब्बा (Blue Bin) — सूखा पुनर्चक्रण योग्य कचरा
            • क्या डालें: प्लास्टिक की बोतलें, गत्ते के डिब्बे, अखबार, किताबें, कांच की बोतलें, धातु के डिब्बे/टिन।
            • आसान टिप: खाद्य डिब्बों को थोड़ा पानी से धोकर सुखा लें।
            • क्यों?: यह रीसाइक्लिंग कारखानों में जाकर नए उत्पाद बनाता है और पेड़ों को बचाता है!

            🟠 3. नारंगी डिब्बा (Orange Bin) — ई-कचरा / इलेक्ट्रॉनिक्स
            • क्या डालें: पुराने मोबाइल, चार्जर, तार, ईयरफोन, रिमोट, माउस, कीबोर्ड, खराब गैजेट्स।
            • आसान टिप: उपकरणों को तोड़ें या जलाएं नहीं।
            • क्यों?: इनमें तांबा और कीमती धातुएं होती हैं, लेकिन सीसा व पारा जैसे जहर भी होते हैं। सही रीसाइक्लिंग से पानी और जमीन सुरक्षित रहती है।

            🔴 4. लाल डिब्बा (Red Bin) — खतरनाक व स्वच्छता अपशिष्ट
            • क्या डालें: पुरानी बैटरियां, ट्यूबलाइट/बल्ब, पेंट, कीटनाशक, दवाइयां, सैनिटरी पैड व डायपर।
            • आसान टिप: सैनिटरी कचरे को अखबार में लपेट कर डालें; बैटरी के सिरों पर टेप चिपकाएं।
            • क्यों?: सफाई कर्मचारियों को बीमारियों से बचाता है और आग के खतरे को रोकता है।

            ⚫ 5. काला डिब्बा (Black Bin) — अक्रिय / न गलने वाला कचरा
            • क्या डालें: झाड़ू की धूल, टूटी चीनी मिट्टी/सिरेमिक, थर्माकोल, अत्यधिक तैलीय कचरा।
            • क्यों?: यह सीधे वैज्ञानिक लैंडफिल में सुरक्षित रूप से निस्तारित किया जाता है।

            घर पर कचरा अलग करना बहुत आसान है और हमारे पर्यावरण को स्वच्छ रखता है! किसी भी वस्तु के बारे में बेझिझक पूछें! 😊
        """.trimIndent()

        "ta" -> """
            வீட்டிலும் பணியிடத்திலும் கழிவுகளை எளிதாக தரம் பிரிக்கும் 5 வண்ணத் தொட்டிகளின் வழிகாட்டி:

            🟢 1. பச்சை தொட்டி (Green Bin) — ஈரக் கரிமக் கழிவுகள்
            • போட வேண்டியவை: பழங்கள், காய்கறித் தோல்கள், எஞ்சிய உணவு, தேயிலை கழிவுகள், இலைகள்.
            • போடக்கூடாதவை: பிளாஸ்டிக் பைகள், பால் கவர்கள் அல்லது ரப்பர்களைப் போடாதீர்கள்.
            • ஏன்?: இது மட்கி இயற்கை உரமாக அல்லது உயிர்வாயுவாக மாறுகிறது!

            🔵 2. நீல தொட்டி (Blue Bin) — உலர் மறுசுழற்சிக் கழிவுகள்
            • போட வேண்டியவை: பிளாஸ்டிக் பாட்டில்கள், அட்டைப் பெட்டிகள், செய்தித்தாள்கள், கண்ணாடி பாட்டில்கள், தகர டப்பாக்கள்.
            • எளிய குறிப்பு: உணவு டப்பாக்களை சிறிது நீரால் கழுவி உலர்த்திப் போடவும்.
            • ஏன்?: இவை மறுசுழற்சி செய்யப்பட்டு புதிய பொருட்களாக உருவாகின்றன, மரங்களைப் பாதுகாக்கின்றன!

            🟠 3. ஆரஞ்சு தொட்டி (Orange Bin) — மின்-கழிவுகள்
            • போட வேண்டியவை: பழைய போன்கள், சார்ஜர்கள், ஒயர்கள், ஹெட்போன்கள், ரிமோட், மவுஸ், கீபோர்டு.
            • எளிய குறிப்பு: எலக்ட்ரானிக்ஸ் பொருட்களை உடைக்கவோ எரிக்கவோ கூடாது.
            • ஏன்?: இவற்றில் தாமிரம் போன்ற உலோகங்களும், நிலத்தடி நீரை விஷமாக்கும் ஈயம், பாதரசமும் உள்ளன.

            🔴 4. சிவப்பு தொட்டி (Red Bin) — அபாயகரமான & சுகாதாரக் கழிவுகள்
            • போட வேண்டியவை: பழைய பேட்டரிகள், டியூப்லைட்/பல்புகள், பெயிண்ட், பூச்சிக்கொல்லி, மருந்துகள், சானிட்டரி நாப்கின்கள், டயப்பர்கள்.
            • எளிய குறிப்பு: சானிட்டரி கழிவுகளை செய்தித்தாளில் சுற்றிப் போடவும்; பேட்டரிகளில் டேப் ஒட்டவும்.
            • ஏன்?: தூய்மைப் பணியாளர்களின் ஆரோக்கியத்தைப் பாதுகாக்கிறது மற்றும் தீ விபத்துகளைத் தடுக்கிறது.

            ⚫ 5. கருப்பு தொட்டி (Black Bin) — மறுசுழற்சி செய்ய முடியாத கழிவுகள்
            • போட வேண்டியவை: கூட்டிய தூசி, உடைந்த பீங்கான் பொருட்கள், தெர்மாகோல்.
            • ஏன்?: பாதுகாப்பான நிலப்பரப்பு கிடங்கிற்கு கொண்டு செல்லப்படுகிறது.

            கழிவுகளை முறையாகப் பிரித்து வைப்போம், நமது சுற்றுப்புறத்தைத் தூய்மையாகப் பராமரிப்போம்! ஏதேனும் சந்தேகம் இருந்தால் என்னிடம் கேளுங்கள்! 😊
        """.trimIndent()

        "mr" -> """
            कचरा वर्गीकरणाची सोपी आणि शास्त्रीय 5 कुंड्यांची माहिती:

            🟢 1. हिरवी कुंडी (Green Bin) — ओला सेंद्रिय कचरा
            • काय टाकावे: फळे व भाज्यांची साले, उरलेले अन्न, चहापत्ती, झाडांची पाने, अंड्याची टरफले.
            • काय टाकू नये: प्लास्टिक पिशव्या, दुधाचे पाकीट किंवा रबर अजिबात टाकू नका.
            • का?: हा कचरा कुजून दर्जेदार सेंद्रिय खत किंवा बायोगॅस तयार होतो!

            🔵 2. निळी कुंडी (Blue Bin) — सुका पुनर्वापरयोग्य कचरा
            • काय टाकावे: प्लास्टिक बाटल्या, कागद, पुठ्ठ्याचे बॉक्स, काचेच्या बाटल्या, धातूचे डबे/कॅन.
            • सोपी टीप: डबे थोडे पाण्याने विसळून कोरडे करून टाका.
            • का?: हा कचरा कारखान्यात जाऊन नवीन वस्तू बनवण्यासाठी वापरला जातो, झाडे वाचतात!

            🟠 3. नारिंगी कुंडी (Orange Bin) — ई-कचरा / इलेक्ट्रॉनिक्स
            • काय टाकावे: जुने मोबाईल, चार्जर, वायर, हेडफोन, रिमोट, कीबोर्ड, संगणकाचे सुटे भाग.
            • सोपी टीप: उपकरणे तोडू किंवा जाळू नका.
            • का?: यामध्ये तांबे असते, तसेच विषारी रसायनेही असतात. योग्य रीसायकलिंगने पाणी व जमीन सुरक्षित राहते.

            🔴 4. लाल कुंडी (Red Bin) — धोकादायक व स्वच्छता कचरा
            • काय टाकावे: वापरलेल्या बॅटऱ्या, ट्यूबलाईट/बल्ब, रंग, कीटकनाशके, औषधे, सॅनिटरी पॅड व डायपर.
            • सोपी टीप: सॅनिटरी कचरा वर्तमानपत्रात गुंडाळून टाका; बॅटरीच्या टोकांवर चिकटपट्टी लावा.
            • का?: सफाई कामगारांचे रक्षण होते आणि संसर्ग व आगीचा धोका टळतो.

            ⚫ 5. काळी कुंडी (Black Bin) — पुनर्वापर न होणारा कचरा
            • काय टाकावे: केर/धूळ, फुटलेली चिनीमाती/सिरॅमिक्स, थर्माकोल, अतिशय तेलकट कचरा.
            • का?: हा कचरा सुरक्षितपणे वैज्ञानिक लँडफिलमध्ये साठवला जातो.

            कचऱ्याचे योग्य वर्गीकरण करूया, आपले घर व परिसर स्वच्छ ठेवूया! कोणताही प्रश्न असल्यास नक्की विचारा! 😊
        """.trimIndent()

        else -> """
            Here is your simple, human-friendly guide to the 5 municipal waste streams:

            🟢 1. Green Bin — Wet / Organic Waste
            • What goes here: Fruit & vegetable peels, leftover food, tea leaves, garden clippings, eggshells.
            • What NOT to put: No plastic covers, no milk pouches, no rubber bands.
            • Why: This waste naturally rots and is converted into rich organic compost or clean biogas!

            🔵 2. Blue Bin — Dry Recyclables
            • What goes here: Clean plastic bottles, paper, cardboard delivery cartons, glass jars, metal beverage cans.
            • Simple tip: Give food containers a quick rinse and shake dry before binning.
            • Why: These go to recycling factories to make new paper, boxes, and containers, saving trees and water!

            🟠 3. Orange Bin — E-Waste (Electronics)
            • What goes here: Old smartphones, cables, chargers, headphones, computer mice, keyboards, remotes.
            • Simple tip: Keep them dry and whole. Never burn or crush old gadgets.
            • Why: Electronics contain copper and gold, but also lead and mercury. Certified recycling keeps toxins out of drinking water.

            🔴 4. Red Bin — Hazardous & Sanitary Waste
            • What goes here: Used batteries, tube lights/bulbs, paint cans, pesticides, medicines, bandages, sanitary pads & diapers.
            • Simple tip: Wrap sanitary waste neatly in newspaper; tape battery ends.
            • Why: Protects sanitation workers and prevents fire and infection hazards.

            ⚫ 5. Black Bin — Non-Recyclable Refuse
            • What goes here: Swept floor dust, broken ceramics, thermo-col packing foam, heavily soiled paper.
            • Why: Sent to scientific municipal landfills.

            Segregation at home takes just a minute and makes our neighborhood clean, healthy, and green! Feel free to ask me about any item you are holding! 😊
        """.trimIndent()
    }

    private fun buildGeneralExplanation(
        itemName: String,
        rule: DisposalRule,
        isHazardous: Boolean,
        warning: String,
        lang: String = "en",
        isDetail: Boolean = false
    ): String {
        val binDisplayName = CategoryLocalization.getBinColorDisplayName(rule.targetBinColor, lang)
        val emoji = getBinEmoji(rule.targetBinColor)
        val targetBin = "$emoji $binDisplayName"

        return when (lang) {
            "te" -> buildString {
                append("నమస్కారం! మీరు అడిగిన వస్తువు: $itemName.\n\n")
                if (isHazardous) {
                    append("⚠️ ముఖ్యమైన భద్రతా హెచ్చరిక:\n$warning\n\n")
                }
                append("దీనిని ఏ డబ్బాలో వేయాలి?\n")
                append("• $targetBin లో వేయాలి (${rule.disposalMethod}).\n\n")

                if (!rule.environmentalImpact.isNullOrBlank()) {
                    append("ఇది ఎందుకు ముఖ్యం?\n")
                    append("• ${rule.environmentalImpact}\n\n")
                }

                if (rule.preparationInstructions.isNotBlank()) {
                    append("మీరు చేయవలసిన సులభమైన పనులు:\n")
                    append("• ${rule.preparationInstructions}\n\n")
                }

                if (rule.prohibitedPathways.isNotEmpty()) {
                    append("చేయకూడనివి: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                }

                append("దీని గురించి లేదా మరొక వస్తువు గురించి ఇంకేమైనా సందేహం ఉంటే నన్ను అడగండి! 😊")
            }.trim()

            "hi" -> buildString {
                append("नमस्ते! आपकी वस्तु: $itemName।\n\n")
                if (isHazardous) {
                    append("⚠️ महत्वपूर्ण सुरक्षा चेतावनी:\n$warning\n\n")
                }
                append("इसे किस डिब्बे में डालें?\n")
                append("• $targetBin में डालें (${rule.disposalMethod})।\n\n")

                if (!rule.environmentalImpact.isNullOrBlank()) {
                    append("यह क्यों जरूरी है?\n")
                    append("• ${rule.environmentalImpact}\n\n")
                }

                if (rule.preparationInstructions.isNotBlank()) {
                    append("आपके लिए आसान कदम:\n")
                    append("• ${rule.preparationInstructions}\n\n")
                }

                if (rule.prohibitedPathways.isNotEmpty()) {
                    append("क्या न करें: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                }

                append("कचरा अलग करने के बारे में कोई और सवाल हो तो बेझिझक पूछें! 😊")
            }.trim()

            "ta" -> buildString {
                append("வணக்கம்! நீங்கள் கேட்ட பொருள்: $itemName.\n\n")
                if (isHazardous) {
                    append("⚠️ முக்கியமான பாதுகாப்பு எச்சரிக்கை:\n$warning\n\n")
                }
                append("எந்தத் தொட்டியில் போட வேண்டும்?\n")
                append("• $targetBin யில் போடவும் (${rule.disposalMethod}).\n\n")

                if (!rule.environmentalImpact.isNullOrBlank()) {
                    append("ஏன் இது முக்கியம்?\n")
                    append("• ${rule.environmentalImpact}\n\n")
                }

                if (rule.preparationInstructions.isNotBlank()) {
                    append("நீங்கள் செய்ய வேண்டிய எளிய வழிகள்:\n")
                    append("• ${rule.preparationInstructions}\n\n")
                }

                if (rule.prohibitedPathways.isNotEmpty()) {
                    append("செய்யக்கூடாதவை: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                }

                append("வேறேதேனும் பொருளைப் பற்றி அறிய விரும்பினால் தயங்காமல் கேளுங்கள்! 😊")
            }.trim()

            "mr" -> buildString {
                append("नमस्कार! तुमची वस्तू: $itemName.\n\n")
                if (isHazardous) {
                    append("⚠️ महत्त्वाची सुरक्षा सूचना:\n$warning\n\n")
                }
                append("कोणत्या कुंडीत टाकावे?\n")
                append("• $targetBin मध्ये टाका (${rule.disposalMethod}).\n\n")

                if (!rule.environmentalImpact.isNullOrBlank()) {
                    append("हे का गरजेचे आहे?\n")
                    append("• ${rule.environmentalImpact}\n\n")
                }

                if (rule.preparationInstructions.isNotBlank()) {
                    append("तुमच्यासाठी सोप्या पायऱ्या:\n")
                    append("• ${rule.preparationInstructions}\n\n")
                }

                if (rule.prohibitedPathways.isNotEmpty()) {
                    append("काय करू नये: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                }

                append("कचरा व्यवस्थापनाबद्दल आणखी काही प्रश्न असल्यास नक्की विचारा! 😊")
            }.trim()

            else -> buildString {
                append("Hello there! Regarding $itemName:\n\n")
                if (isHazardous) {
                    append("⚠️ Important Safety Warning:\n$warning\n\n")
                }
                append("Which bin does it go into?\n")
                append("• $targetBin (${rule.disposalMethod}).\n\n")

                if (!rule.environmentalImpact.isNullOrBlank()) {
                    append("Why is this important?\n")
                    append("• ${rule.environmentalImpact}\n\n")
                }

                if (rule.preparationInstructions.isNotBlank()) {
                    append("Simple steps for you:\n")
                    append("• ${rule.preparationInstructions}\n\n")
                }

                if (rule.prohibitedPathways.isNotEmpty()) {
                    append("Please avoid: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                }

                append("Feel free to ask me about any other items you'd like to segregate! 😊")
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
