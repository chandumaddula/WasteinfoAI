package com.wasteinfoai.ai.llm

import com.wasteinfoai.ai.rules.DisposalRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests verification of Multilingual Bottle Assistant PromptBuilder and IntentDetector.
 */
class MultilingualAssistantTest {

    @Test
    fun testLanguageSpecificPromptInstructions() {
        assertEquals("Respond in clear English.", PromptBuilder.getLanguageInstruction("en"))
        assertEquals("Respond strictly in Hindi using Devanagari script.", PromptBuilder.getLanguageInstruction("hi"))
        assertEquals("Respond strictly in Telugu using Telugu script.", PromptBuilder.getLanguageInstruction("te"))
        assertEquals("Respond strictly in Tamil using Tamil script.", PromptBuilder.getLanguageInstruction("ta"))
        assertEquals("Respond strictly in Marathi using Devanagari script.", PromptBuilder.getLanguageInstruction("mr"))
        assertEquals("Respond in clear English.", PromptBuilder.getLanguageInstruction(null))
    }

    @Test
    fun testPromptContainsBothSafetyAndLanguageInstruction() {
        val rule = DisposalRule(
            id = "RULE-BATTERY-001",
            category = "Battery",
            disposalMethod = "Domestic Hazardous Waste",
            isRecyclable = true,
            isHazardous = true,
            preparationInstructions = "Tape terminals.",
            safetyWarnings = "Explosion hazard.",
            targetBinColor = "RED",
            source = "SWM 2016"
        )

        for (lang in listOf("en", "hi", "te", "ta", "mr")) {
            val prompt = PromptBuilder.buildMultiTurnPrompt(
                context = AssistantContext(category = "Battery", languageCode = lang),
                rule = rule,
                history = emptyList(),
                currentMessage = "How do I safely dispose of this?",
                languageCode = lang
            )

            // Must preserve strict safety rules
            assertTrue("Must contain safety rule 1 in $lang", prompt.contains("Ground your answer ONLY in the official disposal rule provided"))
            assertTrue("Must emphasize hazardous items in $lang", prompt.contains("prominently emphasize safety warnings"))
            assertTrue("Must contain grounding data in $lang", prompt.contains("Target Bin: RED BIN"))

            // Must contain the specific language constraint
            val expectedInstruction = PromptBuilder.getLanguageInstruction(lang)
            assertTrue("Prompt must contain language instruction '$expectedInstruction' for $lang", prompt.contains(expectedInstruction))
        }
    }

    @Test
    fun testMultilingualSafetyIntent() {
        val enQuery = "What is the fire danger of this battery?"
        assertEquals(AssistantIntent.SAFETY, IntentDetector.detect(enQuery))

        val hiQuery = "क्या इस बैटरी में आग लगने का खतरा है?"
        assertEquals(AssistantIntent.SAFETY, IntentDetector.detect(hiQuery))

        val teQuery = "దీన్ని కాల్చడం వల్ల ప్రమాదం జరుగుతుందా?"
        assertEquals(AssistantIntent.SAFETY, IntentDetector.detect(teQuery))

        val taQuery = "இது தீப்பிடிக்கும் ஆபத்து கொண்டதா?"
        assertEquals(AssistantIntent.SAFETY, IntentDetector.detect(taQuery))

        val mrQuery = "या बॅटरीला आग लागल्यास धोकादायक ठरेल का?"
        assertEquals(AssistantIntent.SAFETY, IntentDetector.detect(mrQuery))
    }

