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
    val isLlm: Boolean = false,
    val modelBadge: String? = null
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
    val modelDownloadState: ModelDownloadState = ModelDownloadState.Idle,
    val isGeminiConfigured: Boolean = false
)

class AssistantViewModel(
    private val assistantService: AssistantService,
    private val ttsManager: TextToSpeechManager,
    private val scanRepository: ScanRepository? = null,
    private val llmModelManager: LlmModelManager? = null,
    private val speechInputHelper: SpeechInputHelper? = null,
    private val languageManager: com.wasteinfoai.core.language.LanguageManager? = null,
    private val cloudClient: com.wasteinfoai.ai.llm.CloudAssistantClient? = null
) : ViewModel() {

    private val activeLanguageCode: String
        get() = languageManager?.currentLanguage?.value ?: "en"

    private val _uiState = MutableStateFlow(AssistantUiState(isGeminiConfigured = cloudClient?.isGeminiConfigured() == true))
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val dialogueBuffer = DialogueBuffer(maxMessages = 6)
    private var currentGenerationJob: Job? = null

    init {
        _uiState.update { it.copy(isGeminiConfigured = cloudClient?.isGeminiConfigured() == true) }
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
                    "kn" -> "ನಮಸ್ಕಾರ! ನಾನು ಬಾಟಲ್ AI, ನಿಮ್ಮ ಆಫ್‌ಲೈನ್ ತ್ಯಾಜ್ಯ ಮತ್ತು ಮರುಬಳಕೆ ಸಹಾಯಕ. $itemName ಅನ್ನು ಸುರಕ್ಷಿತವಾಗಿ ನಿರ್ವಹಿಸಲು ಅಥವಾ ಮರುಬಳಕೆ ಮಾಡಲು ನಾನು ನಿಮಗೆ ಹೇಗೆ ಸಹಾಯ ಮಾಡಲಿ?"
                    "ml" -> "നമസ്കാരം! ഞാൻ ബോട്ടിൽ AI, നിങ്ങളുടെ ഓഫ്‌ലൈൻ മാലിന്യ സംസ്കരണ & പുനരുപയോഗ സഹായി. $itemName സുരക്ഷിതമായി കൈകാര്യം ചെയ്യാനോ പുനരുപയോഗിക്കാനോ ഞാൻ എങ്ങനെ സഹായിക്കണം?"
                    "bn" -> "নমস্কার! আমি বটল এআই, আপনার অফলাইন বর্জ্য ও পুনর্ব্যবহার সহকারী। $itemName নিরাপদে পরিচালনা বা পুনর্ব্যবহার করতে আমি কীভাবে সাহায্য করতে পারি?"
                    "gu" -> "નમસ્તે! હું બૉટલ AI છું, તમારો ઑફલાઇન કચરો અને રિસાયક્લિંગ સહાયક. $itemName નો સુરક્ષિત નિકાલ કે પુનઃઉપયોગ કરવામાં હું તમારી શી મદદ કરી શકું?"
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
                isLlm = response.isLlmGenerated,
                modelBadge = response.modelBadge
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
                    suggestedChips = newChips,
                    isGeminiConfigured = cloudClient?.isGeminiConfigured() == true
                )
            }
        }
    }

    fun getGeminiApiKey(): String = cloudClient?.getApiKey() ?: ""

    fun saveGeminiApiKey(key: String) {
        cloudClient?.saveApiKey(key)
        _uiState.update { it.copy(isGeminiConfigured = cloudClient?.isGeminiConfigured() == true) }
    }

    fun clearGeminiApiKey() {
        cloudClient?.clearApiKey()
        _uiState.update { it.copy(isGeminiConfigured = false) }
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
            "kn" -> "ನಮಸ್ಕಾರ! ನಾನು ಬಾಟಲ್ AI. $itemName ನ ವಿಲೇವಾರಿ, ಸ್ವಚ್ಛತೆ ಅಥವಾ ಮರುಬಳಕೆಯಲ್ಲಿ ನಾನು ಹೇಗೆ ಸಹಾಯ ಮಾಡಲಿ?"
            "ml" -> "നമസ്കാരം! ഞാൻ ബോട്ടിൽ AI. $itemName സംസ്കരണം, വൃത്തിയാക്കൽ അല്ലെങ്കിൽ പുനരുപയോഗത്തിൽ ഞാൻ എങ്ങനെ സഹായിക്കണം?"
            "bn" -> "নমস্কার! আমি বটল এআই। $itemName এর নিষ্পত্তি, পরিষ্কার বা পুনর্ব্যবহারে কীভাবে সাহায্য করতে পারি?"
            "gu" -> "નમસ્તે! હું બૉટલ AI છું. $itemName ના નિકાલ, સફાઈ કે પુનઃઉપયોગમાં હું શી મદદ કરું?"
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
                    "kn" -> listOf(
                        "⚠️ ಇದನ್ನು ಮುಟ್ಟುವುದು ಅಪಾಯಕಾರಿಯೇ?",
                        "🔥 ಬೆಂಕಿ ಹೊತ್ತಿಕೊಂಡರೆ ಅಥವಾ ಸೋರಿದರೆ ಏನು ಮಾಡಬೇಕು?",
                        "📍 ಹತ್ತಿರದ ಅಪಾಯಕಾರಿ ತ್ಯಾಜ್ಯ ಡಿಪೋ ಎಲ್ಲಿದೆ?",
                        "ಇದನ್ನು ಸುರಕ್ಷಿತವಾಗಿ ಸಾಗಿಸುವುದು ಹೇಗೆ?"
                    )
                    "ml" -> listOf(
                        "⚠️ ഇത് തൊടുന്നത് വിഷകരമാണോ?",
                        "🔥 തീപിടിക്കുകയോ ചോരുകയോ ചെയ്താൽ എന്ത് ചെയ്യണം?",
                        "📍 അടുത്തുള്ള അപകടകരമായ മാലിന്യ ഡിപ്പോ എവിടെയാണ്?",
                        "ഇത് എങ്ങനെ സുരക്ഷിതമായി കൊണ്ടുപോകാം?"
                    )
                    "bn" -> listOf(
                        "⚠️ এটি স্পর্শ করা কি বিপজ্জনক?",
                        "🔥 এটি লিক হলে বা আগুন লাগলে কি করবেন?",
                        "📍 নিকটতম বিপজ্জনক বর্জ্য ডিপো কোথায়?",
                        "এটি নিরাপদে কীভাবে পরিবহন করবেন?"
                    )
                    "gu" -> listOf(
                        "⚠️ શું આને સ્પર્શ કરવો જોખમી છે?",
                        "🔥 જો તે લીક થાય કે આગ લાગે તો શું?",
                        "📍 નજીકનો જોખમી કચરો ડેપો ક્યાં છે?",
                        "આને સુરક્ષિત રીતે કેવી રીતે લઈ જવું?"
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
                    "kn" -> listOf(
                        "ಇದರ ಬ್ಯಾಟರಿಯನ್ನು ಏನು ಮಾಡಬೇಕು?",
                        "ಇದನ್ನು ದುರಸ್ತಿ ಮಾಡಬಹುದೇ ಅಥವಾ ಮರುಬಳಕೆ ಮಾಡಬಹುದೇ?",
                        "ಹತ್ತಿರದ ಇ-ತ್ಯಾಜ್ಯ ಸಂಗ್ರಹ ಕೇಂದ್ರ ಎಲ್ಲಿದೆ?",
                        "ಇದು ಒಡೆದಿದ್ದರೆ ಅಥವಾ ಹಾಳಾಗಿದ್ದರೆ ಏನು ಮಾಡಬೇಕು?"
                    )
                    "ml" -> listOf(
                        "ഇതിന്റെ ബാറ്ററി എന്തു ചെയ്യണം?",
                        "ഇത് അറ്റകുറ്റപ്പണി നടത്തി ഉപയോഗിക്കാമോ?",
                        "ഏറ്റവും അടുത്തുള്ള ഇ-മാലിന്യ കേന്ദ്രം എവിടെയാണ്?",
                        "ഇത് കേടുവന്നതാണെങ്കിൽ എന്തുചെയ്യണം?"
                    )
                    "bn" -> listOf(
                        "এর ব্যাটারির কি করতে হবে?",
                        "এটি কি মেরামত বা পুনরায় ব্যবহার করা যাবে?",
                        "নিকটতম ই-বর্জ্য সংগ্রহ কেন্দ্র কোথায়?",
                        "এটি ভাঙা বা ক্ষতিগ্রস্ত হলে কি করবেন?"
                    )
                    "gu" -> listOf(
                        "આની બેટરીનું શું કરવું?",
                        "શું આને રિપેર કે પુનઃઉપયોગ કરી શકાય?",
                        "નજીકનું ઇ-કચરો સંગ્રહ કેન્દ્ર ક્યાં છે?",
                        "જો આ તૂટેલું કે ક્ષતિગ્રસ્ત હોય તો?"
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
                    "kn" -> listOf(
                        "ಇದನ್ನು ಮನೆಯಲ್ಲೇ ಕಾಂಪೋಸ್ಟ್ ಮಾಡಬಹುದೇ?",
                        "ಇದು ಕೊಳೆಯಲು ಎಷ್ಟು ಸಮಯ ತೆಗೆದುಕೊಳ್ಳುತ್ತದೆ?",
                        "ಇದು ಯಾವ ಬಣ್ಣದ ಬುಟ್ಟಿಗೆ ಸೇರುತ್ತದೆ?",
                        "ಇದರಿಂದ ಕೀಟಗಳು ಅಥವಾ ವಾಸನೆ ಬರುತ್ತದೆಯೇ?"
                    )
                    "ml" -> listOf(
                        "ഇത് വീട്ടിൽ തന്നെ കമ്പോസ്റ്റ് ചെയ്യാമോ?",
                        "ഇത് മണ്ണിൽ ചേരാൻ എത്ര സമയം വേണം?",
                        "ഇത് ഏത് നിറത്തിലുള്ള തൊട്ടിയിലാണ് ഇടേണ്ടത്?",
                        "ഇത് കീടങ്ങളെയോ ദുർഗന്ധത്തെയോ ആകർഷിക്കുമോ?"
                    )
                    "bn" -> listOf(
                        "আমি কি এটি বাড়িতে কম্পোস্ট করতে পারি?",
                        "এটি পচতে কত সময় লাগে?",
                        "এটি কোন রঙের পাত্রে যাবে?",
                        "এটি থেকে কি গন্ধ বা পোকা হতে পারে?"
                    )
                    "gu" -> listOf(
                        "શું હું આને ઘરે ખાતર બનાવી શકું?",
                        "આને ઓગળવામાં કેટલો સમય લાગે છે?",
                        "આ કયા રંગના ડબ્બામાં જાય છે?",
                        "શું આનાથી જીવજંતુ કે દુર્ગંધ આવી શકે?"
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
                    "kn" -> listOf(
                        "ನಾನು ಇದನ್ನು ಮರುಬಳಕೆ ಮಾಡಬಹುದೇ?",
                        "ವಿಲೇವಾರಿ ಮಾಡುವ ಮುನ್ನ ಇದನ್ನು ಹೇಗೆ ಸ್ವಚ್ಛಗೊಳಿಸಬೇಕು?",
                        "ಇದು ಯಾವ ಬಣ್ಣದ ಬುಟ್ಟಿಗೆ ಸೇರುತ್ತದೆ?",
                        "ಇದು ಒಡೆದಿದ್ದರೆ ಏನು ಮಾಡಬೇಕು?",
                        "ಪರಿಸರದ ಮೇಲೆ ಇದರ ಪರಿಣಾಮವೇನು?"
                    )
                    "ml" -> listOf(
                        "എനിക്ക് ഇത് പുനരുപയോഗിക്കാൻ കഴിയുമോ?",
                        "സംസ്കരിക്കുന്നതിന് മുമ്പ് ഇത് എങ്ങനെ വൃത്തിയാക്കണം?",
                        "ഇത് ഏത് നിറത്തിലുള്ള തൊട്ടിയിലാണ് ഇടേണ്ടത്?",
                        "ഇത് പൊട്ടിയതാണെങ്കിൽ എന്തു ചെയ്യണം?",
                        "പരിസ്ഥിതിയിൽ ഇതിന്റെ പ്രത്യാഘാതം എന്താണ്?"
                    )
                    "bn" -> listOf(
                        "আমি কি এটি পুনর্ব্যবহার করতে পারি?",
                        "নিষ্পত্তির আগে এটি কীভাবে পরিষ্কার করব?",
                        "এটি কোন রঙের পাত্রে যাবে?",
                        "যদি এটি ভাঙা থাকে তবে কি করণীয়?",
                        "পরিবেশে এর প্রভাব কী?"
                    )
                    "gu" -> listOf(
                        "શું હું આનો પુનઃઉપયોગ કરી શકું?",
                        "નિકાલ પહેલાં આને કેવી રીતે સાફ કરવું?",
                        "આ કયા રંગના ડબ્બામાં જાય છે?",
                        "જો આ તૂટેલું હોય તો શું?",
                        "પર્યાવરણ પર આની શી અસર થાય છે?"
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
