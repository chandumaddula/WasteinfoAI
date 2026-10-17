package com.wasteinfoai.features.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wasteinfoai.ai.rules.DisposalRule
import com.wasteinfoai.ai.rules.DisposalRuleEngine
import com.wasteinfoai.ai.rules.SafetyAssessment
import com.wasteinfoai.ai.rules.SafetyGuard
import com.wasteinfoai.ai.vision.ClassificationResult
import com.wasteinfoai.ai.vision.WasteClassifier
import com.wasteinfoai.audio.TextToSpeechManager
import com.wasteinfoai.data.local.entity.ScanHistoryEntity
import com.wasteinfoai.data.repository.ScanRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface ScanResultUiState {
    data object Loading : ScanResultUiState
    data class Success(
        val imageFile: File,
        val bitmap: Bitmap?,
        val classification: ClassificationResult,
        val rule: DisposalRule?,
        val safety: SafetyAssessment,
        val isSaved: Boolean
    ) : ScanResultUiState
    data class Error(val message: String, val imageFile: File?) : ScanResultUiState
}

class ScanResultViewModel(
    private val wasteClassifier: WasteClassifier,
    private val disposalRuleEngine: DisposalRuleEngine,
    private val scanRepository: ScanRepository,
    private val ttsManager: TextToSpeechManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScanResultUiState>(ScanResultUiState.Loading)
    val uiState: StateFlow<ScanResultUiState> = _uiState.asStateFlow()

    fun processImage(imagePath: String) {
        viewModelScope.launch {
            _uiState.value = ScanResultUiState.Loading
            val file = File(imagePath)
            if (!file.exists()) {
                _uiState.value = ScanResultUiState.Error("Image file not found at $imagePath", null)
                return@launch
            }

            val result = wasteClassifier.classify(file)
            if (result.isFailure) {
                _uiState.value = ScanResultUiState.Error(
                    "Classification failed: ${result.exceptionOrNull()?.localizedMessage}",
                    file
                )
                return@launch
            }

            val classification = result.getOrThrow()
            val rule = disposalRuleEngine.getRuleForCategory(classification.label)
            val safety = SafetyGuard.evaluateSafety(classification.label, classification.isUncertain)

            // Decode scaled preview bitmap for display
            val bitmap = runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            }.getOrNull()

            // Save to Room scan history
            runCatching {
                scanRepository.saveScan(
                    ScanHistoryEntity(
                        imagePath = file.absolutePath,
                        category = classification.label,
                        confidence = classification.confidence,
                        timestamp = System.currentTimeMillis(),
                        ruleId = rule?.id,
                        isHazardous = safety.isHazardous,
                        disposalMethod = rule?.disposalMethod,
                        targetBinColor = rule?.targetBinColor,
                        isSynced = false
                    )
                )
            }

            _uiState.value = ScanResultUiState.Success(
                imageFile = file,
                bitmap = bitmap,
                classification = classification,
                rule = rule,
                safety = safety,
                isSaved = true
            )
        }
    }

    fun speakGuidance() {
        val state = _uiState.value as? ScanResultUiState.Success ?: return
        val textToSpeak = buildString {
            append("Identified as ${state.classification.label}. ")
            if (state.safety.isHazardous) {
                append("Warning: Hazardous waste. ${state.safety.primaryWarning}. ")
            }
            if (state.rule != null) {
                append("Disposal method: ${state.rule.disposalMethod}. ")
                append("Place in ${state.rule.targetBinColor} bin. ")
                append(state.rule.preparationInstructions)
            }
        }
        ttsManager.speak(textToSpeak)
    }

    fun stopSpeaking() {
        ttsManager.stop()
    }

    override fun onCleared() {
        super.onCleared()
        stopSpeaking()
    }
}
