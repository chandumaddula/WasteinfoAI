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
                } else if (isGreeting(query)) {
                    buildGreetingResponse(lang, itemName)
                } else if (isGratitude(query)) {
                    buildGratitudeResponse(lang)
                } else if (isIdentity(query)) {
                    buildIdentityResponse(lang)
                } else if (isHelp(query)) {
                    buildHelpResponse(lang)
                } else if (isFarewell(query)) {
                    buildFarewellResponse(lang)
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

    private fun buildGeneralExplanation(
        itemName: String,
        rule: DisposalRule,
        isHazardous: Boolean,
        warning: String,
        lang: String = "en",
        isDetail: Boolean = false
    ): String {
        val binDisplayName = CategoryLocalization.getBinColorDisplayName(rule.targetBinColor, lang)
        val targetBin = if (lang == "en") binDisplayName else "$binDisplayName (${rule.targetBinColor})"
        return when (lang) {
            "te" -> buildString {
                if (isHazardous) {
                    append("⚠️ భద్రతా సలహా (ప్రమాదకర వ్యర్థాలు):\n$warning\n\n")
                } else {
                    append("మంచి ఆలోచన! బాధ్యతాయుతంగా పారవేసేందుకు తనిఖీ చేసినందుకు ధన్యవాదాలు.\n\n")
                }
                append("$itemName కోసం మార్గదర్శకత్వం:\n")
                append("• సిఫార్సు చేసిన పారవేత విధానం: ${rule.disposalMethod}\n")
                append("• కేటాయించిన డబ్బా: $targetBin\n")
                append("• రీసైకిల్ చేయగలదా: ${if (rule.isRecyclable) "అవును" else "కాదు"}\n\n")
                if (rule.preparationInstructions.isNotBlank()) {
                    append("సన్నాహక సూచనలు:\n• ${rule.preparationInstructions}\n")
                }
                if (isDetail || rule.prohibitedPathways.isNotEmpty()) {
                    if (rule.prohibitedPathways.isNotEmpty()) {
                        append("\nచేయకూడనివి: ${rule.prohibitedPathways.joinToString("; ")}\n")
                    }
                }
                if (isDetail && !rule.environmentalImpact.isNullOrBlank()) {
                    append("\nపర్యావరణ సమాచారం: ${rule.environmentalImpact}\n")
                }
                append("\n(బాటిల్ AI ఆఫ్‌లైన్ అసిస్టెంట్ — ${rule.source} ఆధారంగా)")
            }.trim()
            "hi" -> buildString {
                if (isHazardous) {
                    append("⚠️ सुरक्षा सलाह (खतरनाक कचरा):\n$warning\n\n")
                } else {
                    append("अच्छा कदम! फेंकने से पहले जाँच करने के लिए धन्यवाद।\n\n")
                }
                append("$itemName के लिए मार्गदर्शन:\n")
                append("• अनुशंसित निपटान: ${rule.disposalMethod}\n")
                append("• लक्षित डिब्बा: $targetBin\n")
                append("• पुनर्चक्रण योग्य: ${if (rule.isRecyclable) "हाँ" else "नहीं"}\n\n")
                if (rule.preparationInstructions.isNotBlank()) {
                    append("तैयारी के निर्देश:\n• ${rule.preparationInstructions}\n")
                }
                if (isDetail || rule.prohibitedPathways.isNotEmpty()) {
                    if (rule.prohibitedPathways.isNotEmpty()) {
                        append("\nनिषिद्ध कार्य: ${rule.prohibitedPathways.joinToString("; ")}\n")
                    }
                }
                if (isDetail && !rule.environmentalImpact.isNullOrBlank()) {
                    append("\nपर्यावरण प्रभाव: ${rule.environmentalImpact}\n")
                }
                append("\n(बॉटल AI — स्रोत: ${rule.source})")
            }.trim()
            "ta" -> buildString {
                if (isHazardous) {
                    append("⚠️ பாதுகாப்பு ஆலோசனை (அபாயகரமான கழிவு):\n$warning\n\n")
                } else {
                    append("சிறந்த சிந்தனை! அகற்றுவதற்கு முன் சரிபார்த்ததற்கு நன்றி.\n\n")
                }
                append("$itemName க்கான வழிகாட்டுதல்:\n")
                append("• பரிந்துரைக்கப்பட்ட அகற்றல்: ${rule.disposalMethod}\n")
                append("• இலக்கு தொட்டி: $targetBin\n")
                append("• மறுசுழற்சி செய்யக்கூடியதா: ${if (rule.isRecyclable) "ஆம்" else "இல்லை"}\n\n")
                if (rule.preparationInstructions.isNotBlank()) {
                    append("தயாரிப்பு வழிமுறைகள்:\n• ${rule.preparationInstructions}\n")
                }
                if (isDetail || rule.prohibitedPathways.isNotEmpty()) {
                    if (rule.prohibitedPathways.isNotEmpty()) {
                        append("\nசெய்யக்கூடாதவை: ${rule.prohibitedPathways.joinToString("; ")}\n")
                    }
                }
                if (isDetail && !rule.environmentalImpact.isNullOrBlank()) {
                    append("\nசுற்றுச்சூழல் தாக்கம்: ${rule.environmentalImpact}\n")
                }
                append("\n(பாட்டில் AI — ஆதாரம்: ${rule.source})")
            }.trim()
            "mr" -> buildString {
                if (isHazardous) {
                    append("⚠️ सुरक्षा सूचना (घातक कचरा):\n$warning\n\n")
                } else {
                    append("चांगली गोष्ट! कचरा टाकण्यापूर्वी खात्री केल्याबद्दल धन्यवाद.\n\n")
                }
                append("$itemName साठी मार्गदर्शन:\n")
                append("• शिफारस केलेली विल्हेवाट: ${rule.disposalMethod}\n")
                append("• लक्षित कुंडी: $targetBin\n")
                append("• पुनर्वापर करण्यायोग्य: ${if (rule.isRecyclable) "होय" else "नाही"}\n\n")
                if (rule.preparationInstructions.isNotBlank()) {
                    append("तयारीच्या सूचना:\n• ${rule.preparationInstructions}\n")
                }
                if (isDetail || rule.prohibitedPathways.isNotEmpty()) {
                    if (rule.prohibitedPathways.isNotEmpty()) {
                        append("\nनिषिद्ध कृती: ${rule.prohibitedPathways.joinToString("; ")}\n")
                    }
                }
                if (isDetail && !rule.environmentalImpact.isNullOrBlank()) {
                    append("\nपर्यावरणीय प्रभाव: ${rule.environmentalImpact}\n")
                }
                append("\n(बॉटल AI — स्रोत: ${rule.source})")
            }.trim()
            else -> buildString {
                if (isHazardous) {
                    append("⚠️ SAFETY ADVISORY (Hazardous Waste):\n$warning\n\n")
                } else {
                    append("Great job checking before disposing! Here is the guidance for $itemName:\n\n")
                }
                append("Guidance for $itemName:\n")
                append("• Recommended Disposal: ${rule.disposalMethod}\n")
                append("• Target Bin: $targetBin\n")
                append("• Recyclable: ${if (rule.isRecyclable) "Yes" else "No"}\n\n")
                if (rule.preparationInstructions.isNotBlank()) {
                    append("Preparation Steps:\n• ${rule.preparationInstructions}\n")
                }
                if (isDetail || rule.prohibitedPathways.isNotEmpty()) {
                    if (rule.prohibitedPathways.isNotEmpty()) {
                        append("\nDo NOT: ${rule.prohibitedPathways.joinToString("; ")}\n")
                    }
                }
                if (isDetail && !rule.environmentalImpact.isNullOrBlank()) {
                    append("\nEco Note: ${rule.environmentalImpact}\n")
                }
                append("\n(Bottle AI — grounded in ${rule.source})")
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
