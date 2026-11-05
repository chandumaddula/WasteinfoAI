package com.wasteinfoai.features.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wasteinfoai.ai.llm.AssistantContext
import com.wasteinfoai.ai.llm.AssistantService
import com.wasteinfoai.ai.llm.DialogueBuffer
import com.wasteinfoai.ai.llm.LlmModelManager
import com.wasteinfoai.ai.llm.ModelDownloadState
import com.wasteinfoai.ai.rules.SafetyGuard
import com.wasteinfoai.core.language.CategoryLocalization
import com.wasteinfoai.audio.SpeechInputHelper
import com.wasteinfoai.audio.SpeechTextFormatter
import com.wasteinfoai.audio.TextToSpeechManager
import com.wasteinfoai.data.repository.ScanRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isHazardous: Boolean = false,
    val isLlm: Boolean = false
)

data class AssistantUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val currentCategory: String = "plastic",
    val currentItemName: String? = null,
    val context: AssistantContext = AssistantContext(category = "plastic"),
    val suggestedChips: List<String> = emptyList(),
    val isListening: Boolean = false,
    val speechError: String? = null,
    val isModelDownloaded: Boolean = false,
    val modelDownloadState: ModelDownloadState = ModelDownloadState.Idle
)

class AssistantViewModel(
    private val assistantService: AssistantService,
    private val ttsManager: TextToSpeechManager,
    private val scanRepository: ScanRepository? = null,
    private val llmModelManager: LlmModelManager? = null,
    private val speechInputHelper: SpeechInputHelper? = null,
    private val languageManager: com.wasteinfoai.core.language.LanguageManager? = null
) : ViewModel() {

    private val activeLanguageCode: String
        get() = languageManager?.currentLanguage?.value ?: "en"

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val dialogueBuffer = DialogueBuffer(maxMessages = 6)
    private var currentGenerationJob: Job? = null

    init {
        // Observe model download status if manager is available
        llmModelManager?.let { mgr ->
            viewModelScope.launch {
                mgr.downloadState.collect { state ->
                    _uiState.update {
                        it.copy(
                            modelDownloadState = state,
                            isModelDownloaded = mgr.isModelDownloaded()
                        )
                    }
                }
            }
        }
    }

    /**
     * Backward-compatible initialization with category.
     */
    fun setInitialCategory(category: String) {
        setInitialContext(category = category, scanId = 0L)
    }

    /**
     * Rich initialization with scanId lookup from repository.
     */
    fun setInitialContext(category: String, scanId: Long = 0L) {
        val cat = category.ifBlank { "plastic" }

        viewModelScope.launch {
            val lang = activeLanguageCode
            var ctx = AssistantContext(category = cat, scanId = scanId, languageCode = lang)
            if (scanId > 0L && scanRepository != null) {
                val scan = scanRepository.getScanById(scanId)
                if (scan != null) {
                    ctx = AssistantContext(
                        category = scan.category,
                        specificItem = scan.specificItem ?: scan.userConfirmedCategory,
                        materialCode = scan.materialCode,
                        confidence = scan.confidence,
                        isUserConfirmed = scan.isUserConfirmed,
                        jurisdiction = "INDIA_SWM_2016",
                        scanId = scanId,
                        languageCode = lang
                    )
                }
            }

            dialogueBuffer.reset(ctx.specificItem ?: ctx.category)

            val chips = generateSuggestedChips(ctx)
            _uiState.update {
                it.copy(
                    context = ctx,
                    currentCategory = ctx.category,
                    currentItemName = ctx.specificItem,
                    suggestedChips = chips
                )
            }

            if (_uiState.value.messages.isEmpty()) {
                val rawItem = ctx.specificItem ?: ctx.category
                val itemName = CategoryLocalization.getCategoryDisplayName(rawItem, lang)
                val greeting = when (lang) {
                    "hi" -> "नमस्ते! मैं बॉटल AI हूँ, आपका ऑफ़लाइन अपशिष्ट और पुनर्चक्रण सहायक। मैं $itemName के सुरक्षित प्रबंधन या पुनर्चक्रण में आपकी क्या मदद कर सकता हूँ?"
                    "te" -> "నమస్కారం! నేను బాటిల్ AI, మీ ఆఫ్‌లైన్ వ్యర్థాల సహాయకుడిని. $itemName సురక్షిత నిర్వహణ లేదా పునర్వినియోగంలో నేను మీకు ఎలా సహాయపడగలను?"
                    "ta" -> "வணக்கம்! நான் பாட்டில் AI, உங்கள் ஆஃப்லைன் கழிவு உதவியாளர். $itemName ஐ பாதுகாப்பாக நிர்வகிக்க நான் எவ்வாறு உதவ முடியும்?"
                    "mr" -> "नमस्कार! मी बॉटल AI आहे, तुमचा ऑफलाइन कचरा सहाय्यक. मी $itemName चे सुरक्षित व्यवस्थापन किंवा पुनर्वापर करण्यात आपल्याला कशी मदत करू शकतो?"
                    else -> "Hi! I'm Bottle AI, your offline waste & recycling assistant. How can I help you safely manage or upcycle $itemName?"
                }
                val botGreeting = ChatMessage(text = greeting, isUser = false)
                dialogueBuffer.addMessage(botGreeting)
                _uiState.update { it.copy(messages = listOf(botGreeting)) }
            }
        }
    }

    fun askQuestion(query: String) {
        if (query.isBlank()) return

        stopSpeaking()
        currentGenerationJob?.cancel()

        val userMsg = ChatMessage(text = query, isUser = true)
        dialogueBuffer.addMessage(userMsg)

        val botMsgId = UUID.randomUUID().toString()
        val botPlaceholder = ChatMessage(id = botMsgId, text = "", isUser = false)

        _uiState.update {
            it.copy(
                messages = it.messages + userMsg + botPlaceholder,
                isGenerating = true
            )
        }

        currentGenerationJob = viewModelScope.launch {
            val responseBuilder = StringBuilder()

            val response = assistantService.reply(
                context = _uiState.value.context,
                message = query,
                history = dialogueBuffer.getHistory()
            ) { token ->
                // Token streaming callback
                responseBuilder.append(token)
                val currentText = responseBuilder.toString()
                _uiState.update { state ->
                    val updated = state.messages.map { msg ->
                        if (msg.id == botMsgId) msg.copy(text = currentText) else msg
                    }
                    state.copy(messages = updated)
                }
            }

            // Final message update with metadata
            val finalBotMsg = ChatMessage(
                id = botMsgId,
                text = response.text,
                isUser = false,
                isHazardous = response.isHazardous,
                isLlm = response.isLlmGenerated
            )
            dialogueBuffer.addMessage(finalBotMsg)

            val newChips = generateSuggestedChips(_uiState.value.context)
            _uiState.update { state ->
                val updated = state.messages.map { msg ->
                    if (msg.id == botMsgId) finalBotMsg else msg
                }
                state.copy(
                    messages = updated,
                    isGenerating = false,
                    suggestedChips = newChips
                )
            }
        }
    }

    fun stopGeneration() {
        currentGenerationJob?.cancel()
        assistantService.stop()
        _uiState.update { it.copy(isGenerating = false) }
    }

    fun clearChat() {
        stopGeneration()
        stopSpeaking()
        dialogueBuffer.clear()
        val ctx = _uiState.value.context
        val rawItem = ctx.specificItem ?: ctx.category
        val itemName = CategoryLocalization.getCategoryDisplayName(rawItem, activeLanguageCode)
        val greeting = when (activeLanguageCode) {
            "hi" -> "नमस्ते! मैं बॉटल AI हूँ। मैं $itemName के निपटान, सफाई या पुनर्चक्रण में कैसे मदद करूँ?"
            "te" -> "నమస్కారం! నేను బాటిల్ AI. $itemName పారవేయడం, శుభ్రపరచడం లేదా పునర్వినియోగంలో నేను ఎలా సహాయపడగలను?"
            "ta" -> "வணக்கம்! நான் பாட்டில் AI. $itemName ஐ அகற்ற அல்லது மறுசுழற்சி செய்ய நான் எவ்வாறு உதவ முடியும்?"
            "mr" -> "नमस्कार! मी बॉटल AI आहे. $itemName ची विल्हेवाट, स्वच्छता किंवा पुनर्वापरात मी कशी मदत करू?"
            else -> "Hi! I'm Bottle AI. How can I help you dispose, clean, or upcycle $itemName?"
        }
        val botGreeting = ChatMessage(text = greeting, isUser = false)
        dialogueBuffer.addMessage(botGreeting)
        _uiState.update {
            it.copy(
                messages = listOf(botGreeting),
                suggestedChips = generateSuggestedChips(ctx)
            )
        }
    }

    /**
     * Switch language and update suggested chips and assistant context.
     */
    fun setLanguage(languageCode: String) {
        languageManager?.setLanguage(languageCode)
        val ctx = _uiState.value.context.copy(languageCode = languageCode)
        val chips = generateSuggestedChips(ctx)
        _uiState.update {
            it.copy(
                context = ctx,
                suggestedChips = chips
            )
        }
    }

    fun startListening() {
        val helper = speechInputHelper ?: return
        _uiState.update { it.copy(isListening = true, speechError = null) }
        helper.startListening(
            languageCode = activeLanguageCode,
            onResult = { text ->
                _uiState.update { it.copy(isListening = false) }
                askQuestion(text)
            },
            onError = { error ->
                _uiState.update { it.copy(isListening = false, speechError = error) }
            },
            onListeningStateChanged = { listening ->
                _uiState.update { it.copy(isListening = listening) }
            }
        )
    }

    fun stopListening() {
        speechInputHelper?.stopListening()
        _uiState.update { it.copy(isListening = false) }
    }

    fun downloadLlmModel(allowMobileData: Boolean = false) {
        val mgr = llmModelManager ?: return
        viewModelScope.launch {
            mgr.downloadModel(requireWifi = !allowMobileData)
        }
    }

    fun cancelModelDownload() {
        llmModelManager?.cancelDownload()
    }

    fun deleteLlmModel() {
        llmModelManager?.deleteModel()
    }

    fun speak(text: String) {
        stopSpeaking()
        val detectedLang = SpeechTextFormatter.detectLanguage(text, activeLanguageCode)
        val formattedSpeech = SpeechTextFormatter.formatForSpeech(text, detectedLang)
        if (formattedSpeech.isNotBlank()) {
            ttsManager.speak(formattedSpeech, languageCode = detectedLang)
        }
    }

    fun stopSpeaking() {
        ttsManager.stop()
    }

    private fun generateSuggestedChips(context: AssistantContext): List<String> {
        val safety = SafetyGuard.evaluateSafety(context.category, isUncertain = false)
        val rawItem = context.specificItem ?: context.category
        val lang = activeLanguageCode
        val itemName = CategoryLocalization.getCategoryDisplayName(rawItem, lang)
        val lowerItem = "$rawItem ${context.category}".lowercase()

        val isEWaste = lowerItem.contains("e-waste") || lowerItem.contains("ewaste") ||
                lowerItem.contains("electronic") || lowerItem.contains("battery") ||
                lowerItem.contains("mouse") || lowerItem.contains("phone") ||
                lowerItem.contains("cable") || lowerItem.contains("computer") ||
                lowerItem.contains("wire") || lowerItem.contains("charger") ||
                lowerItem.contains("device") || lowerItem.contains("remote")

        val isOrganic = lowerItem.contains("biodegradable") || lowerItem.contains("organic") ||
                lowerItem.contains("wet") || lowerItem.contains("food") ||
                lowerItem.contains("fruit") || lowerItem.contains("vegetable") ||
                lowerItem.contains("peel") || lowerItem.contains("compost") ||
                lowerItem.contains("leaf") || lowerItem.contains("plant")

        return when {
            safety.isHazardous -> {
                when (lang) {
                    "hi" -> listOf(
                        "⚠️ क्या इसे छूना हानिकारक है?",
                        "🔥 यदि इसमें आग लग जाए या लीक हो?",
                        "📍 निकटतम खतरनाक डिपो कहाँ है?",
                        "इसे सुरक्षित रूप से कैसे ले जाएं?"
                    )
                    "te" -> listOf(
                        "⚠️ దీన్ని తాకడం ప్రమాదకరమా?",
                        "🔥 మంటలు చెలరేగితే ఏమి చేయాలి?",
                        "📍 సమీప డిపో ఎక్కడ ఉంది?",
                        "దీన్ని సురక్షితంగా ఎలా రవాణా చేయాలి?"
                    )
                    "ta" -> listOf(
                        "⚠️ இதைத் தொடுவது நச்சுத்தன்மையுடையதா?",
                        "🔥 தீப்பிடித்தால் என்ன செய்வது?",
                        "📍 அருகிலுள்ள கிடங்கு எங்கே?",
                        "பாதுகாப்பாக கொண்டு செல்வது எப்படி?"
                    )
                    "mr" -> listOf(
                        "⚠️ याला स्पर्श करणे विषारी आहे का?",
                        "🔥 आग लागल्यास काय करावे?",
                        "📍 जवळचे डेपो कुठे आहे?",
                        "सुरक्षित वाहतूक कशी करावी?"
                    )
                    else -> listOf(
                        "⚠️ Is $itemName toxic to touch?",
                        "🔥 What if it leaks or catches fire?",
                        "📍 Where is the nearest hazardous depot?",
                        "How to transport $itemName safely?"
                    )
                }
            }
            isEWaste -> {
                when (lang) {
                    "hi" -> listOf(
                        "इसकी बैटरी के बारे में क्या करना चाहिए?",
                        "क्या इसे ठीक या पुनः उपयोग किया जा सकता है?",
                        "निकटतम ई-कचरा केंद्र कहाँ है?",
                        "यदि यह टूटा या क्षतिग्रस्त हो तो?"
                    )
                    "te" -> listOf(
                        "దీని బ్యాటరీ గురించి ఏమి చేయాలి?",
                        "దీన్ని రిపేర్ లేదా తిరిగి ఉపయోగించవచ్చా?",
                        "సమీప ఇ-వ్యర్థాల సేకరణ కేంద్రం ఎక్కడ ఉంది?",
                        "ఇది విరిగిపోయి ఉంటే ఏమి చేయాలి?"
                    )
                    "ta" -> listOf(
                        "இதன் பேட்டரியை என்ன செய்வது?",
                        "இதை பழுதுபார்த்து பயன்படுத்தலாமா?",
                        "மின்-கழிவு சேகரிப்பு மையம் எங்கே உள்ளது?",
                        "இது உடைந்திருந்தால் என்ன செய்வது?"
                    )
                    "mr" -> listOf(
                        "यातील बॅटरीचे काय करावे?",
                        "हे दुरुस्त किंवा पुनर्वापर करता येईल का?",
                        "जवळचे ई-कचरा संकलन केंद्र कुठे आहे?",
                        "हे तुटलेले किंवा नादुरुस्त असल्यास?"
                    )
                    else -> listOf(
                        "What about its battery?",
                        "Can it be repaired or refurbished?",
                        "Where is the nearest e-waste collection point?",
                        "What if it is broken or damaged?"
                    )
                }
            }
            isOrganic -> {
                when (lang) {
                    "hi" -> listOf(
                        "क्या मैं इसे घर पर खाद बना सकता हूँ?",
                        "इसे गलने में कितना समय लगता है?",
                        "यह किस रंग के कूड़ेदान में जाता है?",
                        "क्या इससे गंध या कीड़े आ सकते हैं?"
                    )
                    "te" -> listOf(
                        "దీన్ని ఇంట్లోనే కంపోస్ట్ చేయవచ్చా?",
                        "ఇది కుళ్ళిపోవడానికి ఎంత సమయం పడుతుంది?",
                        "ఇది ఏ రంగు డబ్బాలో వేయాలి?",
                        "దీని వల్ల కీటకాలు లేదా వాసన వస్తుందా?"
                    )
                    "ta" -> listOf(
                        "இதை வீட்டிலேயே உரமாக மாற்றலாமா?",
                        "இது மட்க எவ்வளவு காலம் ஆகும்?",
                        "இது எந்த நிறத் தொட்டியில் போடப்பட வேண்டும்?",
                        "இதனால் பூச்சிகள் அல்லது துர்நாற்றம் வருமா?"
                    )
                    "mr" -> listOf(
                        "मी घरीच याचे खत बनवू शकतो का?",
                        "हे कुजण्यासाठी किती वेळ लागतो?",
                        "हे कोणत्या रंगाच्या कुंडीत टाकावे?",
                        "यामुळे कीटक किंवा दुर्गंधी येऊ शकते का?"
                    )
                    else -> listOf(
                        "Can I compost this at home?",
                        "How long does it take to decompose?",
                        "Which bin color does it go into?",
                        "Can it attract insects or pests?"
                    )
                }
            }
            else -> {
                when (lang) {
                    "hi" -> listOf(
                        "क्या मैं इसका पुनर्चक्रण या पुनः उपयोग कर सकता हूँ?",
                        "निपटान से पहले इसे कैसे साफ़ करें?",
                        "यह किस रंग के कूड़ेदान में जाता है?",
                        "यदि यह टूटा या क्षतिग्रस्त हो तो?",
                        "इसका पर्यावरणीय प्रभाव क्या है?"
                    )
                    "te" -> listOf(
                        "నేను దీన్ని తిరిగి ఉపయోగించవచ్చా?",
                        "పారవేసే ముందు దీన్ని ఎలా శుభ్రం చేయాలి?",
                        "ఇది ఏ రంగు డబ్బాకు చెందుతుంది?",
                        "ఇది విరిగిపోయి ఉంటే ఏమి చేయాలి?",
                        "దీని పర్యావరణ ప్రభావం ఏమిటి?"
                    )
                    "ta" -> listOf(
                        "இதை மீண்டும் பயன்படுத்தலாமா?",
                        "இதை எப்படி சுத்தம் செய்வது?",
                        "இது எந்த நிறத் தொட்டியில் போடப்பட வேண்டும்?",
                        "இது உடைந்திருந்தால் என்ன செய்வது?",
                        "இதன் சுற்றுச்சூழல் தாக்கம் என்ன?"
                    )
                    "mr" -> listOf(
                        "मी याचा पुनर्वापर करू शकतो का?",
                        "विल्हेवाट लावण्यापूर्वी हे कसे स्वच्छ करावे?",
                        "हे कोणत्या रंगाच्या कचराकुंडीत जाते?",
                        "जर ते तुटलेले असेल तर?",
                        "याचा पर्यावरणीय प्रभाव काय आहे?"
                    )
                    else -> listOf(
                        "Can I reuse or upcycle $itemName?",
                        "How should I clean it before disposal?",
                        "Which bin color does $itemName belong to?",
                        "What if it is broken or crushed?",
                        "What is its environmental impact?"
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSpeaking()
        stopListening()
        stopGeneration()
    }
}
