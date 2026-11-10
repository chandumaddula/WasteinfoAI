package com.wasteinfoai.features.scan

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wasteinfoai.ai.barcode.BarcodeScannerService
import com.wasteinfoai.ai.barcode.EvidenceReconciliationEngine
import com.wasteinfoai.ai.barcode.OcrPackagingEvidence
import com.wasteinfoai.ai.barcode.PackagingOcrService
import com.wasteinfoai.ai.barcode.ProductLookupService
import com.wasteinfoai.ai.barcode.ProductPackagingInfo
import com.wasteinfoai.ai.rules.DisposalRule
import com.wasteinfoai.ai.rules.DisposalRuleEngine
import com.wasteinfoai.ai.rules.SafetyAssessment
import com.wasteinfoai.ai.rules.SafetyGuard
import com.wasteinfoai.ai.taxonomy.HierarchicalIdentification
import com.wasteinfoai.ai.taxonomy.IdentificationSource
import com.wasteinfoai.ai.taxonomy.SpecificWasteCandidate
import com.wasteinfoai.ai.taxonomy.WasteTaxonomy
import com.wasteinfoai.ai.vision.AcceptanceState
import com.wasteinfoai.ai.vision.BoxProposals
import com.wasteinfoai.ai.vision.ClassificationResult
import com.wasteinfoai.ai.vision.ClassifiedCrop
import com.wasteinfoai.ai.vision.ItemSelection
import com.wasteinfoai.ai.vision.ImagePreprocessor
import com.wasteinfoai.ai.vision.ImageQualityAssessment
import com.wasteinfoai.ai.vision.ImageQualityAssessor
import com.wasteinfoai.ai.vision.PreprocessingStrategy
import com.wasteinfoai.ai.vision.WasteClassifier
import com.wasteinfoai.audio.SpeechTextFormatter
import com.wasteinfoai.audio.TextToSpeechManager
import com.wasteinfoai.core.language.CategoryLocalization
import com.wasteinfoai.core.language.LanguageManager
import com.wasteinfoai.camera.DetectedWasteItem
import com.wasteinfoai.camera.ImageDecoding
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

