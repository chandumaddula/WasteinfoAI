package com.wasteinfoai.features.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wasteinfoai.ai.llm.AssistantContext
import com.wasteinfoai.ai.llm.AssistantService
import com.wasteinfoai.ai.llm.DialogueBuffer
import com.wasteinfoai.ai.llm.LlmModelManager
import com.wasteinfoai.ai.llm.ModelDownloadState
import com.wasteinfoai.ai.rules.SafetyGuard
import com.wasteinfoai.audio.SpeechInputHelper
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
    private val speechInputHelper: SpeechInputHelper? = null
) : ViewModel() {

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
            var ctx = AssistantContext(category = cat, scanId = scanId)
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
                        scanId = scanId
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
                val itemName = ctx.specificItem ?: ctx.category
                val greeting = "Hi! I'm Bottle, your offline waste & recycling assistant. How can I help you safely manage or upcycle $itemName?"
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
        val itemName = ctx.specificItem ?: ctx.category
        val greeting = "Hi! I'm Bottle. How can I help you dispose, clean, or upcycle $itemName?"
        val botGreeting = ChatMessage(text = greeting, isUser = false)
        dialogueBuffer.addMessage(botGreeting)
        _uiState.update {
            it.copy(
                messages = listOf(botGreeting),
                suggestedChips = generateSuggestedChips(ctx)
            )
        }
    }

    fun startListening() {
        val helper = speechInputHelper ?: return
        _uiState.update { it.copy(isListening = true, speechError = null) }
        helper.startListening(
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
        val cleanText = text.replace("⚠️", "Warning:").replace("•", "")
        ttsManager.speak("Bottle: $cleanText")
    }

    fun stopSpeaking() {
        ttsManager.stop()
    }

    private fun generateSuggestedChips(context: AssistantContext): List<String> {
        val safety = SafetyGuard.evaluateSafety(context.category, isUncertain = false)
        val itemName = context.specificItem ?: context.category

        return if (safety.isHazardous) {
            listOf(
                "⚠️ Is $itemName toxic to touch?",
                "🔥 What if it leaks or catches fire?",
                "📍 Where is the nearest hazardous depot?",
                "How to transport $itemName safely?"
            )
        } else {
            listOf(
                "Can I reuse or upcycle $itemName?",
                "How should I clean it before disposal?",
                "Which bin color does $itemName belong to?",
                "What if it is broken or crushed?",
                "What is its environmental impact?"
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSpeaking()
        stopListening()
        stopGeneration()
    }
}
