package com.wasteinfoai.features.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wasteinfoai.ai.barcode.BarcodeScannerService
import com.wasteinfoai.ai.barcode.EvidenceReconciliationEngine
import com.wasteinfoai.ai.barcode.OcrPackagingEvidence
import com.wasteinfoai.ai.barcode.PackagingOcrService
import com.wasteinfoai.ai.barcode.ProductLookupService
import com.wasteinfoai.ai.barcode.ProductPackagingInfo
import com.wasteinfoai.ai.barcode.ReconciledEvidenceResult
import com.wasteinfoai.ai.rules.DisposalRule
import com.wasteinfoai.ai.rules.DisposalRuleEngine
import com.wasteinfoai.ai.rules.SafetyAssessment
import com.wasteinfoai.ai.rules.SafetyGuard
import com.wasteinfoai.ai.taxonomy.HierarchicalIdentification
import com.wasteinfoai.ai.taxonomy.IdentificationSource
import com.wasteinfoai.ai.taxonomy.SpecificWasteCandidate
import com.wasteinfoai.ai.taxonomy.WasteTaxonomy
import com.wasteinfoai.ai.vision.ClassificationResult
import com.wasteinfoai.ai.vision.ImageQualityAssessor
import com.wasteinfoai.ai.vision.ImageQualityAssessment
import com.wasteinfoai.ai.vision.PreprocessingStrategy
import com.wasteinfoai.ai.vision.WasteClassifier
import com.wasteinfoai.audio.TextToSpeechManager
import com.wasteinfoai.camera.WasteObjectDetector
import com.wasteinfoai.data.local.entity.FeedbackChoice
import com.wasteinfoai.data.local.entity.ScanFeedbackEntity
import com.wasteinfoai.data.local.entity.ScanHistoryEntity
import com.wasteinfoai.data.local.entity.SyncState
import com.wasteinfoai.data.repository.FeedbackRepository
import com.wasteinfoai.data.repository.ScanRepository
import com.wasteinfoai.location.JurisdictionResolver
import com.wasteinfoai.location.LocationHelper
import com.wasteinfoai.location.WasteJurisdiction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

sealed interface ScanResultUiState {
    data object Loading : ScanResultUiState
    data class Success(
        val imageFile: File,
        val bitmap: Bitmap?,
        val classification: ClassificationResult,
        val rule: DisposalRule?,
        val safety: SafetyAssessment,
        val isSaved: Boolean,
        val scanId: Long = 0L,
        val qualityAssessment: ImageQualityAssessment? = null,
        val hierarchicalIdentification: HierarchicalIdentification? = null,
        val detectedBarcode: String? = null,
        val productPackagingInfo: ProductPackagingInfo? = null,
        val detectedObjectsCount: Int = 0,
        val detectedObjects: List<com.wasteinfoai.camera.DetectedWasteItem> = emptyList(),
        val selectedObjectIndex: Int = 0,
        val isUserConfirmed: Boolean = false,
        val userConfirmedLabel: String? = null,
        val specificCandidates: List<SpecificWasteCandidate> = emptyList(),
        val ocrEvidence: OcrPackagingEvidence? = null,
        val hasEvidenceConflict: Boolean = false,
        val conflictExplanation: String? = null,
        val resolvedJurisdiction: WasteJurisdiction = WasteJurisdiction.INDIA_NATIONAL,
        val feedbackSubmitted: Boolean = false
    ) : ScanResultUiState
    data class Error(val message: String, val imageFile: File?) : ScanResultUiState
}