/** Full analysis of ONE item (one detected box, or the whole image when nothing was detected). */
data class ItemAnalysis(
    val source: DetectedWasteItem?,                 // null => whole image was classified
    val classification: ClassificationResult,
    val identification: HierarchicalIdentification,
    val rule: DisposalRule,
    val safety: SafetyAssessment,
    val candidates: List<SpecificWasteCandidate>,
    val barcode: String?,
    val productInfo: ProductPackagingInfo?,
    val ocrEvidence: OcrPackagingEvidence?,
    val hasConflict: Boolean,
    val conflictExplanation: String?,
    val scanId: Long = 0L,
    val isUserConfirmed: Boolean = false,
    val userConfirmedLabel: String? = null
)

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
        val detectedObjects: List<DetectedWasteItem> = emptyList(),
        val selectedObjectIndex: Int = 0,
        val isUserConfirmed: Boolean = false,
        val userConfirmedLabel: String? = null,
        val specificCandidates: List<SpecificWasteCandidate> = emptyList(),
        val ocrEvidence: OcrPackagingEvidence? = null,
        val hasEvidenceConflict: Boolean = false,
        val conflictExplanation: String? = null,
        val resolvedJurisdiction: WasteJurisdiction = WasteJurisdiction.INDIA_NATIONAL,
        val feedbackSubmitted: Boolean = false,
        /** NEW: every item found in the photo, already classified. Index == selectedObjectIndex. */
        val items: List<ItemAnalysis> = emptyList(),
        /** Detected regions the classifier did not accept as waste (hidden from the list). */
        val ignoredObjectCount: Int = 0
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
    private val locationHelper: LocationHelper? = null,
    private val languageManager: LanguageManager? = null
) : ViewModel() {

    private companion object {
        const val MAX_ITEMS = 6
    }

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
                // 1. ONE upright bitmap (EXIF applied) used for EVERYTHING below: display, quality, detection,
                //    barcode, OCR and classification. Previously detection ran on the raw (sideways) decode while
                //    the classifier rotated the file before cropping, so every ROI pointed at the wrong region.
                val bitmap = ImageDecoding.decodeOriented(file, ImageDecoding.PREVIEW_MAX_DIMENSION)
                if (bitmap == null) {
                    _uiState.value = ScanResultUiState.Error("Could not decode the image.", file)
                    return@withContext
                }

                // 2. Quality (on the original-resolution upright bitmap)
                val quality = ImageQualityAssessor.assess(bitmap)

                // 3. Item boxes: drop specks / whole-scene boxes / duplicates
                val raw: List<DetectedWasteItem> =
                    runCatching { objectDetector.detectObjects(bitmap) }.getOrDefault(emptyList())
                val keep = BoxProposals.refine(
                    boxes = raw.map { RectF(it.boundingBox.left, it.boundingBox.top, it.boundingBox.right, it.boundingBox.bottom) },
                    scores = raw.map { it.saliencyScore },
                    maxBoxes = MAX_ITEMS
                )
                val proposals: List<DetectedWasteItem?> = if (keep.isEmpty()) listOf(null) else keep.map { raw[it] }

                // 4. Jurisdiction once per scan
                val coords = locationHelper?.getLastKnownLocation()
                val jurisdiction = JurisdictionResolver.resolveJurisdiction(
                    latitude = coords?.latitude,
                    longitude = coords?.longitude
                )

                // 5. Classify EVERY item (not just the "most salient" one, which was usually the biggest blob)
                var firstError: Throwable? = null
                val analyses = ArrayList<ItemAnalysis>()
                for (proposal in proposals) {
                    analyzeItem(bitmap, proposal, jurisdiction.code)
                        .onSuccess { analyses += it }
                        .onFailure { if (firstError == null) firstError = it }
                }
                if (analyses.isEmpty()) {
                    _uiState.value = ScanResultUiState.Error(
                        "Classification failed: ${firstError?.localizedMessage}", file
                    )
                    return@withContext
                }

                // 6. Which items to show: accepted ones + plausible-but-unsure ones (e.g. a mouse on a pile of papers),
                //    same-category duplicates merged, probable non-waste (hands, furniture) dropped. See ItemSelection.
                val selection = ItemSelection.select(
                    analyses.map {
                        ClassifiedCrop(
                            accepted = it.classification.isAccepted,
                            state = it.classification.acceptanceState,
                            confidence = it.classification.confidence,
                            category = it.categoryKey(),
                            box = it.rectOrNull()
                        )
                    },
                    maxItems = MAX_ITEMS
                )
                val shown = selection.shown.map { analyses[it] }
                val ignoredCount = selection.hiddenAsNonWaste

                // 7. Persist one history row per shown item
                val saved = shown.map { it.copy(scanId = saveItem(file, it)) }
                val first = saved.first()

                _uiState.value = ScanResultUiState.Success(
                    imageFile = file,
                    bitmap = bitmap,
                    classification = first.classification,
                    rule = first.rule,
                    safety = first.safety,
                    isSaved = true,
                    scanId = first.scanId,
                    qualityAssessment = quality,
                    hierarchicalIdentification = first.identification,
                    detectedBarcode = first.barcode,
                    productPackagingInfo = first.productInfo,
                    detectedObjectsCount = saved.size,
                    detectedObjects = saved.mapNotNull { it.source },
                    selectedObjectIndex = 0,
                    isUserConfirmed = false,
                    specificCandidates = first.candidates,
                    ocrEvidence = first.ocrEvidence,
                    hasEvidenceConflict = first.hasConflict,
                    conflictExplanation = first.conflictExplanation,
                    resolvedJurisdiction = jurisdiction,
                    feedbackSubmitted = false,
                    items = saved,
                    ignoredObjectCount = ignoredCount
                )
            }
        }
    }

    /** Classifies one crop and gathers barcode / OCR evidence FROM THAT CROP only. */
    private suspend fun analyzeItem(
        bitmap: Bitmap,
        source: DetectedWasteItem?,
        jurisdictionCode: String
    ): kotlin.Result<ItemAnalysis> {
        val rect: RectF? = source?.let {
            RectF(it.boundingBox.left, it.boundingBox.top, it.boundingBox.right, it.boundingBox.bottom)
        }
        // RESIZE_FULL matches how the model was trained; LETTERBOX (gray bars) was never seen in training.
        val strategy = if (rect != null) PreprocessingStrategy.ROI_CROP else PreprocessingStrategy.RESIZE_FULL

        val rawClassification = wasteClassifier.classify(bitmap, strategy, rect)
            .getOrElse { return kotlin.Result.failure(it) }

        // Models trained with an explicit "not_waste" class (hands, clothes, furniture, backgrounds).
        // topK[0] is checked because a rejected result carries a generic label, not the class name.
        val classification = if (rawClassification.topKResults.firstOrNull()?.label.equals("not_waste", ignoreCase = true)) {
            ClassificationResult.rejected(
                state = AcceptanceState.LIKELY_NON_WASTE,
                reason = "This looks like a non-waste object (hand, clothing, furniture or background).",
                durationMs = rawClassification.inferenceDurationMs,
                topK = rawClassification.topKResults.filterNot { it.label.equals("not_waste", ignoreCase = true) },
                policyResult = rawClassification.policyResult
            )
        } else {
            rawClassification
        }

        // Barcode / OCR run on this item's crop so one item's barcode never labels a different item.
        val crop = if (rect != null) ImagePreprocessor.cropRoi(bitmap, rect, 0.10f) else bitmap
        val barcode: String?
        val productInfo: ProductPackagingInfo?
        val ocrEvidence: OcrPackagingEvidence?
        try {
            barcode = barcodeScanner.scanBarcode(crop)
            productInfo = barcode?.let { productLookupService.lookup(it) }
            // Resin marks only matter for plastic (or when the model is unsure): skips ~300 ms OCR per item otherwise.
            val needsOcr = !classification.isAccepted || classification.label.equals("plastic", ignoreCase = true)
            ocrEvidence = if (needsOcr) packagingOcrService.scanPackagingText(crop) else null
        } finally {
            if (crop !== bitmap) crop.recycle()
        }

        val reconciliation = evidenceReconciliationEngine.reconcile(
            visualResult = classification,
            barcodeProduct = productInfo,
            ocrEvidence = ocrEvidence,
            isCroppedRoi = rect != null
        )
        val effectiveCategory = reconciliation.coarseCategory
        val rule = disposalRuleEngine.getHierarchicalRule(
            category = effectiveCategory,
            specificItem = reconciliation.specificItem,
            materialCode = reconciliation.materialCode,
            jurisdiction = jurisdictionCode
        )
        val safety = SafetyGuard.evaluateSafety(effectiveCategory, classification.isUncertain)

        return kotlin.Result.success(
            ItemAnalysis(
                source = source,
                classification = classification,
                identification = reconciliation.toHierarchicalIdentification(),
                rule = rule,
                safety = safety,
                candidates = WasteTaxonomy.getCandidates(effectiveCategory),
                barcode = barcode,
                productInfo = productInfo,
                ocrEvidence = ocrEvidence,
                hasConflict = reconciliation.hasConflict,
                conflictExplanation = reconciliation.conflictReason
            )
        )
    }

    private fun ItemAnalysis.categoryKey(): String =
        classification.topKResults.firstOrNull()?.label ?: classification.label

    private fun ItemAnalysis.rectOrNull(): RectF? = source?.let {
        RectF(it.boundingBox.left, it.boundingBox.top, it.boundingBox.right, it.boundingBox.bottom)
    }

    private suspend fun saveItem(file: File, item: ItemAnalysis): Long = runCatching {
        scanRepository.saveScan(
            ScanHistoryEntity(
                imagePath = file.absolutePath,
                category = item.identification.coarseCategory,
                confidence = item.classification.confidence,
                timestamp = System.currentTimeMillis(),
                ruleId = item.rule.id,
                isHazardous = item.safety.isHazardous,
                disposalMethod = item.rule.disposalMethod,
                targetBinColor = item.rule.targetBinColor,
                isSynced = false,
                specificItem = item.identification.specificItem,
                materialCode = item.identification.materialCode,
                isUserConfirmed = false,
                detectedBarcode = item.barcode
            )
        )
    }.getOrDefault(0L)

    private fun ScanResultUiState.Success.withItem(index: Int): ScanResultUiState.Success {
        val item = items[index]
        return copy(
            classification = item.classification,
            rule = item.rule,
            safety = item.safety,
            hierarchicalIdentification = item.identification,
            detectedBarcode = item.barcode,
            productPackagingInfo = item.productInfo,
            ocrEvidence = item.ocrEvidence,
            hasEvidenceConflict = item.hasConflict,
            conflictExplanation = item.conflictExplanation,
            specificCandidates = item.candidates,
            scanId = item.scanId,
            selectedObjectIndex = index,
            isUserConfirmed = item.isUserConfirmed,
            userConfirmedLabel = item.userConfirmedLabel
        )
    }

    /** Switches to another already-analysed item. Instant: nothing is re-cropped or re-classified. */
    fun selectDetectedObject(index: Int) {
        val currentState = _uiState.value as? ScanResultUiState.Success ?: return
        if (index !in currentState.items.indices) return
        _uiState.value = currentState.withItem(index)
    }

    /**
     * User correction for the SELECTED item: recomputes its rule/safety, stores it back into [items]
     * (so it survives switching items) and updates its Room row.
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

                val label = resolvedItem ?: newCategory
                val updatedItems = currentState.items.mapIndexed { i, item ->
                    if (i == currentState.selectedObjectIndex) {
                        item.copy(
                            rule = updatedRule,
                            safety = updatedSafety,
                            identification = updatedIdentification,
                            candidates = newCandidates,
                            hasConflict = false,
                            conflictExplanation = null,
                            isUserConfirmed = true,
                            userConfirmedLabel = label
                        )
                    } else item
                }

                _uiState.value = currentState.copy(
                    rule = updatedRule,
                    safety = updatedSafety,
                    hierarchicalIdentification = updatedIdentification,
                    isUserConfirmed = true,
                    userConfirmedLabel = label,
                    specificCandidates = newCandidates,
                    hasEvidenceConflict = false,
                    conflictExplanation = null,
                    items = updatedItems
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
                    modelVersion = currentState.classification.modelVersion,
                    preprocessingVersion = if (crop != null) "roi_crop" else "resize_full",
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

    fun speakGuidance(targetLanguage: String? = null) {
        val state = _uiState.value as? ScanResultUiState.Success ?: return
        val lang = targetLanguage?.trim()?.lowercase()?.take(2)
            ?: languageManager?.currentLanguage?.value
            ?: Locale.getDefault().language.trim().lowercase().take(2)

        val currentItem = state.items.getOrNull(state.selectedObjectIndex)
        val rawItem = currentItem?.userConfirmedLabel
            ?: currentItem?.identification?.specificItem
            ?: currentItem?.identification?.coarseCategory
            ?: state.userConfirmedLabel
            ?: state.hierarchicalIdentification?.specificItem
            ?: state.classification.label

        val localizedItemName = CategoryLocalization.getCategoryDisplayName(rawItem, lang)
        val targetBinColor = currentItem?.rule?.targetBinColor ?: state.rule?.targetBinColor ?: ""
        val localizedBin = CategoryLocalization.getBinColorDisplayName(targetBinColor, lang)

        val localizedRule = disposalRuleEngine.getHierarchicalRule(
            category = state.classification.label,
            specificItem = currentItem?.identification?.specificItem ?: state.hierarchicalIdentification?.specificItem ?: state.userConfirmedLabel,
            materialCode = currentItem?.identification?.materialCode ?: state.hierarchicalIdentification?.materialCode,
            languageCode = lang
        )

        val isHazardous = state.safety.isHazardous || currentItem?.safety?.isHazardous == true || localizedRule.isHazardous
        val safetyWarning = localizedRule.safetyWarnings.ifBlank {
            currentItem?.safety?.primaryWarning ?: state.safety.primaryWarning
        }

        val speechText = when (lang) {
            "te" -> buildString {
                append("గుర్తించిన వస్తువు: $localizedItemName. ")
                if (isHazardous) {
                    append("హెచ్చరిక: ప్రమాదకర వ్యర్థాలు. $safetyWarning. ")
                }
                if (localizedBin.isNotBlank()) {
                    append("దీనిని $localizedBin లో వేయండి. ")
                }
                if (localizedRule.disposalMethod.isNotBlank()) {
                    append("పారవేసే విధానం: ${localizedRule.disposalMethod}. ")
                }
                if (localizedRule.preparationInstructions.isNotBlank()) {
                    append(localizedRule.preparationInstructions)
                }
            }
            "hi" -> buildString {
                append("पहचाना गया: $localizedItemName। ")
                if (isHazardous) {
                    append("चेतावनी: खतरनाक कचरा। $safetyWarning। ")
                }
                if (localizedBin.isNotBlank()) {
                    append("इसे $localizedBin में डालें। ")
                }
                if (localizedRule.disposalMethod.isNotBlank()) {
                    append("निपटान विधि: ${localizedRule.disposalMethod}। ")
                }
                if (localizedRule.preparationInstructions.isNotBlank()) {
                    append(localizedRule.preparationInstructions)
                }
            }
            "ta" -> buildString {
                append("கண்டறியப்பட்டது: $localizedItemName. ")
                if (isHazardous) {
                    append("எச்சரிக்கை: அபாயகரமான கழிவு. $safetyWarning. ")
                }
                if (localizedBin.isNotBlank()) {
                    append("இதை $localizedBin யில் போடவும். ")
                }
                if (localizedRule.disposalMethod.isNotBlank()) {
                    append("அகற்றும் முறை: ${localizedRule.disposalMethod}. ")
                }
                if (localizedRule.preparationInstructions.isNotBlank()) {
                    append(localizedRule.preparationInstructions)
                }
            }
            "mr" -> buildString {
                append("ओळखले: $localizedItemName. ")
                if (isHazardous) {
                    append("धोका: धोकादायक कचरा. $safetyWarning. ")
                }
                if (localizedBin.isNotBlank()) {
                    append("हे $localizedBin मध्ये टाका. ")
                }
                if (localizedRule.disposalMethod.isNotBlank()) {
                    append("विल्हेवाट पद्धत: ${localizedRule.disposalMethod}. ")
                }
                if (localizedRule.preparationInstructions.isNotBlank()) {
                    append(localizedRule.preparationInstructions)
                }
            }
            "kn" -> buildString {
                append("ಗುರುತಿಸಲಾದ ವಸ್ತು: $localizedItemName. ")
                if (isHazardous) {
                    append("ಎಚ್ಚರಿಕೆ: ಅಪಾಯಕಾರಿ ತ್ಯಾಜ್ಯ. $safetyWarning. ")
                }
                if (localizedBin.isNotBlank()) {
                    append("ಇದನ್ನು $localizedBin ನಲ್ಲಿ ಹಾಕಿ. ")
                }
                if (localizedRule.disposalMethod.isNotBlank()) {
                    append("ವಿಲೇವಾರಿ ವಿಧಾನ: ${localizedRule.disposalMethod}. ")
                }
                if (localizedRule.preparationInstructions.isNotBlank()) {
                    append(localizedRule.preparationInstructions)
                }
            }
            "ml" -> buildString {
                append("കണ്ടെത്തിയ ഇനം: $localizedItemName. ")
                if (isHazardous) {
                    append("മുന്നറിയിപ്പ്: അപകടകരമായ മാലിന്യം. $safetyWarning. ")
                }
                if (localizedBin.isNotBlank()) {
                    append("ഇത് $localizedBin ൽ നിക്ഷേപിക്കുക. ")
                }
                if (localizedRule.disposalMethod.isNotBlank()) {
                    append("സംസ്കരണ രീതി: ${localizedRule.disposalMethod}. ")
                }
                if (localizedRule.preparationInstructions.isNotBlank()) {
                    append(localizedRule.preparationInstructions)
                }
            }
            "bn" -> buildString {
                append("শনাক্তকৃত বস্তু: $localizedItemName। ")
                if (isHazardous) {
                    append("সতর্কতা: বিপজ্জনক বর্জ্য। $safetyWarning। ")
                }
                if (localizedBin.isNotBlank()) {
                    append("এটি $localizedBin এ ফেলুন। ")
                }
                if (localizedRule.disposalMethod.isNotBlank()) {
                    append("নিষ্পত্তি পদ্ধতি: ${localizedRule.disposalMethod}। ")
                }
                if (localizedRule.preparationInstructions.isNotBlank()) {
                    append(localizedRule.preparationInstructions)
                }
            }
            "gu" -> buildString {
                append("ઓળખાયેલ વસ્તુ: $localizedItemName. ")
                if (isHazardous) {
                    append("ચેતવણી: જોખમી કચરો. $safetyWarning. ")
                }
                if (localizedBin.isNotBlank()) {
                    append("આને $localizedBin માં નાખો. ")
                }
                if (localizedRule.disposalMethod.isNotBlank()) {
                    append("નિકાલ પદ્ધતિ: ${localizedRule.disposalMethod}. ")
                }
                if (localizedRule.preparationInstructions.isNotBlank()) {
                    append(localizedRule.preparationInstructions)
                }
            }
            else -> buildString {
                append("Identified as $localizedItemName. ")
                if (isHazardous) {
                    append("Warning: Hazardous waste. $safetyWarning. ")
                }
                if (localizedBin.isNotBlank()) {
                    append("Place in $localizedBin. ")
                }
                if (localizedRule.disposalMethod.isNotBlank()) {
                    append("Disposal method: ${localizedRule.disposalMethod}. ")
                }
                if (localizedRule.preparationInstructions.isNotBlank()) {
                    append(localizedRule.preparationInstructions)
                }
            }
        }

        val formatted = SpeechTextFormatter.formatForSpeech(speechText, lang)
        if (formatted.isNotBlank()) {
            ttsManager.speak(formatted, languageCode = lang)
        }
    }

    /** Reads every item found in the photo in the active display language. */
    fun speakAllItems(targetLanguage: String? = null) {
        val state = _uiState.value as? ScanResultUiState.Success ?: return
        val lang = targetLanguage?.trim()?.lowercase()?.take(2)
            ?: languageManager?.currentLanguage?.value
            ?: Locale.getDefault().language.trim().lowercase().take(2)

        val text = when (lang) {
            "te" -> buildString {
                append("మొత్తం ${state.items.size} వస్తువులు గుర్తించబడ్డాయి. ")
                state.items.forEachIndexed { i, item ->
                    val rawLabel = item.userConfirmedLabel
                        ?: item.identification.specificItem
                        ?: item.identification.coarseCategory
                    val itemLabel = CategoryLocalization.getCategoryDisplayName(rawLabel, lang)
                    val binName = CategoryLocalization.getBinColorDisplayName(item.rule.targetBinColor, lang)
                    val rule = disposalRuleEngine.getHierarchicalRule(
                        category = item.classification.label,
                        specificItem = item.identification.specificItem,
                        languageCode = lang
                    )
                    append("వస్తువు ${i + 1}: $itemLabel. ")
                    if (binName.isNotBlank()) append("$binName లో వేయండి. ")
                    if (rule.disposalMethod.isNotBlank()) append("${rule.disposalMethod}. ")
                }
            }
            "hi" -> buildString {
                append("कुल ${state.items.size} वस्तुएं मिलीं। ")
                state.items.forEachIndexed { i, item ->
                    val rawLabel = item.userConfirmedLabel
                        ?: item.identification.specificItem
                        ?: item.identification.coarseCategory
                    val itemLabel = CategoryLocalization.getCategoryDisplayName(rawLabel, lang)
                    val binName = CategoryLocalization.getBinColorDisplayName(item.rule.targetBinColor, lang)
                    val rule = disposalRuleEngine.getHierarchicalRule(
                        category = item.classification.label,
                        specificItem = item.identification.specificItem,
                        languageCode = lang
                    )
                    append("वस्तु ${i + 1}: $itemLabel। ")
                    if (binName.isNotBlank()) append("$binName में डालें। ")
                    if (rule.disposalMethod.isNotBlank()) append("${rule.disposalMethod}। ")
                }
            }
            "ta" -> buildString {
                append("மொத்தம் ${state.items.size} பொருட்கள் கண்டறியப்பட்டன. ")
                state.items.forEachIndexed { i, item ->
                    val rawLabel = item.userConfirmedLabel
                        ?: item.identification.specificItem
                        ?: item.identification.coarseCategory
                    val itemLabel = CategoryLocalization.getCategoryDisplayName(rawLabel, lang)
                    val binName = CategoryLocalization.getBinColorDisplayName(item.rule.targetBinColor, lang)
                    val rule = disposalRuleEngine.getHierarchicalRule(
                        category = item.classification.label,
                        specificItem = item.identification.specificItem,
                        languageCode = lang
                    )
                    append("பொருள் ${i + 1}: $itemLabel. ")
                    if (binName.isNotBlank()) append("$binName யில் போடவும். ")
                    if (rule.disposalMethod.isNotBlank()) append("${rule.disposalMethod}. ")
                }
            }
            "mr" -> buildString {
                append("एकूण ${state.items.size} वस्तू सापडल्या. ")
                state.items.forEachIndexed { i, item ->
                    val rawLabel = item.userConfirmedLabel
                        ?: item.identification.specificItem
                        ?: item.identification.coarseCategory
                    val itemLabel = CategoryLocalization.getCategoryDisplayName(rawLabel, lang)
                    val binName = CategoryLocalization.getBinColorDisplayName(item.rule.targetBinColor, lang)
                    val rule = disposalRuleEngine.getHierarchicalRule(
                        category = item.classification.label,
                        specificItem = item.identification.specificItem,
                        languageCode = lang
                    )
                    append("वस्तू ${i + 1}: $itemLabel. ")
                    if (binName.isNotBlank()) append("$binName मध्ये टाका. ")
                    if (rule.disposalMethod.isNotBlank()) append("${rule.disposalMethod}. ")
                }
            }
            "kn" -> buildString {
                append("ಒಟ್ಟು ${state.items.size} ವಸ್ತುಗಳು ಕಂಡುಬಂದಿವೆ. ")
                state.items.forEachIndexed { i, item ->
                    val rawLabel = item.userConfirmedLabel
                        ?: item.identification.specificItem
                        ?: item.identification.coarseCategory
                    val itemLabel = CategoryLocalization.getCategoryDisplayName(rawLabel, lang)
                    val binName = CategoryLocalization.getBinColorDisplayName(item.rule.targetBinColor, lang)
                    val rule = disposalRuleEngine.getHierarchicalRule(
                        category = item.classification.label,
                        specificItem = item.identification.specificItem,
                        languageCode = lang
                    )
                    append("ವಸ್ತು ${i + 1}: $itemLabel. ")
                    if (binName.isNotBlank()) append("$binName ನಲ್ಲಿ ಹಾಕಿ. ")
                    if (rule.disposalMethod.isNotBlank()) append("${rule.disposalMethod}. ")
                }
            }
            "ml" -> buildString {
                append("ആകെ ${state.items.size} ഇനങ്ങൾ കണ്ടെത്തി. ")
                state.items.forEachIndexed { i, item ->
                    val rawLabel = item.userConfirmedLabel
                        ?: item.identification.specificItem
                        ?: item.identification.coarseCategory
                    val itemLabel = CategoryLocalization.getCategoryDisplayName(rawLabel, lang)
                    val binName = CategoryLocalization.getBinColorDisplayName(item.rule.targetBinColor, lang)
                    val rule = disposalRuleEngine.getHierarchicalRule(
                        category = item.classification.label,
                        specificItem = item.identification.specificItem,
                        languageCode = lang
                    )
                    append("ഇനം ${i + 1}: $itemLabel. ")
                    if (binName.isNotBlank()) append("$binName ൽ ഇടുക. ")
                    if (rule.disposalMethod.isNotBlank()) append("${rule.disposalMethod}. ")
                }
            }
            "bn" -> buildString {
                append("মোট ${state.items.size}টি বস্তু পাওয়া গেছে। ")
                state.items.forEachIndexed { i, item ->
                    val rawLabel = item.userConfirmedLabel
                        ?: item.identification.specificItem
                        ?: item.identification.coarseCategory
                    val itemLabel = CategoryLocalization.getCategoryDisplayName(rawLabel, lang)
                    val binName = CategoryLocalization.getBinColorDisplayName(item.rule.targetBinColor, lang)
                    val rule = disposalRuleEngine.getHierarchicalRule(
                        category = item.classification.label,
                        specificItem = item.identification.specificItem,
                        languageCode = lang
                    )
                    append("বস্তু ${i + 1}: $itemLabel। ")
                    if (binName.isNotBlank()) append("$binName এ ফেলুন। ")
                    if (rule.disposalMethod.isNotBlank()) append("${rule.disposalMethod}। ")
                }
            }
            "gu" -> buildString {
                append("કુલ ${state.items.size} વસ્તુઓ મળી. ")
                state.items.forEachIndexed { i, item ->
                    val rawLabel = item.userConfirmedLabel
                        ?: item.identification.specificItem
                        ?: item.identification.coarseCategory
                    val itemLabel = CategoryLocalization.getCategoryDisplayName(rawLabel, lang)
                    val binName = CategoryLocalization.getBinColorDisplayName(item.rule.targetBinColor, lang)
                    val rule = disposalRuleEngine.getHierarchicalRule(
                        category = item.classification.label,
                        specificItem = item.identification.specificItem,
                        languageCode = lang
                    )
                    append("વસ્તુ ${i + 1}: $itemLabel. ")
                    if (binName.isNotBlank()) append("$binName માં નાખો. ")
                    if (rule.disposalMethod.isNotBlank()) append("${rule.disposalMethod}. ")
                }
            }
            else -> buildString {
                append("Found ${state.items.size} items. ")
                state.items.forEachIndexed { i, item ->
                    val rawLabel = item.userConfirmedLabel
                        ?: item.identification.specificItem
                        ?: item.identification.coarseCategory
                    val itemLabel = CategoryLocalization.getCategoryDisplayName(rawLabel, lang)
                    val binName = CategoryLocalization.getBinColorDisplayName(item.rule.targetBinColor, lang)
                    append("Item ${i + 1}: $itemLabel. ")
                    if (binName.isNotBlank()) append("Place in $binName. ")
                    if (item.rule.disposalMethod.isNotBlank()) append("${item.rule.disposalMethod}. ")
                }
            }
        }
        val formatted = SpeechTextFormatter.formatForSpeech(text, lang)
        if (formatted.isNotBlank()) {
            ttsManager.speak(formatted, languageCode = lang)
        }
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