    @Test
    fun testMultilingualCleaningIntent() {
        val enQuery = "How should I wash this before recycling?"
        assertEquals(AssistantIntent.CLEANING, IntentDetector.detect(enQuery))

        val hiQuery = "फेंकने से पहले इसे कैसे धोना है?"
        assertEquals(AssistantIntent.CLEANING, IntentDetector.detect(hiQuery))

        val teQuery = "పారవేసే ముందు దీన్ని ఎలా శుభ్రం చేయాలి?"
        assertEquals(AssistantIntent.CLEANING, IntentDetector.detect(teQuery))

        val taQuery = "மறுசுழற்சிக்கு முன் இதை எவ்வாறு சுத்தம் செய்ய வேண்டும்?"
        assertEquals(AssistantIntent.CLEANING, IntentDetector.detect(taQuery))

        val mrQuery = "कचऱ्यात टाकण्यापूर्वी हे कसे धुवावे?"
        assertEquals(AssistantIntent.CLEANING, IntentDetector.detect(mrQuery))
    }

    @Test
    fun testMultilingualUpcycleIntent() {
        val enQuery = "Can I make a DIY planter or craft out of this?"
        assertEquals(AssistantIntent.UPCYCLE, IntentDetector.detect(enQuery))

        val hiQuery = "क्या मैं इससे कोई क्राफ्ट या गमला बना सकता हूँ?"
        assertEquals(AssistantIntent.UPCYCLE, IntentDetector.detect(hiQuery))

        val teQuery = "దీన్ని తిరిగి ఉపయోగించి కుండీలు తయారు చేయవచ్చా?"
        assertEquals(AssistantIntent.UPCYCLE, IntentDetector.detect(teQuery))

        val taQuery = "இதை மீண்டும் பயன்படுத்தி கைவினை பொருட்கள் செய்யலாமா?"
        assertEquals(AssistantIntent.UPCYCLE, IntentDetector.detect(taQuery))

        val mrQuery = "यापासून हस्तकला वस्तू किंवा कुंडी बनवू शकतो का?"
        assertEquals(AssistantIntent.UPCYCLE, IntentDetector.detect(mrQuery))
    }

    @Test
    fun testMultilingualBinIntent() {
        val enQuery = "Which bin color should I throw this into?"
        assertEquals(AssistantIntent.BIN_LOCATION, IntentDetector.detect(enQuery))

        val hiQuery = "इसे किस रंग के डिब्बे में फेंकना है?"
        assertEquals(AssistantIntent.BIN_LOCATION, IntentDetector.detect(hiQuery))

        val teQuery = "దీన్ని ఏ రంగు డబ్బాలో వేయాలి?"
        assertEquals(AssistantIntent.BIN_LOCATION, IntentDetector.detect(teQuery))

        val taQuery = "இதை எந்த நிற குப்பைத்தொட்டியில் போடுவது?"
        assertEquals(AssistantIntent.BIN_LOCATION, IntentDetector.detect(taQuery))

        val mrQuery = "हे कोणत्या रंगाच्या कचराकुंडीत टाकावे?"
        assertEquals(AssistantIntent.BIN_LOCATION, IntentDetector.detect(mrQuery))
    }

    @Test
    fun testMultilingualIntentPrioritySafetyOverAll() {
        // Query has safety keyword (आग / आग) and cleaning keyword (धोना / धुणे)
        val hiMixed = "यदि इसमें आग लग जाए तो क्या इसे धोना चाहिए?"
        assertEquals(AssistantIntent.SAFETY, IntentDetector.detect(hiMixed))

        val mrMixed = "या वस्तूला आग लागल्यास काय धोका आहे?"
        assertEquals(AssistantIntent.SAFETY, IntentDetector.detect(mrMixed))
    }

    @Test
    fun testDeterministicFallbackSafetyRefusal() {
        val responder = OfflineResponder()
        val hazardousBattery = DisposalRule(
            id = "RULE-BATTERY-001",
            category = "Battery",
            disposalMethod = "Domestic Hazardous Waste",
            isRecyclable = true,
            isHazardous = true,
            preparationInstructions = "Tape terminals.",
            safetyWarnings = "Chemical burn hazard.",
            targetBinColor = "RED",
            source = "SWM 2016"
        )

        val reply = responder.respond(
            context = AssistantContext(category = "Battery"),
            rule = hazardousBattery,
            query = "Can I craft and reuse this battery?"
        )

        assertTrue("Must trigger safety refusal for hazardous upcycling", reply.contains("SAFETY REFUSAL"))
        assertTrue("Must mention hazard warning", reply.contains("Chemical burn hazard"))
        assertTrue("Must point to red bin", reply.contains("RED BIN"))
    }

