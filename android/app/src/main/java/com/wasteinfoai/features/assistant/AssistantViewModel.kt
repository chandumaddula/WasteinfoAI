package com.wasteinfoai.features.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wasteinfoai.ai.llm.AssistantResponse
import com.wasteinfoai.ai.llm.AssistantService
import com.wasteinfoai.audio.TextToSpeechManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isHazardous: Boolean = false,
    val isLlm: Boolean = false
)

data class AssistantUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val currentCategory: String = "plastic"
)

class AssistantViewModel(
    private val assistantService: AssistantService,
    private val ttsManager: TextToSpeechManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    fun setInitialCategory(category: String) {
        val cat = category.ifBlank { "plastic" }
        _uiState.value = _uiState.value.copy(currentCategory = cat)

        if (_uiState.value.messages.isEmpty()) {
            // Provide initial grounded guidance greeting
            askQuestion("How should I dispose of $cat?")
        }
    }

    fun askQuestion(query: String) {
        if (query.isBlank()) return

        val userMsg = ChatMessage(text = query, isUser = true)
        val updatedList = _uiState.value.messages + userMsg
        _uiState.value = _uiState.value.copy(messages = updatedList, isGenerating = true)

        viewModelScope.launch {
            val response = assistantService.getGuidance(
                category = _uiState.value.currentCategory,
                userQuery = query
            )

            val botMsg = ChatMessage(
                text = response.text,
                isUser = false,
                isHazardous = response.isHazardous,
                isLlm = response.isLlmGenerated
            )

            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + botMsg,
                isGenerating = false
            )
        }
    }

    fun speak(text: String) {
        ttsManager.speak(text)
    }

    fun stopSpeaking() {
        ttsManager.stop()
    }

    override fun onCleared() {
        super.onCleared()
        stopSpeaking()
    }
}
