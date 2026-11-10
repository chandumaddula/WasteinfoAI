package com.wasteinfoai.ai.llm

import com.wasteinfoai.ai.rules.DisposalRule
import com.wasteinfoai.ai.rules.SafetyGuard
import com.wasteinfoai.core.language.CategoryLocalization
import java.util.Locale

/**
 * Deterministic offline response generator for Bottle.
 * Resolves pronouns and condition follow-ups, respects intent priority,
 * and strictly prevents hazardous items from being upcycled or burned.
 * Supports native language responses for English (en), Hindi (hi), Telugu (te), Tamil (ta), Marathi (mr),
 * Kannada (kn), Malayalam (ml), Bengali (bn), and Gujarati (gu).
 */
class OfflineResponder {

    private val conditionKeywords = mapOf(
        "broken" to listOf("broken", "shattered", "cracked", "torn", "fractured", "shards", "टूटा", "टूटी", "పగిలిన", "உடைந்த", "फुटलेला", "फुटलेली", "ಒಡೆದ", "ಮುರಿದ", "പൊട്ടിയ", "തകർന്ന", "ভাঙা", "ফাটা", "તૂટેલું", "ભાંગેલું"),
        "crushed" to listOf("crushed", "squashed", "flattened", "bent", "compacted", "कुचला", "कुचली", "నలిగిన", "நசுக்கப்பட்ட", "चिरडलेला", "ಜಜ್ಜಿದ", "ಚಪ್ಪಟೆಯಾದ", "ചതഞ്ഞ", "പരന്ന", "চ্যাপ্টা", "দুমড়ানো", "ચગદાયેલું", "ચપટું"),
        "wet" to listOf("wet", "soaked", "damp", "moist", "गीला", "गीली", "తడి", "ஈரமான", "ओला", "ओली", "ತೇವ", "ಹಸಿ", "നനഞ്ഞ", "ഈർപ്പമുള്ള", "ভেজা", "আর্দ্র", "ભીનું", "ભેજવાળું"),
        "greasy" to listOf("greasy", "oily", "food-stained", "cheese", "dirty", "soiled", "तेल", "चिकनाई", "జిడ్డు", "எண்ணெய்", "तेलकट", "ಜಿಡ್ಡು", "ಎಣ್ಣೆಯುಕ್ತ", "എണ്ണമയം", "കൊഴുപ്പുള്ള", "তেলতেলে", "তৈলাক্ত", "ચીકણું", "તેલવાળું"),
        "empty" to listOf("empty", "rinsed", "खाली", "ఖాళీ", "காலி", "रिकामा", "रिकामी", "ಖಾಲಿ", "ശൂന്യമായ", "ഒഴിഞ്ഞ", "খালি", "ખાલી"),
        "full" to listOf("full", "filled", "leftover", "भरा", "నిండిన", "முழு", "भरलेला", "ತುಂಬಿದ", "നിറഞ്ഞ", "ভরা", "পূর্ণ", "ભરેલું")
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
                "kn" -> buildString {
                    append("⚠️ SAFETY REFUSAL — ಸುರಕ್ಷತಾ ನಿರಾಕರಣೆ (ಅಪಾಯಕಾರಿ ತ್ಯಾಜ್ಯ):\n")
                    append("ತೀವ್ರ ಸುರಕ್ಷತಾ ಅಪಾಯಗಳ ಕಾರಣದಿಂದ $itemName ಅನ್ನು ಮರುಬಳಕೆ ಮಾಡಲು, ಕ್ರಾಫ್ಟ್ ಮಾಡಲು ಅಥವಾ ಸುಡಲು ಸಾಧ್ಯವಿಲ್ಲ!\n\n")
                    append("ಸುರಕ್ಷತಾ ಎಚ್ಚರಿಕೆ: $warning\n\n")
                    append("ಶಿಫಾರಸು ಮಾಡಿದ ವಿಲೇವಾರಿ: ಕೇವಲ ${rule.disposalMethod} (${rule.targetBinColor} ಬುಟ್ಟಿ) ಗೆ ಮಾತ್ರ ಒಪ್ಪಿಸಿ.")
                }.trim()
                "ml" -> buildString {
                    append("⚠️ SAFETY REFUSAL — സുരക്ഷാ നിരസിക്കൽ (അപകടകരമായ മാലിന്യം):\n")
                    append("ഗുരുതരമായ സുരക്ഷാ അപകടങ്ങൾ കാരണം $itemName വീണ്ടും ഉപയോഗിക്കാനോ, ക്രാഫ്റ്റ് ചെയ്യാനോ, കത്തിക്കാനോ പാടില്ല!\n\n")
                    append("സുരക്ഷാ മുന്നറിയിപ്പ്: $warning\n\n")
                    append("ശുപാർശ ചെയ്യുന്ന സംസ്കരണം: ${rule.disposalMethod} (${rule.targetBinColor} തൊട്ടി) ലേക്ക് മാത്രം കൈമാറുക.")
                }.trim()
                "bn" -> buildString {
                    append("⚠️ SAFETY REFUSAL — নিরাপত্তা প্রত্যাখ্যান (বিপজ্জনক বর্জ্য):\n")
                    append("গুরুতর নিরাপত্তা ঝুঁকির কারণে $itemName পুনরায় ব্যবহার, ক্রাফ্ট বা পোড়ানো যাবে না!\n\n")
                    append("নিরাপত্তা সতর্কতা: $warning\n\n")
                    append("প্রস্তাবিত নিষ্পত্তি: কেবল ${rule.disposalMethod} (${rule.targetBinColor} পাত্র) এ হস্তান্তর করুন।")
                }.trim()
                "gu" -> buildString {
                    append("⚠️ SAFETY REFUSAL — સુરક્ષા ઇનકાર (જોખમી કચરો):\n")
                    append("ગંભીર સુરક્ષા જોખમોને કારણે $itemName નો પુનઃઉપયોગ, ક્રાફ્ટ અથવા બાળી શકાતો નથી!\n\n")
                    append("સુરક્ષા ચેતવણી: $warning\n\n")
                    append("ભલામણ કરેલ નિકાલ: માત્ર ${rule.disposalMethod} (${rule.targetBinColor} ડબ્બો) માં જ સોંપો.")
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
                "kn" -> buildString {
                    append("⚠️ ಬೇಡ, ದಯವಿಟ್ಟು $itemName ಅನ್ನು ಸುಡಬೇಡಿ!\n\n")
                    append("ತ್ಯಾಜ್ಯವನ್ನು ತೆರೆದ ಜಾಗದಲ್ಲಿ ಸುಡುವುದರಿಂದ ವಿಷಕಾರಿ ಹೊಗೆ ಮತ್ತು ಅಪಾಯಕಾರಿ ಮಾಲಿನ್ಯಕಾರಕಗಳು ಬಿಡುಗಡೆಯಾಗುತ್ತವೆ, ಇದು ಆರೋಗ್ಯ ಮತ್ತು ಪರಿಸರಕ್ಕೆ ಹಾನಿಕಾರಕ.\n\n")
                    append("ಸುರಕ್ಷಿತ ವಿಲೇವಾರಿ: $targetBin (${rule.disposalMethod}) ನಲ್ಲಿ ಇರಿಸಿ.")
                }.trim()
                "ml" -> buildString {
                    append("⚠️ വേണ്ട, ദയവായി $itemName കത്തിക്കരുത്!\n\n")
                    append("മാലിന്യങ്ങൾ തുറസ്സായ സ്ഥലത്ത് കത്തിക്കുന്നത് വിഷപ്പുകയും ഹാനികരമായ വാതകങ്ങളും വായുവിൽ പടർത്തും, ഇത് ആരോഗ്യത്തിന് ഹാനികരമാണ്.\n\n")
                    append("സുരക്ഷിതമായ സംസ്കരണം: $targetBin (${rule.disposalMethod}) ൽ നിക്ഷേപിക്കുക.")
                }.trim()
                "bn" -> buildString {
                    append("⚠️ না, দয়া করে $itemName পোড়াবেন না!\n\n")
                    append("খোলা জায়গায় বর্জ্য পোড়ালে বিষাক্ত ধোঁয়া ও ক্ষতিকারক গ্যাস নির্গত হয় যা স্বাস্থ্য ও পরিবেশের জন্য মারাত্মক ক্ষতিকর।\n\n")
                    append("নিরাপদ নিষ্পত্তি: এটি $targetBin (${rule.disposalMethod}) এ রাখুন।")
                }.trim()
                "gu" -> buildString {
                    append("⚠️ ના, કૃપા કરીને $itemName બાળશો નહીં!\n\n")
                    append("ખુલ્લામાં કચરો બાળવાથી ઝેરી ધુમાડો અને પ્રદૂષકો ફેલાય છે જે સ્વાસ્થ્ય અને પર્યાવરણ માટે અત્યંત હાનિકારક છે.\n\n")
                    append("સુરક્ષિત નિકાલ: તેને $targetBin (${rule.disposalMethod}) માં મૂકો.")
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
                "kn" -> buildString {
                    append("🔋 $itemName ಬ್ಯಾಟರಿ ನಿರ್ವಹಣೆ:\n\n")
                    append("ನಿಮ್ಮ ಸಾಧನದಲ್ಲಿ ತೆಗೆಯಬಹುದಾದ ಬ್ಯಾಟರಿ ಇದ್ದರೆ, ವಿಲೇವಾರಿ ಮಾಡುವ ಮೊದಲು ಅದನ್ನು ಬೇರ್ಪಡಿಸಿ.\n\n")
                    append("• ಬ್ಯಾಟರಿಗಳನ್ನು ಎಂದಿಗೂ ಒಡೆಯಬೇಡಿ ಅಥವಾ ಸುಡಬೇಡಿ.\n")
                    append("• ಅಧಿಕೃತ ಬ್ಯಾಟರಿ ಅಥವಾ ಇ-ತ್ಯಾಜ್ಯ ಸಂಗ್ರಹ ಕೇಂದ್ರಕ್ಕೆ ನೀಡಿ.\n\n")
                    append("ಉಳಿದ ಭಾಗವನ್ನು ${rule.disposalMethod} ($targetBin) ನಲ್ಲಿ ವಿಲೇವಾರಿ ಮಾಡಿ.")
                }.trim()
                "ml" -> buildString {
                    append("🔋 $itemName ബാറ്ററി കൈകാര്യം ചെയ്യൽ:\n\n")
                    append("നിങ്ങളുടെ ഉപകരണത്തിൽ വേർപെടുത്താവുന്ന ബാറ്ററിയുണ്ടെങ്കിൽ, സംസ്കരിക്കുന്നതിന് മുമ്പ് അത് മാറ്റുക.\n\n")
                    append("• ബാറ്ററികൾ തകർക്കുകയോ കത്തിക്കുകയോ ചെയ്യരുത്.\n")
                    append("• അംഗീകൃത ബാറ്ററി അല്ലെങ്കിൽ ഇ-മാലിന്യ കേന്ദ്രത്തിൽ കൈമാറുക.\n\n")
                    append("ബാക്കി ഭാഗം ${rule.disposalMethod} ($targetBin) ൽ നിക്ഷേപിക്കുക.")
                }.trim()
                "bn" -> buildString {
                    append("🔋 $itemName ব্যাটারি পরিচালনা:\n\n")
                    append("আপনার ডিভাইসে খোলা যায় এমন ব্যাটারি থাকলে, ফেলার আগে তা আলাদা করে নিন।\n\n")
                    append("• ব্যাটারি কখনো ভাঙবেন না বা পোড়াবেন না।\n")
                    append("• অনুমোদিত ব্যাটারি বা ই-বর্জ্য সংগ্রহ কেন্দ্রে জমা দিন।\n\n")
                    append("বাকি অংশ ${rule.disposalMethod} ($targetBin) এ নিষ্পত্তি করুন।")
                }.trim()
                "gu" -> buildString {
                    append("🔋 $itemName બેટરી સંચાલન:\n\n")
                    append("જો તમારા ઉપકરણમાં દૂર કરી શકાય તેવી બેટરી હોય, તો નિકાલ કરતા પહેલાં તેને અલગ કરો.\n\n")
                    append("• બેટરીને ક્યારેય તોડશો કે બાળશો નહીં.\n")
                    append("• અધિકૃત બેટરી અથવા ઇ-કચરો કેન્દ્રમાં જમા કરાવો.\n\n")
                    append("બાકીના ભાગને ${rule.disposalMethod} ($targetBin) માં નિકાલ કરો.")
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
                "kn" -> buildString {
                    append("⏳ $itemName ಕೊಳೆಯಲು ತೆಗೆದುಕೊಳ್ಳುವ ಸಮಯ:\n\n")
                    if (!rule.environmentalImpact.isNullOrBlank()) {
                        append("ಪರಿಸರ ಮಾಹಿತಿ: ${rule.environmentalImpact}\n\n")
                    }
                    append("• ಸಾವಯವ ತ್ಯಾಜ್ಯವು ಕಾಂಪೋಸ್ಟ್‌ನಲ್ಲಿ ಕೆಲವೇ ವಾರಗಳಲ್ಲಿ ಅಥವಾ ತಿಂಗಳುಗಳಲ್ಲಿ ಸ್ವಾಭಾವಿಕವಾಗಿ ಕೊಳೆಯುತ್ತದೆ.\n")
                    append("• ಪ್ಲಾಸ್ಟಿಕ್ ಮತ್ತು ಇ-ತ್ಯಾಜ್ಯ ಪ್ರಕೃತಿಯಲ್ಲಿ ಕರಗಲು ನೂರಾರು ವರ್ಷಗಳು ಬೇಕಾಗುತ್ತದೆ.\n\n")
                    append("ಸೂಕ್ತ ವಿಲೇವಾರಿಗಾಗಿ: $targetBin (${rule.disposalMethod}).")
                }.trim()
                "ml" -> buildString {
                    append("⏳ $itemName മണ്ണിൽ ചേരാൻ എടുക്കുന്ന സമയം:\n\n")
                    if (!rule.environmentalImpact.isNullOrBlank()) {
                        append("പരിസ്ഥിതി ആഘാതം: ${rule.environmentalImpact}\n\n")
                    }
                    append("• ജൈവ മാലിന്യങ്ങൾ ഏതാനും ആഴ്ചകൾ മുതൽ മാസങ്ങൾക്കുള്ളിൽ പ്രകൃതിദത്തമായി മണ്ണിൽ ലയിക്കുന്നു.\n")
                    append("• പ്ലാസ്റ്റിക്കും ഇ-മാലിന്യങ്ങളും നശിക്കാൻ നൂറുകണക്കിന് വർഷങ്ങൾ എടുക്കും.\n\n")
                    append("ശരിയായ സംസ്കരണത്തിന്: $targetBin (${rule.disposalMethod}).")
                }.trim()
                "bn" -> buildString {
                    append("⏳ $itemName পচতে লাগা সময়:\n\n")
                    if (!rule.environmentalImpact.isNullOrBlank()) {
                        append("পরিবেশগত প্রভাব: ${rule.environmentalImpact}\n\n")
                    }
                    append("• জৈব বর্জ্য কম্পোস্টে কয়েক সপ্তাহ থেকে মাসের মধ্যে প্রাকৃতিকভাবে মিশে যায়।\n")
                    append("• প্লাস্টিক ও ই-বর্জ্য প্রকৃতিতে মিশতে শত শত বছর সময় লাগে।\n\n")
                    append("সঠিক নিষ্পত্তি: $targetBin (${rule.disposalMethod})।")
                }.trim()
                "gu" -> buildString {
                    append("⏳ $itemName વિઘટન થવાનો સમય:\n\n")
                    if (!rule.environmentalImpact.isNullOrBlank()) {
                        append("પર્યાવરણીય અસર: ${rule.environmentalImpact}\n\n")
                    }
                    append("• જૈવિક કચરો ખાતરમાં થોડા અઠવાડિયાથી મહિનામાં કુદરતી રીતે ભળી જાય છે.\n")
                    append("• પ્લાસ્ટિક અને ઇ-કચરાને પ્રકૃતિમાં ઓગળતા સેંકડો વર્ષ લાગે છે.\n\n")
                    append("યોગ્ય નિકાલ: $targetBin (${rule.disposalMethod}).")
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
                    "kn" -> buildString {
                        append("⚠️ $itemName ಗಾಗಿ ಸುರಕ್ಷತಾ ಸಲಹೆ:\n")
                        append("$warning\n\n")
                        if (safety.recommendedActions.isNotEmpty()) {
                            append("ಸುರಕ್ಷತಾ ನಿಯಮಗಳು:\n")
                            safety.recommendedActions.forEach { append("• $it\n") }
                            append("\n")
                        }
                        append("ಗುರಿ ಬುಟ್ಟಿ: ${rule.targetBinColor} ಬುಟ್ಟಿ (${rule.disposalMethod})")
                        if (conditionAdvice != null) {
                            append("\n\nಸ್ಥಿತಿ ಟಿಪ್ಪಣಿ ($detectedCondition): $conditionAdvice")
                        }
                    }.trim()
                    "ml" -> buildString {
                        append("⚠️ $itemName നുള്ള സുരക്ഷാ നിർദ്ദേശം:\n")
                        append("$warning\n\n")
                        if (safety.recommendedActions.isNotEmpty()) {
                            append("സുരക്ഷാ മാർഗ്ഗരേഖ:\n")
                            safety.recommendedActions.forEach { append("• $it\n") }
                            append("\n")
                        }
                        append("ലക്ഷ്യ തൊട്ടി: ${rule.targetBinColor} തൊട്ടി (${rule.disposalMethod})")
                        if (conditionAdvice != null) {
                            append("\n\nസ്ഥിതി വിവരണം ($detectedCondition): $conditionAdvice")
                        }
                    }.trim()
                    "bn" -> buildString {
                        append("⚠️ $itemName এর জন্য নিরাপত্তা পরামর্শ:\n")
                        append("$warning\n\n")
                        if (safety.recommendedActions.isNotEmpty()) {
                            append("নিরাপত্তা প্রোটোকল:\n")
                            safety.recommendedActions.forEach { append("• $it\n") }
                            append("\n")
                        }
                        append("নির্দিষ্ট পাত্র: ${rule.targetBinColor} পাত্র (${rule.disposalMethod})")
                        if (conditionAdvice != null) {
                            append("\n\nঅবস্থা নির্দেশিকা ($detectedCondition): $conditionAdvice")
                        }
                    }.trim()
                    "gu" -> buildString {
                        append("⚠️ $itemName માટે સુરક્ષા સલાહ:\n")
                        append("$warning\n\n")
                        if (safety.recommendedActions.isNotEmpty()) {
                            append("સુરક્ષા પ્રોટોકોલ:\n")
                            safety.recommendedActions.forEach { append("• $it\n") }
                            append("\n")
                        }
                        append("લક્ષ્ય ડબ્બો: ${rule.targetBinColor} ડબ્બો (${rule.disposalMethod})")
                        if (conditionAdvice != null) {
                            append("\n\nસ્થિતિ નોંધ ($detectedCondition): $conditionAdvice")
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
                    "kn" -> buildString {
                        append("🧼 $itemName ಸ್ವಚ್ಛಗೊಳಿಸುವ ಮತ್ತು ಸಿದ್ಧತೆಯ ಸೂಚನೆಗಳು:\n\n")
                        append("$tips\n\n")
                        if (conditionAdvice != null) {
                            append("ಸ್ಥಿತಿ ನಿರ್ವಹಣೆ ($detectedCondition):\n$conditionAdvice\n\n")
                        }
                        append("ವಿಲೇವಾರಿ ಬುಟ್ಟಿ: ${rule.targetBinColor} ಬುಟ್ಟಿ (${if (rule.isRecyclable) "ಮರುಬಳಕೆ ಮಾಡಬಹುದಾದ" else "ಮರುಬಳಕೆಯಾಗದ"}).")
                    }.trim()
                    "ml" -> buildString {
                        append("🧼 $itemName വൃത്തിയാക്കലും തയ്യാറെടുപ്പ് നിർദ്ദേശങ്ങളും:\n\n")
                        append("$tips\n\n")
                        if (conditionAdvice != null) {
                            append("സ്ഥിതി കൈകാര്യം ചെയ്യൽ ($detectedCondition):\n$conditionAdvice\n\n")
                        }
                        append("സംസ്കരണ തൊട്ടി: ${rule.targetBinColor} തൊട്ടി (${if (rule.isRecyclable) "പുനരുപയോഗിക്കാവുന്നത്" else "പുനരുപയോഗിക്കാൻ കഴിയാത്തത്"}).")
                    }.trim()
                    "bn" -> buildString {
                        append("🧼 $itemName পরিষ্কার ও প্রস্তুতির নির্দেশাবলী:\n\n")
                        append("$tips\n\n")
                        if (conditionAdvice != null) {
                            append("অবস্থা পরিচালনা ($detectedCondition):\n$conditionAdvice\n\n")
                        }
                        append("নির্দিষ্ট পাত্র: ${rule.targetBinColor} পাত্র (${if (rule.isRecyclable) "পুনর্ব্যবহারযোগ্য" else "অপুনর্ব্যবহারযোগ্য"}).")
                    }.trim()
                    "gu" -> buildString {
                        append("🧼 $itemName ની સફાઈ અને તૈયારી માટેની સૂચનાઓ:\n\n")
                        append("$tips\n\n")
                        if (conditionAdvice != null) {
                            append("સ્થિતિ સંચાલન ($detectedCondition):\n$conditionAdvice\n\n")
                        }
                        append("નિકાલ ડબ્બો: ${rule.targetBinColor} ડબ્બો (${if (rule.isRecyclable) "પુનઃઉપયોગી" else "બિન-પુનઃઉપયોગી"}).")
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
                    "kn" -> buildString {
                        append("♻️ $itemName ಗಾಗಿ ಮರುಬಳಕೆ ಮತ್ತು ಸೃಜನಶೀಲ ಕಲ್ಪನೆಗಳು:\n\n")
                        if (rule.reuseIdeas.isNotEmpty()) {
                            rule.reuseIdeas.forEach { idea -> append("• $idea\n") }
                        } else {
                            append("• ಸ್ವಚ್ಛಗೊಳಿಸಿ ಗೃಹ ಶೇಖರಣೆ, ತೋಟಗಾರಿಕೆ ಕುಂಡ ಅಥವಾ ಕ್ರಾಫ್ಟ್ ವಸ್ತುಗಳಾಗಿ ಮರುಬಳಸಿ.\n")
                        }
                        if (!rule.environmentalImpact.isNullOrBlank()) {
                            append("\nಪರಿಸರ ಮಾಹಿತಿ: ${rule.environmentalImpact}\n")
                        }
                        if (conditionAdvice != null) {
                            append("\nಸ್ಥಿತಿ ಟಿಪ್ಪಣಿ ($detectedCondition): $conditionAdvice\n")
                        }
                        append("\nಬಳಕೆ ಮುಗಿದ ನಂತರ, ${rule.targetBinColor} ಬುಟ್ಟಿಯಲ್ಲಿ ವಿಲೇವಾರಿ ಮಾಡಿ.")
                    }.trim()
                    "ml" -> buildString {
                        append("♻️ $itemName നുള്ള പുനരുപയോഗവും സർഗ്ഗാത്മക ആശയങ്ങളും:\n\n")
                        if (rule.reuseIdeas.isNotEmpty()) {
                            rule.reuseIdeas.forEach { idea -> append("• $idea\n") }
                        } else {
                            append("• വൃത്തിയാക്കി വീട്ടിലെ സാധനങ്ങൾ സൂക്ഷിക്കാനോ ചെടിച്ചട്ടികൾക്കോ ക്രാഫ്റ്റുകൾക്കോ ഉപയോഗിക്കുക.\n")
                        }
                        if (!rule.environmentalImpact.isNullOrBlank()) {
                            append("\nപരിസ്ഥിതി ആഘാതം: ${rule.environmentalImpact}\n")
                        }
                        if (conditionAdvice != null) {
                            append("\nസ്ഥിതി വിവരണം ($detectedCondition): $conditionAdvice\n")
                        }
                        append("\nഉപയോഗം പൂർത്തിയായ ശേഷം, ${rule.targetBinColor} തൊട്ടിയിൽ ഇടുക.")
                    }.trim()
                    "bn" -> buildString {
                        append("♻️ $itemName এর জন্য পুনর্ব্যবহার এবং সৃজনশীল ধারণা:\n\n")
                        if (rule.reuseIdeas.isNotEmpty()) {
                            rule.reuseIdeas.forEach { idea -> append("• $idea\n") }
                        } else {
                            append("• পরিষ্কার করে ঘরোয়া স্টোরেজ, গাছের টব বা ক্রাফ্ট প্রজেক্ট হিসেবে ব্যবহার করুন।\n")
                        }
                        if (!rule.environmentalImpact.isNullOrBlank()) {
                            append("\nপরিবেশগত প্রভাব: ${rule.environmentalImpact}\n")
                        }
                        if (conditionAdvice != null) {
                            append("\nঅবস্থা নির্দেশিকা ($detectedCondition): $conditionAdvice\n")
                        }
                        append("\nব্যবহার শেষ হলে, ${rule.targetBinColor} পাত্রে ফেলুন।")
                    }.trim()
                    "gu" -> buildString {
                        append("♻️ $itemName માટે પુનઃઉપયોગ અને સર્જનાત્મક વિચારો:\n\n")
                        if (rule.reuseIdeas.isNotEmpty()) {
                            rule.reuseIdeas.forEach { idea -> append("• $idea\n") }
                        } else {
                            append("• સાફ કરીને ઘરગથ્થુ સંગ્રહ, છોડના કુંડા કે ક્રાફ્ટ વસ્તુઓ માટે વાપરો.\n")
                        }
                        if (!rule.environmentalImpact.isNullOrBlank()) {
                            append("\nપર્યાવરણીય અસર: ${rule.environmentalImpact}\n")
                        }
                        if (conditionAdvice != null) {
                            append("\nસ્થિતિ નોંધ ($detectedCondition): $conditionAdvice\n")
                        }
                        append("\nઉપયોગ પૂર્ણ થયા પછી, તેને ${rule.targetBinColor} ડબ્બામાં નાખો.")
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
                    "kn" -> buildString {
                        append("$itemName ಅನ್ನು ಸರಿಯಾಗಿ ಈ ಬುಟ್ಟಿಗೆ ಹಾಕಬೇಕು:\n\n")
                        append("$emoji ನಿಗದಿತ ಬುಟ್ಟಿ: $targetBin\n")
                        append("• ವಿಲೇವಾರಿ ಮಾರ್ಗ: ${rule.disposalMethod}\n")
                        append("• ಮರುಬಳಕೆ ಸಾಧ್ಯವೇ: ${if (rule.isRecyclable) "ಹೌದು, ಮರುಬಳಕೆಯಾಗುತ್ತದೆ" else "ಇಲ್ಲ"}\n\n")
                        if (rule.acceptedPathways.isNotEmpty()) {
                            append("ಸ್ವೀಕರಿಸುವ ಕೇಂದ್ರಗಳು:\n${rule.acceptedPathways.joinToString("\n") { "• $it" }}\n\n")
                        }
                        if (rule.prohibitedPathways.isNotEmpty()) {
                            append("ಮಾಡಬಾರದ ಕಾರ್ಯಗಳು: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                        }
                        if (conditionAdvice != null) {
                            append("ಸ್ಥಿತಿ ಟಿಪ್ಪಣಿ ($detectedCondition): $conditionAdvice\n\n")
                        }
                        append("ಪರಿಸರವನ್ನು ಸ್ವಚ್ಛವಾಗಿಡಲು ಸಹಕರಿಸಿದ್ದಕ್ಕೆ ಧನ್ಯವಾದಗಳು! 😊")
                    }.trim()
                    "ml" -> buildString {
                        append("$itemName ശരിയായി ഈ തൊട്ടിയിലാണ് നിക്ഷേപിക്കേണ്ടത്:\n\n")
                        append("$emoji ലക്ഷ്യ തൊട്ടി: $targetBin\n")
                        append("• സംസ്കരണ രീതി: ${rule.disposalMethod}\n")
                        append("• പുനരുപയോഗിക്കാമോ: ${if (rule.isRecyclable) "അതെ" else "ഇല്ല"}\n\n")
                        if (rule.acceptedPathways.isNotEmpty()) {
                            append("സ്വീകരിക്കുന്ന കേന്ദ്രങ്ങൾ:\n${rule.acceptedPathways.joinToString("\n") { "• $it" }}\n\n")
                        }
                        if (rule.prohibitedPathways.isNotEmpty()) {
                            append("ചെയ്യാൻ പാടില്ലാത്തവ: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                        }
                        if (conditionAdvice != null) {
                            append("സ്ഥിതി വിവരണം ($detectedCondition): $conditionAdvice\n\n")
                        }
                        append("പരിസരം വൃത്തിയായി സൂക്ഷിക്കാൻ സഹായിച്ചതിന് നന്ദി! 😊")
                    }.trim()
                    "bn" -> buildString {
                        append("$itemName সঠিকভাবে এই পাত্রে ফেলা উচিত:\n\n")
                        append("$emoji নির্দিষ্ট পাত্র: $targetBin\n")
                        append("• নিষ্পত্তি পদ্ধতি: ${rule.disposalMethod}\n")
                        append("• পুনর্ব্যবহারযোগ্য কি: ${if (rule.isRecyclable) "হ্যাঁ" else "না"}\n\n")
                        if (rule.acceptedPathways.isNotEmpty()) {
                            append("গ্রহণযোগ্য স্থান:\n${rule.acceptedPathways.joinToString("\n") { "• $it" }}\n\n")
                        }
                        if (rule.prohibitedPathways.isNotEmpty()) {
                            append("যা করবেন না: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                        }
                        if (conditionAdvice != null) {
                            append("অবস্থা নির্দেশিকা ($detectedCondition): $conditionAdvice\n\n")
                        }
                        append("পরিবেশ পরিষ্কার রাখতে সহযোগিতা করার জন্য ধন্যবাদ! 😊")
                    }.trim()
                    "gu" -> buildString {
                        append("$itemName ને યોગ્ય રીતે આ ડબ્બામાં નાખવું:\n\n")
                        append("$emoji લક્ષ્ય ડબ્બો: $targetBin\n")
                        append("• નિકાલ પદ્ધતિ: ${rule.disposalMethod}\n")
                        append("• પુનઃઉપયોગી છે: ${if (rule.isRecyclable) "હા" else "ના"}\n\n")
                        if (rule.acceptedPathways.isNotEmpty()) {
                            append("સ્વીકૃત કેન્દ્રો:\n${rule.acceptedPathways.joinToString("\n") { "• $it" }}\n\n")
                        }
                        if (rule.prohibitedPathways.isNotEmpty()) {
                            append("શું ન કરવું: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                        }
                        if (conditionAdvice != null) {
                            append("સ્થિતિ નોંધ ($detectedCondition): $conditionAdvice\n\n")
                        }
                        append("સ્વચ્છતા જાળવવામાં મદદ કરવા બદલ આભાર! 😊")
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
                        "kn" -> buildString {
                            append("$detectedCondition $itemName ಗಾಗಿ ನಿರ್ವಹಣೆ:\n\n")
                            append("$conditionAdvice\n\n")
                            append("ಮಾನದಂಡದ ವಿಧಾನ: ${rule.disposalMethod} (${rule.targetBinColor} ಬುಟ್ಟಿ).")
                        }.trim()
                        "ml" -> buildString {
                            append("$detectedCondition $itemName കൈകാര്യം ചെയ്യൽ:\n\n")
                            append("$conditionAdvice\n\n")
                            append("സാധാരണ രീതി: ${rule.disposalMethod} (${rule.targetBinColor} തൊട്ടി).")
                        }.trim()
                        "bn" -> buildString {
                            append("$detectedCondition $itemName পরিচালনা:\n\n")
                            append("$conditionAdvice\n\n")
                            append("প্রমিত পদ্ধতি: ${rule.disposalMethod} (${rule.targetBinColor} পাত্র)।")
                        }.trim()
                        "gu" -> buildString {
                            append("$detectedCondition $itemName માટે વ્યવસ્થાપન:\n\n")
                            append("$conditionAdvice\n\n")
                            append("પ્રમાણભૂત રીત: ${rule.disposalMethod} (${rule.targetBinColor} ડબ્બો).")
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
        val indic = listOf(
            "నమస్కారం", "హలో", "హాయ్", "బాగున్నారా",
            "नमस्ते", "नमस्कार", "हेलो", "हाय",
            "வணக்கம்",
            "हॅलो",
            "ನಮಸ್ಕಾರ", "ಹಲೋ", "ಹಾಯ್",
            "നമസ്കാരം", "ഹലോ", "ഹായ്",
            "নমস্কার", "হ্যালো", "হাই",
            "નમસ્તે", "નમસ્કાર", "હેલો", "હાય"
        )
        return words.any { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(q) } ||
                indic.any { q.contains(it) }
    }

    private fun isGratitude(query: String): Boolean {
        val q = query.trim().lowercase()
        val words = listOf("thank you", "thanks", "thank u", "thx")
        val indic = listOf(
            "ధన్యవాదాలు", "థాంక్స్",
            "धन्यवाद", "शुक्रिया",
            "நன்றி",
            "आभार",
            "ಧನ್ಯವಾದಗಳು", "ಥ್ಯಾಂಕ್ಸ್",
            "നന്ദി", "താങ്ക്സ്",
            "ধন্যবাদ", "থ্যাংকস",
            "આભાર", "ધન્યવાદ", "થેન્ક્સ"
        )
        return words.any { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(q) } ||
                indic.any { q.contains(it) }
    }

    private fun isIdentity(query: String): Boolean {
        val q = query.trim().lowercase()
        val phrases = listOf("who are you", "what is your name", "who made you", "what are you")
        val indic = listOf(
            "మీరు ఎవరు", "నీ పేరేంటి", "నువ్వు ఎవరు", "నీ పేరు ఏమిటి",
            "आप कौन हैं", "तुम्हारा नाम क्या है", "तुम कौन हो",
            "நீங்கள் யார்", "உன் பெயர் என்ன",
            "तू कोण आहेस",
            "ನೀವು ಯಾರು", "ನಿನ್ನ ಹೆಸರೇನು", "ನಿಮ್ಮ ಹೆಸರೇನು",
            "നിങ്ങൾ ആരാണ്", "നിന്റെ പേരെന്താണ്", "നിങ്ങളുടെ പേരെന്താണ്",
            "আপনি কে", "তোমার নাম কি", "আপনার নাম কি",
            "તમે કોણ છો", "તમારું નામ શું છે", "તારું નામ શું છે"
        )
        return phrases.any { q.contains(it) } || indic.any { q.contains(it) }
    }

    private fun isHelp(query: String): Boolean {
        val q = query.trim().lowercase()
        val phrases = listOf("help", "what can you do", "how to use", "how do you work", "commands")
        val indic = listOf(
            "సహాయం", "నువ్వు ఏమి చేయగలవు", "ఎలా ఉపయోగించాలి",
            "मदद", "आप क्या कर सकते हैं", "उपयोग कैसे करें",
            "உதவி",
            "मदत",
            "ಸಹಾಯ", "ನೀನು ಏನು ಮಾಡಬಲ್ಲೆ", "ಹೇಗೆ ಬಳಸುವುದು",
            "സഹായം", "നിങ്ങൾക്ക് എന്തുചെയ്യാൻ കഴിയും", "എങ്ങനെ ഉപയോഗിക്കാം",
            "সাহায্য", "তুমি কি করতে পারো", "কীভাবে ব্যবহার করব",
            "મદદ", "તમે શું કરી શકો છો", "કેવી રીતે વાપરવું"
        )
        return phrases.any { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(q) } || indic.any { q.contains(it) }
    }

    private fun isFarewell(query: String): Boolean {
        val q = query.trim().lowercase()
        val words = listOf("bye", "goodbye", "see you", "cya")
        val indic = listOf(
            "టాటా", "వెళ్లొస్తా",
            "अलविदा", "बाय",
            "பிரியாவிடை",
            "ವಿದಾಯ", "ಬರುತ್ತೇನೆ",
            "വിട", "പോയിവരാം",
            "বিদায়", "আসি",
            "આવજો", "અલવિદા"
        )
        return words.any { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(q) } || indic.any { q.contains(it) }
    }

    private fun buildGreetingResponse(lang: String, itemName: String): String = when (lang) {
        "te" -> "నమస్కారం! నేను బాటిల్ AI, మీ పర్యావరణ వ్యర్థాల సహాయకుడిని. ఈరోజు $itemName పారవేత, రీసైక్లింగ్ లేదా శుభ్రపరచడంలో నేను మీకు ఎలా సహాయపడగలను?"
        "hi" -> "नमस्ते! मैं बॉटल AI हूँ, आपका पर्यावरण और पुनर्चक्रण सहायक। $itemName के सुरक्षित निपटान, पुनर्चक्रण या सफाई में मैं आपकी क्या मदद करूँ?"
        "ta" -> "வணக்கம்! நான் பாட்டில் AI, உங்கள் சுற்றுச்சூழல் கழிவு உதவியாளர். $itemName ஐ பாதுகாப்பாக நிர்வகிக்க அல்லது மறுசுழற்சி செய்ய நான் எவ்வாறு உதவ முடியும்?"
        "mr" -> "नमस्कार! मी बॉटल AI आहे, तुमचा पर्यावरण सहाय्यक. $itemName ची योग्य विल्हेवाट, पुनर्वापर किंवा स्वच्छतेमध्ये मी कशी मदत करू?"
        "kn" -> "ನಮಸ್ಕಾರ! ನಾನು ಬಾಟಲ್ AI, ನಿಮ್ಮ ಪರಿಸರ ಮತ್ತು ತ್ಯಾಜ್ಯ ಸಹಾಯಕ. $itemName ಅನ್ನು ಸುರಕ್ಷಿತವಾಗಿ ವಿಲೇವಾರಿ ಮಾಡಲು, ಮರುಬಳಕೆ ಮಾಡಲು ಅಥವಾ ಸ್ವಚ್ಛಗೊಳಿಸಲು ನಾನು ನಿಮಗೆ ಹೇಗೆ ಸಹಾಯ ಮಾಡಲಿ?"
        "ml" -> "നമസ്കാരം! ഞാൻ ബോട്ടിൽ AI, നിങ്ങളുടെ പരിസ്ഥിതി മാലിന്യ സഹായി. $itemName സുരക്ഷിതമായി സംസ്കരിക്കാനോ പുനരുപയോഗിക്കാനോ ഞാൻ എങ്ങനെ സഹായിക്കണം?"
        "bn" -> "নমস্কার! আমি বটল এআই, আপনার পরিবেশ ও বর্জ্য নিষ্পত্তির সহকারী। $itemName নিরাপদে নিষ্পত্তি, পুনর্ব্যবহার বা পরিষ্কার করতে আমি কীভাবে সাহায্য করতে পারি?"
        "gu" -> "નમસ્તે! હું બૉટલ AI છું, તમારો પર્યાવરણ અને કચરા વ્યવસ્થાપન સહાયક. $itemName નો સુરક્ષિત નિકાલ, પુનઃઉપયોગ કે સફાઈ કરવામાં હું તમારી શી મદદ કરી શકું?"
        else -> "Hello! I'm Bottle AI, your friendly waste & recycling assistant. How can I help you manage, clean, or dispose of $itemName today?"
    }

    private fun buildGratitudeResponse(lang: String): String = when (lang) {
        "te" -> "మీకు స్వాగతం! బాధ్యతాయుతంగా వ్యర్థాలను వేరు చేయడం మన పర్యావరణానికి ఎంతో మేలు చేస్తుంది. ఇంకేమైనా సందేహాలు ఉంటే అడగండి!"
        "hi" -> "आपका बहुत-बहुत स्वागत है! जिम्मेदारी से कचरा अलग करना पर्यावरण के लिए बहुत महत्वपूर्ण है। कोई अन्य सवाल हो तो बेझिझक पूछें!"
        "ta" -> "மகிழ்ச்சி! கழிவுகளை பொறுப்புடன் பிரிப்பது நமது பூமிக்கு மிகப்பெரிய நன்மையைத் தரும். மேலும் கேள்விகள் இருந்தால் கேளுங்கள்!"
        "mr" -> "आपले स्वागत आहे! कचऱ्याचे योग्य वर्गीकरण आपल्या पर्यावरणासाठी मोलाचे आहे. इतर काही प्रश्न असल्यास नक्की विचारा!"
        "kn" -> "ನಿಮಗೆ ಸ್ವಾಗತ! ಜವಾಬ್ದಾರಿಯುತವಾಗಿ ತ್ಯಾಜ್ಯ ವಿಂಗಡಿಸುವುದು ನಮ್ಮ ಪರಿಸರಕ್ಕೆ ಅತ್ಯಂತ ಉಪಯುಕ್ತ. ಬೇರೆ ಯಾವುದೇ ಪ್ರಶ್ನೆಗಳಿದ್ದರೆ ಕೇಳಿ!"
        "ml" -> "സ്വാഗതം! മാലിന്യങ്ങൾ ഉത്തരവാദിത്തത്തോടെ തരംതിരിക്കുന്നത് നമ്മുടെ പരിസ്ഥിതിക്ക് വളരെ പ്രധാനമാണ്. കൂടുതൽ സംശയങ്ങളുണ്ടെങ്കിൽ ചോദിക്കൂ!"
        "bn" -> "আপনাকে স্বাগতম! দায়িত্বশীলভাবে বর্জ্য পৃথক করা পরিবেশের জন্য অত্যন্ত উপকারী। অন্য কোনো প্রশ্ন থাকলে সানন্দে জিজ্ঞাসা করুন!"
        "gu" -> "તમારું સ્વાગત છે! જવાબદારીપૂર્વક કચરો અલગ કરવો આપણા પર્યાવરણ માટે ખૂબ જ મહત્વપૂર્ણ છે. અન્ય કોઈ પ્રશ્ન હોય તો જરૂર પૂછો!"
        else -> "You're very welcome! Every small step towards proper waste segregation makes a big difference for our environment. Let me know if you have any other questions!"
    }

    private fun buildIdentityResponse(lang: String): String = when (lang) {
        "te" -> "నేను బాటిల్ AI, మీ ఆఫ్‌లైన్ పర్యావరణ వ్యర్థాల సహాయకుడిని! వస్తువులను ఏ డబ్బాలో వేయాలో, ఎలా రీసైకిల్ చేయాలో మరియు ప్రమాదకర వ్యర్థాలను ఎలా సురక్షితంగా నిర్వహించాలో నేను మీకు మార్గనిర్దేశం చేస్తాను."
        "hi" -> "मैं बॉटल AI हूँ, आपका ऑफ़लाइन पर्यावरण और पुनर्चक्रण सहायक! मैं आपको कचरे की सही डिब्बे में पहचान, पुनर्चक्रण और सुरक्षा नियमों में मार्गदर्शन करता हूँ।"
        "ta" -> "நான் பாட்டில் AI, உங்கள் சுற்றுச்சூழல் கழிவு வழிகாட்டி! குப்பைகளை சரியான தொட்டியில் போடவும், பாதுகாப்பாக மறுசுழற்சி செய்யவும் நான் உதவுகிறேன்."
        "mr" -> "मी बॉटल AI आहे, तुमचा पर्यावरण कचरा व्यवस्थापन सहाय्यक! कचऱ्याचे योग्य कुंडीत वर्गीकरण आणि पुनर्वापरात मी मार्गदर्शन करतो."
        "kn" -> "ನಾನು ಬಾಟಲ್ AI, ನಿಮ್ಮ ಆಫ್‌ಲೈನ್ ಪರಿಸರ ಮತ್ತು ತ್ಯಾಜ್ಯ ಸಹಾಯಕ! ಕಸವನ್ನು ಸರಿಯಾದ ಬುಟ್ಟಿಯಲ್ಲಿ ವಿಂಗಡಿಸಲು, ಮರುಬಳಕೆ ಮಾಡಲು ಮತ್ತು ಅಪಾಯಕಾರಿ ವಸ್ತುಗಳನ್ನು ಸುರಕ್ಷಿತವಾಗಿ ನಿರ್ವಹಿಸಲು ನಾನು ಮಾರ್ಗದರ್ಶನ ನೀಡುತ್ತೇನೆ."
        "ml" -> "ഞാൻ ബോട്ടിൽ AI, നിങ്ങളുടെ ഓഫ്‌ലൈൻ പരിസ്ഥിതി മാലിന്യ സഹായി! മാലിന്യങ്ങൾ ശരിയായ തൊട്ടിയിൽ തരംതിരിക്കാനും പുനരുപയോഗിക്കാനും ഞാൻ സഹായിക്കുന്നു."
        "bn" -> "আমি বটল এআই, আপনার অফলাইন পরিবেশ ও বর্জ্য সহকারী! বর্জ্য সঠিক পাত্রে আলাদা করতে, পুনর্ব্যবহার করতে এবং বিপজ্জনক বস্তু নিরাপদে পরিচালনা করতে আমি নির্দেশিকা দিই।"
        "gu" -> "હું બૉટલ AI છું, તમારો ઑફલાઇન પર્યાવરણ અને કચરા વ્યવસ્થાપન સહાયક! કચરાને યોગ્ય ડબ્બામાં વર્ગીકૃત કરવા, પુનઃઉપયોગ કરવા અને જોખમી સામગ્રીના સુરક્ષિત નિકાલમાં હું માર્ગદર્શન આપું છું."
        else -> "I'm Bottle AI, your on-device environmental sanitation and recycling assistant! I help you identify waste, choose the right municipal bins, learn upcycling ideas, and handle hazardous materials safely."
    }

    private fun buildHelpResponse(lang: String): String = when (lang) {
        "te" -> "నేను మీకు ఈ క్రింది విధాలుగా సహాయపడగలను:\n• సరైన రంగు డబ్బాను ఎంచుకోవడం (రీసైకిల్ కోసం నీలం, తడి చెత్త కోసం ఆకుపచ్చ, ప్రమాదకరమైన వాటికి ఎరుపు, ఇ-వ్యర్థాలకు నారింజ).\n• రీసైక్లింగ్ చేసే ముందు శుభ్రపరిచే సూచనలు.\n• సృజనాత్మక పునర్వినియోగ ఆలోచనలు.\n• ప్రమాదకర వ్యర్థాల సురక్షిత నిర్వహణ."
        "hi" -> "मैं आपकी इन मामलों में मदद कर सकता हूँ:\n• सही रंग का डिब्बा चुनना (पुनर्चक्रण के लिए नीला, गीले कचरे के लिए हरा, खतरनाक के लिए लाल, ई-कचरे के लिए नारंगी)।\n• फेंकने से पहले सफाई और तैयारी के टिप्स।\n• रचनात्मक पुनः उपयोग के विचार।\n• खतरनाक या टूटी हुई वस्तुओं की सुरक्षा।"
        "ta" -> "நான் உங்களுக்கு உதவக்கூடிய வழிகள்:\n• சரியான வண்ணத் தொட்டியைத் தேர்ந்தெடுப்பது.\n• மறுசுழற்சிக்கு முன் சுத்தம் செய்யும் குறிப்புகள்.\n• பயனுள்ள மறுபயன்பாட்டு யோசனைகள்.\n• அபாயகரமான பொருட்களுக்கான பாதுகாப்பு நெறிமுறைகள்."
        "mr" -> "मी आपल्याला पुढील प्रकारे मदत करू शकतो:\n• योग्य रंगाची कुंडी निवडणे.\n• पुनर्वापरापूर्वी स्वच्छतेच्या टिप्स.\n• सर्जनशील पुनर्वापर कल्पना.\n• घातक वस्तूंची सुरक्षित हाताळणी."
        "kn" -> "ನಾನು ಈ ಕೆಳಗಿನಂತೆ ನಿಮಗೆ ಸಹಾಯ ಮಾಡಬಲ್ಲೆ:\n• ಸರಿಯಾದ ಬಣ್ಣದ ಬುಟ್ಟಿ ಆಯ್ಕೆ (ಮರುಬಳಕೆಗೆ ನೀಲಿ, ಹಸಿ ಕಸಕ್ಕೆ ಹಸಿರು, ಅಪಾಯಕಾರಿಗೆ ಕೆಂಪು, ಇ-ತ್ಯಾಜ್ಯಕ್ಕೆ ಕಿತ್ತಳೆ).\n• ಮರುಬಳಕೆಗೆ ಮುನ್ನ ಶುಚಿಗೊಳಿಸುವ ಸಲಹೆಗಳು.\n• ಸೃಜನಶೀಲ ಮರುಬಳಕೆ ಕಲ್ಪನೆಗಳು.\n• ಅಪಾಯಕಾರಿ ತ್ಯಾಜ್ಯದ ಸುರಕ್ಷಿತ ನಿರ್ವಹಣೆ."
        "ml" -> "എനിക്ക് നിങ്ങളെ ഈ വിധങ്ങളിൽ സഹായിക്കാനാകും:\n• ശരിയായ നിറമുള്ള തൊട്ടി കണ്ടെത്തുക (നീല, പച്ച, ചുവപ്പ്, ഓറഞ്ച്).\n• പുനരുപയോഗത്തിന് മുമ്പുള്ള വൃത്തിയാക്കൽ നുറുങ്ങുകൾ.\n• സർഗ്ഗാത്മക പുനരുപയോഗ ആശയങ്ങൾ.\n• അപകടകരമായ വസ്തുക്കളുടെ സുരക്ഷിത കൈകാര്യം ചെയ്യൽ."
        "bn" -> "আমি আপনাকে নিম্নলিখিত উপায়ে সাহায্য করতে পারি:\n• সঠিক রঙের পাত্র নির্বাচন (নীল, সবুজ, লাল, কমলা)।\n• পুনর্ব্যবহারের আগে পরিষ্কার ও প্রস্তুতি টিপস।\n• সৃজনশীল পুনর্ব্যবহার ধারণা।\n• বিপজ্জনক বর্জ্যের নিরাপদ হ্যান্ডলিং।"
        "gu" -> "હું તમને નીચેની રીતે મદદ કરી શકું છું:\n• સાચો રંગીન ડબ્બો પસંદ કરવો (વાદળી, લીલો, લાલ, નારંગી).\n• રિસાયક્લિંગ પહેલાં સફાઈની ટિપ્સ.\n• સર્જનાત્મક પુનઃઉપયોગના વિચારો.\n• જોખમી સામગ્રીનું સુરક્ષિત સંચાલન."
        else -> "I can help you in several ways:\n• Find the right colored bin (Blue for dry recyclables, Green for wet organic, Red for hazardous, Orange for e-waste).\n• Give cleaning and preparation steps before recycling.\n• Suggest creative DIY upcycling ideas.\n• Guide safe handling for broken or hazardous items."
    }

    private fun buildFarewellResponse(lang: String): String = when (lang) {
        "te" -> "వెళ్లొస్తాను! మన భూమిని కాపాడుతున్నందుకు ధన్యవాదాలు. బాధ్యతాయుతంగా వ్యర్థాలను వేరు చేయండి!"
        "hi" -> "अलविदा! हमारे पर्यावरण का ध्यान रखने के लिए धन्यवाद। जिम्मेदारी से कचरा अलग करते रहें!"
        "ta" -> "பிரியாவிடை! நமது பூமியை பாதுகாப்பதற்கு நன்றி. தொடர்ந்து கழிவுகளை முறையாகப் பிரிக்கவும்!"
        "mr" -> "निरोप! पर्यावरणाची काळजी घेतल्याबद्दल धन्यवाद. कचऱ्याचे योग्य वर्गीकरण करत राहा!"
        "kn" -> "ವಿದಾಯ! ನಮ್ಮ ಪರಿಸರವನ್ನು ರಕ್ಷಿಸುತ್ತಿರುವುದಕ್ಕೆ ಧನ್ಯವಾದಗಳು. ಜವಾಬ್ದಾರಿಯುತವಾಗಿ ಕಸ ವಿಂಗಡಿಸುತ್ತಿರಿ!"
        "ml" -> "വിട! നമ്മുടെ പരിസ്ഥിതിയെ സംരക്ഷിക്കുന്നതിന് നന്ദി. ഉത്തരവാദിത്തത്തോടെ മാലിന്യം തരംതിരിക്കൂ!"
        "bn" -> "বিদায়! আমাদের পরিবেশ রক্ষার জন্য ধন্যবাদ। দায়িত্বশীলভাবে বর্জ্য পৃথক করতে থাকুন!"
        "gu" -> "આવજો! આપણા પર્યાવરણનું ધ્યાન રાખવા બદલ આભાર. જવાબદારીપૂર્વક કચરો અલગ કરતા રહો!"
        else -> "Goodbye! Thank you for taking care of our planet. Keep segregating waste responsibly!"
    }

    private fun isDetailRequested(query: String): Boolean {
        val q = query.lowercase()
        return q.contains(Regex("\\b(detail|details|step|steps|explain|comprehensive|full|more|how exactly)\\b")) ||
                q.contains("వివరంగా") || q.contains("దశలు") || q.contains("వివరించు") ||
                q.contains("विस्तार") || q.contains("चरण") ||
                q.contains("விளக்கம்") || q.contains("படிகள்") ||
                q.contains("सविस्तर") || q.contains("पायऱ्या") ||
                q.contains("ವಿವರವಾಗಿ") || q.contains("ಹಂತಗಳು") || q.contains("ವಿವರಿಸು") ||
                q.contains("വിശദമായി") || q.contains("പടികൾ") || q.contains("വിശദീകരിക്കുക") ||
                q.contains("বিস্তারিত") || q.contains("ধাপ") || q.contains("ব্যাখ্যা") ||
                q.contains("વિગતવાર") || q.contains("પગલાં") || q.contains("સમજાવો")
    }

    private fun isBurnQuery(query: String): Boolean {
        val q = query.lowercase()
        return q.contains(Regex("\\b(burn|fire|incinerat|smoke)\\b")) ||
                q.contains("जलाना") || q.contains("आग") ||
                q.contains("కాల్చ") || q.contains("మంట") ||
                q.contains("எரி") || q.contains("தீ") ||
                q.contains("जाळ") ||
                q.contains("ಸುಡು") || q.contains("ಬೆಂಕಿ") ||
                q.contains("കത്തിക്കുക") || q.contains("തീ") ||
                q.contains("পোড়ানো") || q.contains("আগুন") ||
                q.contains("બાળવું") || q.contains("આગ")
    }

    private fun isBatteryQuery(query: String): Boolean {
        val q = query.lowercase()
        return q.contains(Regex("\\b(battery|batteries|cell|cells)\\b")) ||
                q.contains("बैटरी") || q.contains("బ్యాటరీ") || q.contains("பேட்டரி") || q.contains("बॅटरी") ||
                q.contains("ಬ್ಯಾಟರಿ") || q.contains("ബാറ്ററി") || q.contains("ব্যাটারি") || q.contains("બેટરી")
    }

    private fun isDecomposeQuery(query: String): Boolean {
        val q = query.lowercase()
        return q.contains(Regex("\\b(decompose|decay|rot|break down|how long)\\b")) ||
                q.contains("गलने") || q.contains("सड़ने") ||
                q.contains("కుళ్ళిపోవడానికి") || q.contains("మட்க") ||
                q.contains("विघटन") || q.contains("कुजण्यासाठी") ||
                q.contains("ಕೊಳೆಯಲು") || q.contains("ಕರಗಲು") ||
                q.contains("മണ്ണിൽ ചേരാൻ") || q.contains("നശിക്കാൻ") ||
                q.contains("পচতে") || q.contains("মিশতে") ||
                q.contains("વિઘટન") || q.contains("ઓગળતા")
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
            "कचरा वर्गीकरण", "कचरा कसा वेगळा", "कुंड्यांचे रंग", "कचऱ्याचे प्रकार",
            "ಕಸ ವಿಂಗಡಣೆ", "ಬುಟ್ಟಿ", "ತ್ಯಾಜ್ಯ ವಿಂಗಡಣೆ", "ಬುಟ್ಟಿಗಳ ಬಣ್ಣ",
            "മാലിന്യ തരംതിരിക്കൽ", "തൊട്ടികൾ", "മാലിന്യ തരങ്ങൾ",
            "বর্জ্য পৃথকীকরণ", "পাত্রের রঙ", "বর্জ্যের ধরন",
            "કચરો અલગ", "ડબ્બાના રંગ", "કચરાનું વર્ગીકરણ"
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
        "kn" -> """
            ನಮಸ್ಕಾರ! ನೀವು ಕೇಳಿದ ವಸ್ತುವಿನ ವಿವರಗಳನ್ನು ಪರಿಶೀಲಿಸುತ್ತಿದ್ದೇನೆ.

            ಸಾಮಾನ್ಯ ತ್ಯಾಜ್ಯ ವಿಂಗಡಣಾ ನಿಯಮಗಳು:
            🟢 ಹಸಿರು ಬುಟ್ಟಿ (Green): ಹಸಿ ಸಾವಯವ ಕಸ, ಹಣ್ಣು-ತರಕಾರಿ ಸಿಪ್ಪೆಗಳು, ಆಹಾರ.
            🔵 ನೀಲಿ ಬುಟ್ಟಿ (Blue): ಒಣ ಮರುಬಳಕೆ ವಸ್ತುಗಳು (ಪ್ಲಾಸ್ಟಿಕ್, ಕಾಗದ, ಗಾಜು, ಲೋಹ).
            🟠 ಕಿತ್ತಳೆ ಬುಟ್ಟಿ (Orange): ಇ-ತ್ಯಾಜ್ಯ, ಮೊಬೈಲ್, ಚಾರ್ಜರ್, ತಂತಿಗಳು.
            🔴 ಕೆಂಪು ಬುಟ್ಟಿ (Red): ಅಪಾಯಕಾರಿ ತ್ಯಾಜ್ಯ, ಬ್ಯಾಟರಿ, ಔಷಧಿ, ಸ್ಯಾನಿಟರಿ ತ್ಯಾಜ್ಯ.
            ⚫ ಕಪ್ಪು ಬುಟ್ಟಿ (Black): ನೆಲದ ಕಸ/ಧೂಳು, ಥರ್ಮೋಕೋಲ್, ಕೊಳೆಯದ ಕಸ.

            ದಯವಿಟ್ಟು ವಸ್ತುವಿನ ಹೆಸರನ್ನು ತಿಳಿಸಿ ಅಥವಾ ಫೋಟೋ ಸ್ಕ್ಯಾನ್ ಮಾಡಿ, ನಾನು ಸರಿಯಾದ ವಿಧಾನ ತಿಳಿಸುತ್ತೇನೆ! 😊
        """.trimIndent()
        "ml" -> """
            നമസ്കാരം! നിങ്ങൾ ചോദിച്ച ഇനത്തിന്റെ വിവരങ്ങൾ പരിശോധിക്കുന്നു.

            പ്രധാന മാലിന്യ തരംതിരിക്കൽ നിയമങ്ങൾ:
            🟢 പച്ച തൊട്ടി (Green): നനഞ്ഞ ജൈവ മാലിന്യം, പച്ചക്കറി അവശിഷ്ടങ്ങൾ, ഭക്ഷണം.
            🔵 നീല തൊട്ടി (Blue): ഉണങ്ങിയ പുനരുപയോഗ മാലിന്യം (പ്ലാസ്റ്റിക്, പേപ്പർ, ഗ്ലാസ്).
            🟠 ഓറഞ്ച് തൊട്ടി (Orange): ഇ-മാലിന്യം, മൊബൈൽ, ചാർജറുകൾ, വയറുകൾ.
            🔴 ചുവന്ന തൊട്ടി (Red): അപകടകരമായ മാലിന്യം, ബാറ്ററികൾ, മരുന്നുകൾ.
            ⚫ കറുത്ത തൊട്ടി (Black): പൊടിപടലങ്ങൾ, തെർമോകോൾ.

            ദയവായി ഇനത്തിന്റെ പേര് പറയുക അല്ലെങ്കിൽ ഫോട്ടോ സ്കാൻ ചെയ്യുക, ഞാൻ സഹായിക്കാം! 😊
        """.trimIndent()
        "bn" -> """
            নমস্কার! আপনি যে বস্তুর কথা জানতে চেয়েছেন তার তথ্য দেখছি।

            বর্জ্য পৃথকীকরণের প্রধান নিয়মাবলী:
            🟢 সবুজ পাত্র (Green): ভেজা জৈব বর্জ্য, ফল ও সবজির খোসা, খাবার।
            🔵 নীল পাত্র (Blue): শুকনো পুনর্ব্যবহারযোগ্য (প্লাস্টিক, কাগজ, কাঁচ, ধাতু)।
            🟠 কমলা পাত্র (Orange): ই-বর্জ্য, পুরানো মোবাইল, চার্জার, তার।
            🔴 লাল পাত্র (Red): বিপজ্জনক বর্জ্য, ব্যাটারি, ওষুধ, স্যানিটারি বর্জ্য।
            ⚫ কালো পাত্র (Black): মেঝে ঝাড়ুর ধুলো, থার্মোকল।

            অনুগ্রহ করে বস্তুটির নাম বলুন বা ছবি স্ক্যান করুন, আমি সঠিক পদ্ধতি জানিয়ে দেব! 😊
        """.trimIndent()
        "gu" -> """
            નમસ્તે! તમે પૂછેલી વસ્તુની વિગત જોઈ રહ્યો છું.

            કચરો અલગ કરવાના મુખ્ય નિયમો:
            🟢 લીલો ડબ્બો (Green): ભીનો જૈવિક કચરો, ફળ-શાકભાજીની છાલ, વધેલું ભોજન.
            🔵 વાદળી ડબ્બો (Blue): સૂકો રિસાયક્લેબલ કચરો (પ્લાસ્ટિક, કાગળ, કાચ, ધાતુ).
            🟠 નારંગી ડબ્બો (Orange): ઇ-કચરો, જૂના મોબાઇલ, ચાર્જર, વાયર.
            🔴 લાલ ડબ્બો (Red): જોખમી કચરો, બેટરી, દવાઓ, સેનિટરી કચરો.
            ⚫ કાળો ડબ્બો (Black): ધૂળ, થર્મોકોલ, વિઘટન ન થતો કચરો.

            કૃપા કરીને વસ્તુનું નામ જણાવો અથવા ફોટો સ્કેન કરો, હું સાચી પદ્ધતિ જણાવીશ! 😊
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

        "kn" -> """
            ತ್ಯಾಜ್ಯ ವಿಂಗಡಣೆಯ ಸರಳ ಮತ್ತು ವೈಜ್ಞಾನಿಕ 5 ಬುಟ್ಟಿಗಳ ಮಾರ್ಗದರ್ಶಿ:

            🟢 1. ಹಸಿರು ಬುಟ್ಟಿ (Green Bin) — ಹಸಿ ಸಾವಯವ ತ್ಯಾಜ್ಯ
            • ಹಾಕಬೇಕಾದವು: ಹಣ್ಣು ಮತ್ತು ತರಕಾರಿ ಸಿಪ್ಪೆಗಳು, ಉಳಿದ ಆಹಾರ, ಚಹಾ ಪುಡಿ, ಎಲೆಗಳು, ಮೊಟ್ಟೆಯ ಸಿಪ್ಪೆ.
            • ಹಾಕಬಾರದವು: ಪ್ಲಾಸ್ಟಿಕ್ ಕವರ್‌ಗಳು, ಹಾಲಿನ ಪ್ಯಾಕೆಟ್‌ಗಳು ಅಥವಾ ರಬ್ಬರ್ ಹಾಕಬೇಡಿ.
            • ಏಕೆ?: ಈ ಕಸ ಕೊಳೆತು ನೈಸರ್ಗಿಕ ಗೊಬ್ಬರ ಅಥವಾ ಬಯೋಗ್ಯಾಸ್ ಆಗಿ ಬದಲಾಗುತ್ತದೆ!

            🔵 2. ನೀಲಿ ಬುಟ್ಟಿ (Blue Bin) — ಒಣ ಮರುಬಳಕೆ ತ್ಯಾಜ್ಯ
            • ಹಾಕಬೇಕಾದವು: ಪ್ಲಾಸ್ಟಿಕ್ ಬಾಟಲಿಗಳು, ಕಾಗದ, ರಟ್ಟಿನ ಪೆಟ್ಟಿಗೆಗಳು, ಗಾಜಿನ ಬಾಟಲಿಗಳು, ಲೋಹದ ಕ್ಯಾನ್‌ಗಳು.
            • ಸುಲಭ ಸಲಹೆ: ಆಹಾರದ ಡಬ್ಬಿಗಳನ್ನು ಸ್ವಲ್ಪ ನೀರಿನಲ್ಲಿ ತೊಳೆದು ಒಣಗಿಸಿ ಹಾಕಿ.
            • ಏಕೆ?: ಇವು ಮರುಬಳಕೆ ಕಾರ್ಖಾನೆಗಳಿಗೆ ಹೋಗಿ ಹೊಸ ವಸ್ತುಗಳಾಗುತ್ತವೆ, ಮರಗಳನ್ನು ಉಳಿಸುತ್ತವೆ!

            🟠 3. ಕಿತ್ತಳೆ ಬುಟ್ಟಿ (Orange Bin) — ಇ-ತ್ಯಾಜ್ಯ / ಎಲೆಕ್ಟ್ರಾನಿಕ್ಸ್
            • ಹಾಕಬೇಕಾದವು: ಹಳೆಯ ಮೊಬೈಲ್‌ಗಳು, ಚಾರ್ಜರ್‌ಗಳು, ತಂತಿಗಳು, ಇಯರ್‌ಫೋನ್‌ಗಳು, ರಿಮೋಟ್‌ಗಳು, ಕಂಪ್ಯೂಟರ್ ಭಾಗಗಳು.
            • ಸುಲಭ ಸಲಹೆ: ಉಪಕರಣಗಳನ್ನು ಒಡೆಯಬೇಡಿ ಅಥವಾ ಸುಡಬೇಡಿ.
            • ಏಕೆ?: ಇವುಗಳಲ್ಲಿ ತಾಮ್ರವಿರುತ್ತದೆ, ಜೊತೆಗೆ ಸೀಸ ಮತ್ತು ಪಾದರಸದಂತಹ ವಿಷಕಾರಿ ರಾಸಾಯನಿಕಗಳೂ ಇರುತ್ತವೆ. ಸರಿಯಾದ ಮರುಬಳಕೆಯು ನೀರು ಮತ್ತು ಭೂಮಿಯನ್ನು ರಕ್ಷಿಸುತ್ತದೆ.

            🔴 4. ಕೆಂಪು ಬುಟ್ಟಿ (Red Bin) — ಅಪಾಯಕಾರಿ & ನೈರ್ಮಲ್ಯ ತ್ಯಾಜ್ಯ
            • ಹಾಕಬೇಕಾದವು: ಬಳಸಿದ ಬ್ಯಾಟರಿಗಳು, ಟ್ಯೂಬ್‌ಲೈಟ್/ಬಲ್ಬ್‌ಗಳು, ಬಣ್ಣಗಳು, ಕೀಟನಾಶಕಗಳು, ಔಷಧಗಳು, ಸ್ಯಾನಿಟರಿ ಪ್ಯಾಡ್‌ಗಳು ಮತ್ತು ಡೈಪರ್‌ಗಳು.
            • ಸುಲಭ ಸಲಹೆ: ಸ್ಯಾನಿಟರಿ ತ್ಯಾಜ್ಯವನ್ನು ದಿನಪತ್ರಿಕೆಯಲ್ಲಿ ಸುತ್ತಿ ಹಾಕಿ; ಬ್ಯಾಟರಿ ತುದಿಗೆ ಟೇಪ್ ಅಂಟಿಸಿ.
            • ಏಕೆ?: ಸ್ವಚ್ಛತಾ ಕಾರ್ಮಿಕರನ್ನು ರಕ್ಷಿಸುತ್ತದೆ ಮತ್ತು ಸೋಂಕು ಹಾಗೂ ಬೆಂಕಿಯ ಅಪಾಯವನ್ನು ತಡೆಯುತ್ತದೆ.

            ⚫ 5. ಕಪ್ಪು ಬುಟ್ಟಿ (Black Bin) — ಮರುಬಳಕೆಯಾಗದ ನಿಷ್ಕ್ರಿಯ ಕಸ
            • ಹಾಕಬೇಕಾದವು: ನೆಲದ ಧೂಳು, ಒಡೆದ ಸೆರಾಮಿಕ್ ವಸ್ತುಗಳು, ಥರ್ಮೋಕೋಲ್, ಅತಿಯಾದ ಎಣ್ಣೆಯುಕ್ತ ಕಸ.
            • ಏಕೆ?: ಇದು ಸುರಕ್ಷಿತವಾಗಿ ವೈಜ್ಞಾನಿಕ ಲ್ಯಾಂಡ್‌ಫಿಲ್‌ಗೆ ರವಾನೆಯಾಗುತ್ತದೆ.

            ಕಸವನ್ನು ಸರಿಯಾಗಿ ವಿಂಗಡಿಸೋಣ, ನಮ್ಮ ಪರಿಸರವನ್ನು ಸ್ವಚ್ಛವಾಗಿಡೋಣ! ಯಾವುದೇ ಪ್ರಶ್ನೆಗಳಿದ್ದರೆ ಖಂಡಿತ ಕೇಳಿ! 😊
        """.trimIndent()

        "ml" -> """
            മാലിന്യ തരംതിരിക്കലിന്റെ ലളിതമായ 5 തൊട്ടികളുടെ സമഗ്ര വഴികാട്ടി:

            🟢 1. പച്ച തൊട്ടി (Green Bin) — നനഞ്ഞ ജൈവ മാലിന്യങ്ങൾ
            • നിക്ഷേപിക്കേണ്ടവ: പഴം-പച്ചക്കറി അവശിഷ്ടങ്ങൾ, ബാക്കി വന്ന ഭക്ഷണം, ചായപ്പൊടി, ഇലകൾ, മുട്ടത്തോട്.
            • നിക്ഷേപിക്കാൻ പാടില്ലാത്തവ: പ്ലാസ്റ്റിക് കവറുകൾ, പാൽ കവറുകൾ, റബ്ബർ എന്നിവ ഒരിക്കലും ഇടരുത്.
            • എന്തുകൊണ്ട്?: ഇത് സ്വാഭാവികമായി ജീർണ്ണിച്ച് ജൈവവളമോ ബയോഗ്യാസോ ആയി മാറുന്നു!

            🔵 2. നീല തൊട്ടി (Blue Bin) — ഉണങ്ങിയ പുനരുപയോഗ മാലിന്യങ്ങൾ
            • നിക്ഷേപിക്കേണ്ടവ: പ്ലാസ്റ്റിക് കുപ്പികൾ, പേപ്പർ, കാർഡ്ബോർഡ് പെട്ടികൾ, ഗ്ലാസ് കുപ്പികൾ, ടിന്നുകൾ.
            • ലളിതമായ നുറുങ്ങ്: ഭക്ഷണ പാത്രങ്ങൾ അല്പം വെള്ളത്തിൽ കഴുകി ഉണക്കി ഇടുക.
            • എന്തുകൊണ്ട്?: ഇവ റീസൈക്ലിംഗ് ഫാക്ടറികളിൽ പുതിയ ഉൽപ്പന്നങ്ങളായി മാറുന്നു, മരങ്ങളെ സംരക്ഷിക്കുന്നു!

            🟠 3. ഓറഞ്ച് തൊട്ടി (Orange Bin) — ഇ-മാലിന്യം / ഇലക്ട്രോണിക്സ്
            • നിക്ഷേപിക്കേണ്ടവ: പഴയ മൊബൈലുകൾ, ചാർജറുകൾ, വയറുകൾ, ഇയർഫോണുകൾ, റിമോട്ടുകൾ, കമ്പ്യൂട്ടർ ഭാഗങ്ങൾ.
            • ലളിതമായ നുറുങ്ങ്: ഉപകരണങ്ങൾ തകർക്കുകയോ കത്തിക്കുകയോ ചെയ്യരുത്.
            • എന്തുകൊണ്ട്?: ഇവയിൽ വിലപിടിപ്പുള്ള ലോഹങ്ങളും, ഈയം, രസം തുടങ്ങിയ വിഷാംശങ്ങളുമുണ്ട്. ശരിയായ റീസൈക്ലിംഗ് ഭൂഗർഭജലം സംരക്ഷിക്കുന്നു.

            🔴 4. ചുവന്ന തൊട്ടി (Red Bin) — അപകടകരമായ & ശുചിത്വ മാലിന്യങ്ങൾ
            • നിക്ഷേപിക്കേണ്ടവ: ഉപയോഗിച്ച ബാറ്ററികൾ, ട്യൂബ് ലൈറ്റ്/ബൾബുകൾ, പെയിന്റ്, കീടനാശിനികൾ, മരുന്നുകൾ, സാനിറ്ററി പാഡുകൾ, ഡയപ്പറുകൾ.
            • ലളിതമായ നുറുങ്ങ്: സാനിറ്ററി മാലിന്യങ്ങൾ പേപ്പറിൽ പൊതിഞ്ഞ് ഇടുക; ബാറ്ററി അറ്റങ്ങളിൽ ടേപ്പ് ഒട്ടിക്കുക.
            • എന്തുകൊണ്ട്?: ശുചീകരണ തൊഴിലാളികളുടെ ആരോഗ്യം സംരക്ഷിക്കുകയും അണുബാധയും തീപിടുത്തവും തടയുകയും ചെയ്യുന്നു.

            ⚫ 5. കറുത്ത തൊട്ടി (Black Bin) — പുനരുപയോഗിക്കാൻ കഴിയാത്ത മാലിന്യങ്ങൾ
            • നിക്ഷേപിക്കേണ്ടവ: അടിച്ചുവാരിയ പൊടി, പൊട്ടിയ സെറാമിക് സാധനങ്ങൾ, തെർമോകോൾ.
            • എന്തുകൊണ്ട്?: ശാസ്ത്രീയ ലാൻഡ്ഫില്ലുകളിലേക്ക് സുരക്ഷിതമായി മാറ്റുന്നു.

            മാലിന്യങ്ങൾ കൃത്യമായി വേർതിരിച്ച് നമ്മുടെ നാടിനെ ശുചിയായി സൂക്ഷിക്കാം! എന്തെങ്കിലും സംശയമുണ്ടെങ്കിൽ ചോദിക്കൂ! 😊
        """.trimIndent()

        "bn" -> """
            বর্জ্য পৃথকীকরণের সহজ ও বৈজ্ঞানিক ৫টি পাত্রের বিস্তারিত নির্দেশিকা:

            🟢 ১. সবুজ পাত্র (Green Bin) — ভেজা জৈব বর্জ্য
            • যা ফেলবেন: ফল ও সবজির খোসা, এঁটো খাবার, চা পাতা, গাছের পাতা, ডিমের খোসা।
            • যা ফেলবেন না: প্লাস্টিক ব্যাগ, দুধের প্যাকেট বা রাবার একেবারেই ফেলবেন না।
            • কেন?: এই বর্জ্য পচে গিয়ে মূল্যবান জৈব সার বা বায়োগ্যাস তৈরি হয়!

            🔵 ২. নীল পাত্র (Blue Bin) — শুকনো পুনর্ব্যবহারযোগ্য বর্জ্য
            • যা ফেলবেন: প্লাস্টিকের বোতল, কাগজ, কার্ডবোর্ডের বাক্স, কাঁচের বোতল, মেটালের ক্যান।
            • সহজ টিপ: খাবারের পাত্রগুলি সামান্য জল দিয়ে ধুয়ে শুকিয়ে ফেলুন।
            • কেন?: এগুলো পুনর্ব্যবহার কারখানায় গিয়ে নতুন পণ্য তৈরি করে এবং গাছ রক্ষা করে!

            🟠 ৩. কমলা পাত্র (Orange Bin) — ই-বর্জ্য / ইলেকট্রনিক্স
            • যা ফেলবেন: পুরানো মোবাইল, চার্জার, তার, হেডফোন, রিমোট, কীবোর্ড, মাউস ইত্যাদি।
            • সহজ টিপ: ডিভাইসগুলি ভাঙবেন না বা আগুনে পোড়াবেন না।
            • কেন?: এগুলোতে তামা এবং মূল্যবান ধাতু থাকে, সাথে সীসা ও পারদের মতো বিষাক্ত পদার্থ থাকে। সঠিক পুনর্ব্যবহার জল ও মাটি রক্ষা করে।

            🔴 ৪. লাল পাত্র (Red Bin) — বিপজ্জনক ও স্বাস্থ্য বর্জ্য
            • যা ফেলবেন: পুরানো ব্যাটারি, টিউবলাইট/বাল্ব, রঙ, কীটনাশক, ওষুধ, স্যানিটারি প্যাড ও ডায়াপার।
            • সহজ টিপ: স্যানিটারি বর্জ্য খবরের কাগজে মুড়িয়ে ফেলুন; ব্যাটারির প্রান্তে টেপ লাগান।
            • কেন?: পরিচ্ছন্নতাকর্মীদের সুরক্ষা দেয় এবং সংক্রমণ ও অগ্নিকাণ্ড রোধ করে।

            ⚫ ৫. কালো পাত্র (Black Bin) — অপুনর্ব্যবহারযোগ্য বর্জ্য
            • যা ফেলবেন: মেঝের ঝাড়ুর ধুলো, ভাঙা সিরামিক, থার্মোকল, অতিরিক্ত নোংরা বর্জ্য।
            • কেন?: এটি নিরাপদে বৈজ্ঞানিক ল্যান্ডফিলে পাঠানো হয়।

            বর্জ্য সঠিকভাবে আলাদা করে আমাদের পরিবেশ পরিচ্ছন্ন রাখি! কোনো প্রশ্ন থাকলে নিশ্চিন্তে জিজ্ঞাসা করুন! 😊
        """.trimIndent()

        "gu" -> """
            કચરાના યોગ્ય વર્ગીકરણ માટે સરળ અને વૈજ્ઞાનિક 5 ડબ્બાની માર્ગદર્શિકા:

            🟢 1. લીલો ડબ્બો (Green Bin) — ભીનો જૈવિક કચરો
            • શું નાખવું: ફળ અને શાકભાજીની છાલ, વધેલું ભોજન, ચાની પત્તી, બગીચાના પાંદડાં, ઈંડાંની છાલ.
            • શું ન નાખવું: પ્લાસ્ટિકની કોથળીઓ, દૂધની થેલીઓ કે રબર બિલકુલ ન નાખો.
            • શા માટે?: આ કચરો સડીને ઉત્તમ કુદરતી ખાતર કે બાયોગેસ બનાવે છે!

            🔵 2. વાદળી ડબ્બો (Blue Bin) — સૂકો રિસાયક્લેબલ કચરો
            • શું નાખવું: પ્લાસ્ટિકની બોટલો, કાગળ, પૂંઠાના બોક્સ, કાચની બોટલો, ધાતુના ડબ્બા/કેન.
            • સરળ ટિપ: ખોરાકના ડબ્બાને થોડા પાણીથી ધોઈને સૂકવીને નાખો.
            • શા માટે?: આ કચરો ફેક્ટરીઓમાં જઈને નવી વસ્તુઓ બનાવે છે અને વૃક્ષો બચાવે છે!

            🟠 3. નારંગી ડબ્બો (Orange Bin) — ઇ-કચરો / ઇલેક્ટ્રોનિક્સ
            • શું નાખવું: જૂના મોબાઇલ, ચાર્જર, વાયર, હેડફોન, રિમોટ, કીબોર્ડ, કમ્પ્યુટરના ભાગો.
            • સરળ ટિપ: ઉપકરણોને તોડશો કે બાળશો નહીં.
            • શા માટે?: આમાં તાંબુ હોય છે, પણ સીસું અને પારો જેવા ઝેરી તત્વો પણ હોય છે. યોગ્ય રિસાયક્લિંગથી પાણી અને જમીન સુરક્ષિત રહે છે.

            🔴 4. લાલ ડબ્બો (Red Bin) — જોખમી અને સેનિટરી કચરો
            • શું નાખવું: વપરાયેલી બેટરીઓ, ટ્યુબલાઇટ/બલ્બ, પેઇન્ટ, જંતુનાશકો, દવાઓ, સેનિટરી પેડ અને ડાયપર.
            • સરળ ટિપ: સેનિટરી કચરાને છાપામાં લપેટીને નાખો; બેટરીના છેડા પર ટેપ ચોંટાડો.
            • શા માટે?: સફાઈ કામદારોને રોગોથી બચાવે છે અને આગનો ખતરો ટાળે છે.

            ⚫ 5. કાળો ડબ્બો (Black Bin) — બિન-રિસાયક્લેબલ કચરો
            • શું નાખવું: કચરા-પોતાની ધૂળ, તૂટેલા સિરામિક વાસણો, થર્મોકોલ.
            • શા માટે?: આ કચરો સુરક્ષિત રીતે વૈજ્ઞાનિક લેન્ડફિલમાં મોકલવામાં આવે છે.

            કચરાનું સાચું વર્ગીકરણ કરીએ, આપણું પર્યાવરણ સ્વચ્છ રાખીએ! કોઈ પણ પ્રશ્ન હોય તો જરૂર પૂછો! 😊
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

            "kn" -> buildString {
                append("ನಮಸ್ಕಾರ! ನಿಮ್ಮ ವಸ್ತು: $itemName.\n\n")
                if (isHazardous) {
                    append("⚠️ ಪ್ರಮುಖ ಸುರಕ್ಷತಾ ಎಚ್ಚರಿಕೆ:\n$warning\n\n")
                }
                append("ಯಾವ ಬುಟ್ಟಿಗೆ ಹಾಕಬೇಕು?\n")
                append("• $targetBin ನಲ್ಲಿ ಹಾಕಿ (${rule.disposalMethod}).\n\n")

                if (!rule.environmentalImpact.isNullOrBlank()) {
                    append("ಇದು ಏಕೆ ಮುಖ್ಯ?\n")
                    append("• ${rule.environmentalImpact}\n\n")
                }

                if (rule.preparationInstructions.isNotBlank()) {
                    append("ನಿಮಗಾಗಿ ಸರಳ ಹಂತಗಳು:\n")
                    append("• ${rule.preparationInstructions}\n\n")
                }

                if (rule.prohibitedPathways.isNotEmpty()) {
                    append("ಮಾಡಬಾರದ ಕಾರ್ಯಗಳು: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                }

                append("ಕಸ ವಿಂಗಡಣೆಯ ಬಗ್ಗೆ ಯಾವುದೇ ಪ್ರಶ್ನೆಗಳಿದ್ದರೆ ಖಂಡಿತ ಕೇಳಿ! 😊")
            }.trim()

            "ml" -> buildString {
                append("നമസ്കാരം! നിങ്ങളുടെ വസ്തു: $itemName.\n\n")
                if (isHazardous) {
                    append("⚠️ പ്രധാന സുരക്ഷാ മുന്നറിയിപ്പ്:\n$warning\n\n")
                }
                append("ഏത് തൊട്ടിയിലാണ് നിക്ഷേപിക്കേണ്ടത്?\n")
                append("• $targetBin ൽ നിക്ഷേപിക്കുക (${rule.disposalMethod}).\n\n")

                if (!rule.environmentalImpact.isNullOrBlank()) {
                    append("ഇത് എന്തുകൊണ്ട് പ്രധാനമാണ്?\n")
                    append("• ${rule.environmentalImpact}\n\n")
                }

                if (rule.preparationInstructions.isNotBlank()) {
                    append("ലളിതമായ ഘട്ടങ്ങൾ:\n")
                    append("• ${rule.preparationInstructions}\n\n")
                }

                if (rule.prohibitedPathways.isNotEmpty()) {
                    append("ചെയ്യാൻ പാടില്ലാത്തവ: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                }

                append("മാലിന്യ സംസ്കരണത്തെക്കുറിച്ച് കൂടുതൽ സംശയങ്ങളുണ്ടെങ്കിൽ ചോദിക്കൂ! 😊")
            }.trim()

            "bn" -> buildString {
                append("নমস্কার! আপনার বস্তু: $itemName।\n\n")
                if (isHazardous) {
                    append("⚠️ গুরুত্বপূর্ণ নিরাপত্তা সতর্কতা:\n$warning\n\n")
                }
                append("কোন পাত্রে ফেলতে হবে?\n")
                append("• $targetBin এ ফেলুন (${rule.disposalMethod})।\n\n")

                if (!rule.environmentalImpact.isNullOrBlank()) {
                    append("এটি কেন গুরুত্বপূর্ণ?\n")
                    append("• ${rule.environmentalImpact}\n\n")
                }

                if (rule.preparationInstructions.isNotBlank()) {
                    append("আপনার জন্য সহজ পদক্ষেপ:\n")
                    append("• ${rule.preparationInstructions}\n\n")
                }

                if (rule.prohibitedPathways.isNotEmpty()) {
                    append("যা করবেন না: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                }

                append("বর্জ্য ব্যবস্থাপনা সম্পর্কে অন্য কোনো প্রশ্ন থাকলে সানন্দে জিজ্ঞাসা করুন! 😊")
            }.trim()

            "gu" -> buildString {
                append("નમસ્તે! તમારી વસ્તુ: $itemName.\n\n")
                if (isHazardous) {
                    append("⚠️ મહત્વપૂર્ણ સુરક્ષા ચેતવણી:\n$warning\n\n")
                }
                append("કયા ડબ્બામાં નાખવું?\n")
                append("• $targetBin માં નાખો (${rule.disposalMethod}).\n\n")

                if (!rule.environmentalImpact.isNullOrBlank()) {
                    append("આ શા માટે મહત્વપૂર્ણ છે?\n")
                    append("• ${rule.environmentalImpact}\n\n")
                }

                if (rule.preparationInstructions.isNotBlank()) {
                    append("તમારા માટે સરળ પગલાં:\n")
                    append("• ${rule.preparationInstructions}\n\n")
                }

                if (rule.prohibitedPathways.isNotEmpty()) {
                    append("શું ન કરવું: ${rule.prohibitedPathways.joinToString("; ")}\n\n")
                }

                append("કચરા વ્યવસ્થાપન વિશે અન્ય કોઈ પ્રશ્ન હોય તો જરૂર પૂછો! 😊")
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
            "यह वस्तु", "यह", "ఈ వస్తువు", "ఇది", "இந்த பொருள்", "இது", "ही वस्तू", "हे",
            "ಈ ವಸ್ತು", "ಇದು", "ഈ വസ്തു", "ഇത്", "এই বস্তু", "এটি", "এটা", "આ વસ્તુ", "આ"
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