    @Test
    fun testOfflineResponderMultilingualReplies() {
        val responder = OfflineResponder()
        val mouseRule = DisposalRule(
            id = "RULE-MOUSE-001",
            category = "Mouse",
            disposalMethod = "ఇ-వ్యర్థాల రీసైక్లింగ్ (నారింజ డబ్బా)",
            isRecyclable = true,
            isHazardous = false,
            preparationInstructions = "బ్యాటరీలను తొలగించండి.",
            safetyWarnings = "కాల్చవద్దు.",
            targetBinColor = "ORANGE",
            source = "ఇ-వ్యర్థాల నిబంధనలు 2022"
        )

        val replyTe = responder.respond(
            context = AssistantContext(category = "Mouse", languageCode = "te"),
            rule = mouseRule,
            query = "దీని పారవేసే విధానం ఏమిటి?"
        )

        assertTrue("Must contain Telugu guidance heading", replyTe.contains("మౌస్ (Mouse) కోసం మార్గదర్శకత్వం") || replyTe.contains("కొరకు మార్గదర్శకత్వం"))
        assertTrue("Must contain Telugu target bin", replyTe.contains("నారింజ డబ్బా (ORANGE)"))
        assertTrue("Must contain Telugu prep instructions", replyTe.contains("బ్యాటరీలను తొలగించండి"))
    }

    @Test
    fun testSpeechTextFormatterMarkdownAndTransitions() {
        val markdownText = """
            **Guidance for PET Bottle:**
            • Rinse clean with water.
            • Flatten the bottle.
            • Replace the cap!
            Visit https://example.com/recycle for more info 😊
        """.trimIndent()

        val spokenEn = com.wasteinfoai.audio.SpeechTextFormatter.formatForSpeech(markdownText, "en")
        org.junit.Assert.assertFalse("Spoken text must not contain markdown asterisks", spokenEn.contains("**"))
        org.junit.Assert.assertFalse("Spoken text must not contain URLs", spokenEn.contains("https://"))
        org.junit.Assert.assertFalse("Spoken text must not contain emojis", spokenEn.contains("😊"))
        org.junit.Assert.assertTrue("Spoken text must contain natural transition", spokenEn.contains("First, Rinse clean with water"))
        org.junit.Assert.assertTrue("Spoken text must contain final transition", spokenEn.contains("Finally, Replace the cap!"))

        val teListText = """
            సూచనలు:
            • నీటితో శుభ్రం చేయండి.
            • డబ్బాలో వేయండి.
        """.trimIndent()
        val spokenTe = com.wasteinfoai.audio.SpeechTextFormatter.formatForSpeech(teListText, "te")
        org.junit.Assert.assertTrue("Must contain Telugu transition", spokenTe.contains("మొదటగా,") && spokenTe.contains("చివరగా,"))
    }

    @Test
    fun testOfflineResponderBatteryFollowUp() {
        val responder = OfflineResponder()
        val mouseRule = DisposalRule(
            id = "RULE-MOUSE-001",
            category = "Mouse",
            disposalMethod = "E-waste collection",
            isRecyclable = true,
            isHazardous = false,
            preparationInstructions = "Remove batteries.",
            safetyWarnings = "Do not burn.",
            targetBinColor = "ORANGE",
            source = "E-waste Rules"
        )

        val replyEn = responder.respond(
            context = AssistantContext(category = "Mouse"),
            rule = mouseRule,
            query = "What about its battery?"
        )
        assertTrue("Must address battery in response", replyEn.contains("battery", ignoreCase = true))
        assertTrue("Must warn against crushing or burning battery", replyEn.contains("puncture, crush, or burn", ignoreCase = true))
    }

