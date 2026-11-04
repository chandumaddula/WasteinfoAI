package com.wasteinfoai.ai.barcode

import com.wasteinfoai.ai.taxonomy.HierarchicalIdentification
import com.wasteinfoai.ai.taxonomy.IdentificationSource
import com.wasteinfoai.ai.vision.ClassificationResult
import java.util.Locale

/**
 * Output of multi-modal evidence reconciliation across vision, barcode, and OCR.
 */
data class ReconciledEvidenceResult(
    val coarseCategory: String,
    val specificItem: String?,
    val materialCode: String?,
    val identificationSource: IdentificationSource,
    val confidence: Float,
    val evidenceSummary: String,
    val hasConflict: Boolean,
    val conflictReason: String?,
    val needsUserConfirmation: Boolean
) {
    fun toHierarchicalIdentification(): HierarchicalIdentification {
        return HierarchicalIdentification(
            coarseCategory = coarseCategory,
            specificItem = specificItem,
            materialCode = materialCode,
            materialDescription = specificItem ?: "$coarseCategory solid waste",
            identificationSource = identificationSource,
            confidence = confidence,
            evidenceSummary = evidenceSummary,
            needsUserConfirmation = needsUserConfirmation
        )
    }
}

/**
 * Reconciles multiple independent evidence streams (ExecuTorch visual classifier,
 * ML Kit Barcode packaging lookup, and on-pack OCR resin markings).
 * Preserves evidence provenance and flags conflicts rather than silently overriding.
 */
class EvidenceReconciliationEngine {

    companion object {
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.85f
    }