class ScanResultViewModel(
    private val wasteClassifier: WasteClassifier,
    private val disposalRuleEngine: DisposalRuleEngine,
    private val scanRepository: ScanRepository,
    private val ttsManager: TextToSpeechManager,
    private val objectDetector: WasteObjectDetector = com.wasteinfoai.camera.MlKitWasteObjectDetector(),
    private val barcodeScanner: BarcodeScannerService = BarcodeScannerService(),
    private val productLookupService: ProductLookupService = ProductLookupService(),
    private val packagingOcrService: PackagingOcrService = PackagingOcrService(),
    private val evidenceReconciliationEngine: EvidenceReconciliationEngine = EvidenceReconciliationEngine(),
    private val feedbackRepository: FeedbackRepository? = null,
    private val locationHelper: LocationHelper? = null
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

            withContext(Dispatchers.IO) {
                // 1. Decode scaled bitmap for analysis & display
                val bitmap = runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                }.getOrNull()

                // 2. Pre-inference quality assessment
                val quality = bitmap?.let { ImageQualityAssessor.assess(it) }

                // 3. Salient object detection
                val detectedItems: List<com.wasteinfoai.camera.DetectedWasteItem> =
                    bitmap?.let { objectDetector.detectObjects(it) } ?: emptyList()
                val primaryObject = detectedItems.maxByOrNull { it.saliencyScore }

                val (strategy, cropRect) = if (primaryObject != null) {
                    val rect = RectF(
                        primaryObject.boundingBox.left,
                        primaryObject.boundingBox.top,
                        primaryObject.boundingBox.right,
                        primaryObject.boundingBox.bottom
                    )
                    Pair(PreprocessingStrategy.ROI_CROP, rect)
                } else {
                    Pair(PreprocessingStrategy.LETTERBOX, null)
                }

                // 4. On-device ReXNet-150 classification on ROI or Letterboxed image
                val classResult = wasteClassifier.classify(file, strategy, cropRect)
                if (classResult.isFailure) {
                    _uiState.value = ScanResultUiState.Error(
                        "Classification failed: ${classResult.exceptionOrNull()?.localizedMessage}",
                        file
                    )
                    return@withContext
                }

                val classification = classResult.getOrThrow()

                // 5. Optional on-device Barcode scanning & Packaging DB lookup
                val barcode = bitmap?.let { barcodeScanner.scanBarcode(it) }
                val productInfo = barcode?.let { productLookupService.lookup(it) }

                // 6. On-device Packaging OCR for resin codes (RIC 1-7) & keywords
                val ocrEvidence = bitmap?.let { packagingOcrService.scanPackagingText(it) }

                // 7. Multi-evidence reconciliation
                val reconciliation = evidenceReconciliationEngine.reconcile(
                    visualResult = classification,
                    barcodeProduct = productInfo,
                    ocrEvidence = ocrEvidence,
                    isCroppedRoi = primaryObject != null
                )

                val effectiveCategory = reconciliation.coarseCategory
                val specificItem = reconciliation.specificItem
                val materialCode = reconciliation.materialCode
                val identification = reconciliation.toHierarchicalIdentification()

                // 8. Jurisdiction resolution based on location
                val coords = locationHelper?.getLastKnownLocation()
                val targetJurisdiction = JurisdictionResolver.resolveJurisdiction(
                    latitude = coords?.latitude,
                    longitude = coords?.longitude
                )

                // 9. Hierarchical Disposal Rule lookup for resolved jurisdiction
                val rule = disposalRuleEngine.getHierarchicalRule(
                    category = effectiveCategory,
                    specificItem = specificItem,
                    materialCode = materialCode,
                    jurisdiction = targetJurisdiction.code
                )

                // 10. Conservative safety assessment
                val safety = SafetyGuard.evaluateSafety(effectiveCategory, classification.isUncertain)

                // 11. Load specific candidate items for user refinement
                val candidates = WasteTaxonomy.getCandidates(effectiveCategory)

                // 12. Persist initial scan to Room
                val scanId = runCatching {
                    scanRepository.saveScan(
                        ScanHistoryEntity(
                            imagePath = file.absolutePath,
                            category = effectiveCategory,
                            confidence = classification.confidence,
                            timestamp = System.currentTimeMillis(),
                            ruleId = rule.id,
                            isHazardous = safety.isHazardous,
                            disposalMethod = rule.disposalMethod,
                            targetBinColor = rule.targetBinColor,
                            isSynced = false,
                            specificItem = specificItem,
                            materialCode = materialCode,
                            isUserConfirmed = false,
                            detectedBarcode = barcode
                        )
                    )
                }.getOrDefault(0L)

                _uiState.value = ScanResultUiState.Success(
                    imageFile = file,
                    bitmap = bitmap,
                    classification = classification,
                    rule = rule,
                    safety = safety,
                    isSaved = true,
                    scanId = scanId,
                    qualityAssessment = quality,
                    hierarchicalIdentification = identification,
                    detectedBarcode = barcode,
                    productPackagingInfo = productInfo,
                    detectedObjectsCount = detectedItems.size,
                    detectedObjects = detectedItems,
                    selectedObjectIndex = 0,
                    isUserConfirmed = false,
                    specificCandidates = candidates,
                    ocrEvidence = ocrEvidence,
                    hasEvidenceConflict = reconciliation.hasConflict,
                    conflictExplanation = reconciliation.conflictReason,
                    resolvedJurisdiction = targetJurisdiction,
                    feedbackSubmitted = false
                )
            }
        }
    }

    /**
     * Multi-object selection: allows user to select which detected bounding box ROI to inspect.
     * Re-crops and classifies the chosen item.
     */
    fun selectDetectedObject(index: Int) {
        val currentState = _uiState.value as? ScanResultUiState.Success ?: return
        if (index !in currentState.detectedObjects.indices) return
        val targetObj = currentState.detectedObjects[index]

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val cropRect = RectF(
                    targetObj.boundingBox.left,
                    targetObj.boundingBox.top,
                    targetObj.boundingBox.right,
                    targetObj.boundingBox.bottom
                )
                val classResult = wasteClassifier.classify(
                    currentState.imageFile,
                    PreprocessingStrategy.ROI_CROP,
                    cropRect
                )
                if (classResult.isSuccess) {
                    val classification = classResult.getOrThrow()
                    val reconciliation = evidenceReconciliationEngine.reconcile(
                        visualResult = classification,
                        barcodeProduct = currentState.productPackagingInfo,
                        ocrEvidence = currentState.ocrEvidence,
                        isCroppedRoi = true
                    )

                    val effectiveCategory = reconciliation.coarseCategory
                    val specificItem = reconciliation.specificItem
                    val materialCode = reconciliation.materialCode
                    val hier = reconciliation.toHierarchicalIdentification()

                    val rule = disposalRuleEngine.getHierarchicalRule(
                        category = effectiveCategory,
                        specificItem = specificItem,
                        materialCode = materialCode,
                        jurisdiction = currentState.resolvedJurisdiction.code
                    )
                    val safety = SafetyGuard.evaluateSafety(effectiveCategory, classification.isUncertain)
                    val candidates = WasteTaxonomy.getCandidates(effectiveCategory)

                    _uiState.value = currentState.copy(
                        classification = classification,
                        rule = rule,
                        safety = safety,
                        hierarchicalIdentification = hier,
                        selectedObjectIndex = index,
                        isUserConfirmed = false,
                        userConfirmedLabel = null,
                        specificCandidates = candidates,
                        hasEvidenceConflict = reconciliation.hasConflict,
                        conflictExplanation = reconciliation.conflictReason
                    )
                }
            }
        }
    }

    /**
     * User correction action: allows user to select an alternative candidate or category.
     * Updates the UI, recalculates hierarchical rules, and updates local Room persistence.
     */
    fun confirmCategory(newCategory: String, specificItemCandidate: String? = null) {
        val currentState = _uiState.value as? ScanResultUiState.Success ?: return

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val candidateObj = specificItemCandidate?.let { WasteTaxonomy.findCandidateById(it) }
                val resolvedItem = candidateObj?.displayName ?: specificItemCandidate
                val materialCode = candidateObj?.materialCode

                val updatedRule = disposalRuleEngine.getHierarchicalRule(
                    category = newCategory,
                    specificItem = resolvedItem,
                    materialCode = materialCode,
                    jurisdiction = currentState.resolvedJurisdiction.code
                )
                val updatedSafety = SafetyGuard.evaluateSafety(newCategory, false)
                val newCandidates = WasteTaxonomy.getCandidates(newCategory)

                val updatedIdentification = HierarchicalIdentification(
                    coarseCategory = newCategory,
                    specificItem = resolvedItem,
                    materialCode = materialCode,
                    materialDescription = candidateObj?.description ?: "User confirmed waste item",
                    identificationSource = IdentificationSource.USER_CONFIRMED,
                    confidence = 1.0f,
                    evidenceSummary = "User confirmed as '$newCategory' (${resolvedItem ?: "general"})",
                    needsUserConfirmation = false
                )

                // Update Room database entry with user correction
                if (currentState.scanId > 0) {
                    runCatching {
                        val existing = scanRepository.getScanById(currentState.scanId)
                        if (existing != null) {
                            scanRepository.updateScan(
                                existing.copy(
                                    category = newCategory,
                                    specificItem = resolvedItem,
                                    materialCode = materialCode,
                                    ruleId = updatedRule.id,
                                    isHazardous = updatedSafety.isHazardous,
                                    disposalMethod = updatedRule.disposalMethod,
                                    targetBinColor = updatedRule.targetBinColor,
                                    isUserConfirmed = true,
                                    userConfirmedCategory = newCategory
                                )
                            )
                        }
                    }
                }

                _uiState.value = currentState.copy(
                    rule = updatedRule,
                    safety = updatedSafety,
                    hierarchicalIdentification = updatedIdentification,
                    isUserConfirmed = true,
                    userConfirmedLabel = resolvedItem ?: newCategory,
                    specificCandidates = newCandidates,
                    hasEvidenceConflict = false,
                    conflictExplanation = null
                )
            }
        }
    }

    /**
     * Submit human-in-the-loop feedback with optional user consent for anonymized sync.
     */
    fun submitFeedback(
        choice: String,
        userCorrectedCategory: String? = null,
        userCorrectedSpecificItem: String? = null,
        hasConsent: Boolean = false
    ) {
        val currentState = _uiState.value as? ScanResultUiState.Success ?: return

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val scanUuid = UUID.randomUUID().toString()
                val crop = currentState.detectedObjects.getOrNull(currentState.selectedObjectIndex)?.boundingBox
                val cropStr = crop?.let { "${it.left},${it.top},${it.right},${it.bottom}" }
                val topKFormatted = currentState.classification.topKResults.joinToString(";") { scored ->
                    "${scored.label}:${String.format(Locale.US, "%.3f", scored.score)}"
                }

                val feedbackChoice = when (choice) {
                    "CORRECT" -> FeedbackChoice.CORRECT
                    "WRONG_ITEM" -> FeedbackChoice.WRONG_ITEM
                    else -> FeedbackChoice.NOT_SURE
                }

                val feedbackEntity = ScanFeedbackEntity(
                    scanUuid = scanUuid,
                    originalCategory = currentState.classification.label,
                    originalConfidence = currentState.classification.confidence,
                    topKJson = topKFormatted,
                    detectorLabel = cropStr,
                    userChoice = feedbackChoice,
                    userConfirmedCategory = userCorrectedCategory ?: userCorrectedSpecificItem,
                    modelVersion = "1.0.0-rexnet150",
                    preprocessingVersion = if (crop != null) "roi_crop" else "letterbox",
                    timestamp = System.currentTimeMillis(),
                    hasUploadConsent = hasConsent,
                    syncStatus = if (hasConsent) SyncState.PENDING_SYNC else SyncState.LOCAL_DRAFT
                )

                feedbackRepository?.saveFeedback(feedbackEntity)

                _uiState.value = currentState.copy(
                    feedbackSubmitted = true,
                    isUserConfirmed = (choice == "CORRECT" || choice == "WRONG_ITEM"),
                    userConfirmedLabel = userCorrectedSpecificItem ?: userCorrectedCategory ?: currentState.userConfirmedLabel
                )
            }
        }
    }

    fun speakGuidance() {
        val state = _uiState.value as? ScanResultUiState.Success ?: return
        val currentLabel = state.userConfirmedLabel
            ?: state.hierarchicalIdentification?.specificItem
            ?: state.classification.label

        val textToSpeak = buildString {
            append("Identified as $currentLabel. ")
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
        barcodeScanner.close()
        packagingOcrService.close()
    }
}