    @Test
    fun testOfflineResponderAntiBurningAdvice() {
        val responder = OfflineResponder()
        val plasticRule = DisposalRule(
            id = "RULE-PLASTIC-001",
            category = "Plastic",
            disposalMethod = "Dry Recyclable Waste",
            isRecyclable = true,
            isHazardous = false,
            preparationInstructions = "Rinse clean.",
            safetyWarnings = "Do not burn.",
            targetBinColor = "BLUE",
            source = "PWM 2016"
        )

        val replyEn = responder.respond(
            context = AssistantContext(category = "Plastic"),
            rule = plasticRule,
            query = "Can I burn it in my backyard?"
        )
        assertTrue("Must refuse burning", replyEn.contains("do not burn", ignoreCase = true) || replyEn.contains("don't burn", ignoreCase = true))
        assertTrue("Must mention toxic fumes or pollutants", replyEn.contains("toxic fumes", ignoreCase = true))
    }

    @Test
    fun testOfflineResponderDecompositionFollowUp() {
        val responder = OfflineResponder()
        val appleRule = DisposalRule(
            id = "RULE-ORGANIC-001",
            category = "Apple",
            disposalMethod = "Wet Organic Compost",
            isRecyclable = true,
            isHazardous = false,
            preparationInstructions = "Compost with greens.",
            safetyWarnings = "Keep covered.",
            targetBinColor = "GREEN",
            source = "SWM 2016",
            environmentalImpact = "Converts to nutrient-rich compost."
        )

        val replyEn = responder.respond(
            context = AssistantContext(category = "Apple"),
            rule = appleRule,
            query = "How long does it take to decompose?"
        )
        assertTrue("Must mention decomposition", replyEn.contains("decompose", ignoreCase = true))
        assertTrue("Must mention environmental impact", replyEn.contains("Converts to nutrient-rich compost"))
    }

    @Test
    fun testOfflineResponderConversationalQueries() {
        val responder = OfflineResponder()
        val dummyRule = DisposalRule(
            id = "RULE-PLASTIC-001",
            category = "Plastic",
            disposalMethod = "Dry Recyclable Waste",
            isRecyclable = true,
            isHazardous = false,
            preparationInstructions = "Rinse clean.",
            safetyWarnings = "Do not burn.",
            targetBinColor = "BLUE",
            source = "PWM 2016"
        )

        // Greeting
        val greetingEn = responder.respond(AssistantContext(category = "Plastic"), dummyRule, "Hello Bottle!")
        assertTrue("Greeting must welcome user friendly", greetingEn.contains("Hello! I'm Bottle AI", ignoreCase = true))

        val greetingTe = responder.respond(AssistantContext(category = "Plastic", languageCode = "te"), dummyRule, "హలో!")
        assertTrue("Telugu greeting must contain welcome", greetingTe.contains("బాటిల్ AI") && greetingTe.contains("నమస్కారం"))

        // Identity
        val identityEn = responder.respond(AssistantContext(category = "Plastic"), dummyRule, "Who are you?")
        assertTrue("Identity must explain Bottle AI", identityEn.contains("Bottle AI") && identityEn.contains("assistant"))

        // Gratitude
        val thanksEn = responder.respond(AssistantContext(category = "Plastic"), dummyRule, "Thank you very much!")
        assertTrue("Gratitude must be acknowledged", thanksEn.contains("welcome", ignoreCase = true))
    }

    @Test
    fun testSpeechTextFormatterAttributionRemoval() {
        val message = "Great job! This goes in the blue bin.\n\n(Bottle AI — based on PWM 2016)"
        val speech = com.wasteinfoai.audio.SpeechTextFormatter.formatForSpeech(message, "en")
        org.junit.Assert.assertFalse("Speech must strip attribution so chatbot name is not spoken", speech.contains("based on PWM 2016"))
        org.junit.Assert.assertFalse("Speech must not recite parenthesized attribution", speech.contains("(Bottle AI"))
        org.junit.Assert.assertTrue("Speech must contain actual guidance", speech.contains("Great job! This goes in the blue bin"))
    }
}