    fun reconcile(
        visualResult: ClassificationResult,
        barcodeProduct: ProductPackagingInfo?,
        ocrEvidence: OcrPackagingEvidence?,
        isCroppedRoi: Boolean = false
    ): ReconciledEvidenceResult {
        // Case 1: Barcode product packaging record available
        if (barcodeProduct != null && barcodeProduct.specificItem != null) {
            val visualCat = visualResult.label.lowercase().trim()
            val barcodeCat = barcodeProduct.coarseCategory.lowercase().trim()

            val isCoarseMatch = visualCat == barcodeCat ||
                    (visualCat == "cardboard" && barcodeCat == "paper") ||
                    (visualCat == "paper" && barcodeCat == "cardboard")

            // Check for stark discrepancy between high-confidence vision and barcode
            if (!isCoarseMatch && visualResult.confidence >= HIGH_CONFIDENCE_THRESHOLD && !visualResult.isUncertain) {
                return ReconciledEvidenceResult(
                    coarseCategory = visualResult.label,
                    specificItem = null,
                    materialCode = null,
                    identificationSource = if (isCroppedRoi) IdentificationSource.OBJECT_CROP_CLASSIFIER else IdentificationSource.VISION_CLASSIFIER,
                    confidence = visualResult.confidence,
                    evidenceSummary = "CONFLICT: Barcode corresponds to '${barcodeProduct.productName}' ($barcodeCat), but image visually resembles '${visualResult.label}' (${String.format(Locale.US, "%.0f%%", visualResult.confidence * 100)}).",
                    hasConflict = true,
                    conflictReason = "Visual appearance disagrees with scanned barcode product composition.",
                    needsUserConfirmation = true
                )
            }

            // Vision was accepted but disagrees (below the high-confidence bar): still use the barcode,
            // but flag it and ask the user instead of silently overriding.
            val disagrees = !isCoarseMatch && !visualResult.isUncertain
            return ReconciledEvidenceResult(
                coarseCategory = barcodeProduct.coarseCategory,
                specificItem = barcodeProduct.specificItem,
                materialCode = barcodeProduct.materialCode,
                identificationSource = IdentificationSource.BARCODE_LOOKUP,
                confidence = if (disagrees) 0.75f else 0.95f,
                evidenceSummary = "Matched packaging record for '${barcodeProduct.productName}' (${barcodeProduct.dataSource})" +
                        if (disagrees) "; image looks like '${visualResult.label}'" else "",
                hasConflict = disagrees,
                conflictReason = if (disagrees) "Image appears to be '${visualResult.label}' but barcode lists '$barcodeCat' packaging." else null,
                needsUserConfirmation = disagrees
            )
        }

        // Case 2: On-pack OCR resin code or instructions available
        if (ocrEvidence != null && ocrEvidence.materialCode != null) {
            val ocrCat = ocrEvidence.coarseCategory ?: "plastic"
            val visualCat = visualResult.label.lowercase().trim()

            // If visual classification is plastic/uncertain or matches OCR category
            if (visualCat == ocrCat || visualResult.isUncertain) {
                return ReconciledEvidenceResult(
                    coarseCategory = ocrCat,
                    specificItem = ocrEvidence.materialName,
                    materialCode = ocrEvidence.materialCode,
                    identificationSource = IdentificationSource.OCR_OR_SYMBOL,
                    confidence = maxOf(visualResult.confidence, ocrEvidence.confidence),
                    evidenceSummary = "Verified by on-pack recycling symbol '${ocrEvidence.materialCode}' (${ocrEvidence.materialName ?: "Resin #${ocrEvidence.resinCode}"})",
                    hasConflict = false,
                    conflictReason = null,
                    needsUserConfirmation = false
                )
            } else if (visualResult.confidence >= HIGH_CONFIDENCE_THRESHOLD) {
                return ReconciledEvidenceResult(
                    coarseCategory = visualResult.label,
                    specificItem = null,
                    materialCode = null,
                    identificationSource = if (isCroppedRoi) IdentificationSource.OBJECT_CROP_CLASSIFIER else IdentificationSource.VISION_CLASSIFIER,
                    confidence = visualResult.confidence,
                    evidenceSummary = "CONFLICT: On-pack OCR resin symbol indicates '${ocrEvidence.materialCode}' ($ocrCat), but visual classifier indicates '${visualResult.label}' (${String.format(Locale.US, "%.0f%%", visualResult.confidence * 100)}).",
                    hasConflict = true,
                    conflictReason = "Visual prediction '${visualResult.label}' disagrees with packaging resin mark '${ocrEvidence.materialCode}'.",
                    needsUserConfirmation = true
                )
            } else {
                // Moderate-confidence vision disagrees with the resin mark: keep vision, but ask the user.
                return ReconciledEvidenceResult(
                    coarseCategory = visualResult.label,
                    specificItem = null,
                    materialCode = null,
                    identificationSource = if (isCroppedRoi) IdentificationSource.OBJECT_CROP_CLASSIFIER else IdentificationSource.VISION_CLASSIFIER,
                    confidence = visualResult.confidence,
                    evidenceSummary = "Resin symbol '${ocrEvidence.materialCode}' ($ocrCat) disagrees with visual '${visualResult.label}'.",
                    hasConflict = true,
                    conflictReason = "Visual prediction '${visualResult.label}' disagrees with packaging resin mark '${ocrEvidence.materialCode}'.",
                    needsUserConfirmation = true
                )
            }
        }

        // Case 3: Standard Visual Model Classification
        val source = if (isCroppedRoi) IdentificationSource.OBJECT_CROP_CLASSIFIER else IdentificationSource.VISION_CLASSIFIER
        return ReconciledEvidenceResult(
            coarseCategory = visualResult.label,
            specificItem = visualResult.specificSubItem,
            materialCode = null,
            identificationSource = source,
            confidence = visualResult.confidence,
            evidenceSummary = if (isCroppedRoi) "Identified from salient object ROI using the on-device classifier" else "Identified from the full image using the on-device classifier",
            hasConflict = false,
            conflictReason = null,
            needsUserConfirmation = visualResult.isUncertain
        )
    }
}
