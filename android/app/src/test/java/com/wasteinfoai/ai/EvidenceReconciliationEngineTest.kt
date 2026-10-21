package com.wasteinfoai.ai

import com.wasteinfoai.ai.barcode.EvidenceReconciliationEngine
import com.wasteinfoai.ai.barcode.OcrPackagingEvidence
import com.wasteinfoai.ai.barcode.ProductPackagingInfo
import com.wasteinfoai.ai.barcode.ReconciledEvidenceResult
import com.wasteinfoai.ai.taxonomy.IdentificationSource
import com.wasteinfoai.ai.vision.AcceptanceState
import com.wasteinfoai.ai.vision.ClassScore
import com.wasteinfoai.ai.vision.ClassificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceReconciliationEngineTest {

    private val engine = EvidenceReconciliationEngine()

    @Test
    fun visualOnly_withoutBarcodeOrOcr_usesVisualClassification() {
        val visual = ClassificationResult(
            classId = 2,
            label = "cardboard",
            confidence = 0.92f,
            inferenceDurationMs = 15L,
            topKResults = listOf(ClassScore(2, "cardboard", 0.92f)),
            acceptanceState = AcceptanceState.ACCEPTED
        )

        val result = engine.reconcile(
            visualResult = visual,
            barcodeProduct = null,
            ocrEvidence = null
        )

        assertEquals("cardboard", result.coarseCategory)
        assertFalse(result.hasConflict)
        assertEquals(IdentificationSource.VISION_CLASSIFIER, result.identificationSource)
    }

    @Test
    fun barcodeAgreement_refinesIdentificationWithoutConflict() {
        val visual = ClassificationResult(
            classId = 1,
            label = "metal",
            confidence = 0.88f,
            inferenceDurationMs = 15L,
            topKResults = listOf(ClassScore(1, "metal", 0.88f)),
            acceptanceState = AcceptanceState.ACCEPTED
        )
        val barcodeInfo = ProductPackagingInfo(
            barcode = "8901234567890",
            productName = "Sparkling Soda Can 330ml",
            brands = "Beverage Co",
            packagingDescription = "Aluminum cylindrical can",
            coarseCategory = "metal",
            specificItem = "Aluminum Beverage Can",
            materialCode = "ALU 41",
            components = emptyList(),
            dataSource = "Open Food Facts"
        )

        val result = engine.reconcile(
            visualResult = visual,
            barcodeProduct = barcodeInfo,
            ocrEvidence = null
        )

        assertEquals("metal", result.coarseCategory)
        assertEquals("Aluminum Beverage Can", result.specificItem)
        assertEquals("ALU 41", result.materialCode)
        assertFalse(result.hasConflict)
        assertEquals(IdentificationSource.BARCODE_LOOKUP, result.identificationSource)
    }

    @Test
    fun barcodeConflict_withVisual_flagsConflictAndPromptsUserConfirmation() {
        val visual = ClassificationResult(
            classId = 5,
            label = "organic",
            confidence = 0.89f,
            inferenceDurationMs = 15L,
            topKResults = listOf(ClassScore(5, "organic", 0.89f)),
            acceptanceState = AcceptanceState.ACCEPTED
        )
        val barcodeInfo = ProductPackagingInfo(
            barcode = "8901111222333",
            productName = "Canned Baked Beans",
            brands = "Bean Co",
            packagingDescription = "Steel can",
            coarseCategory = "metal",
            specificItem = "Tin-Coated Steel Food Can",
            materialCode = "FE 40",
            components = emptyList(),
            dataSource = "Open Food Facts"
        )

        val result = engine.reconcile(
            visualResult = visual,
            barcodeProduct = barcodeInfo,
            ocrEvidence = null
        )

        assertTrue(result.hasConflict)
        assertNotNull(result.conflictReason)
        assertTrue(result.needsUserConfirmation)
    }

    @Test
    fun ocrRicEvidence_matchingPlastic_refinesToPetSpecificItem() {
        val visual = ClassificationResult(
            classId = 0,
            label = "plastic",
            confidence = 0.85f,
            inferenceDurationMs = 15L,
            topKResults = listOf(ClassScore(0, "plastic", 0.85f)),
            acceptanceState = AcceptanceState.ACCEPTED
        )
        val ocr = OcrPackagingEvidence(
            resinCode = 1,
            materialCode = "PET 01",
            materialName = "Polyethylene Terephthalate (PET)",
            coarseCategory = "plastic",
            detectedKeywords = listOf("PLEASE RECYCLE"),
            confidence = 0.92f,
            rawMatchedText = "PET 1"
        )

        val result = engine.reconcile(
            visualResult = visual,
            barcodeProduct = null,
            ocrEvidence = ocr
        )

        assertEquals("plastic", result.coarseCategory)
        assertEquals("PET 01", result.materialCode)
        assertEquals("Polyethylene Terephthalate (PET)", result.specificItem)
        assertFalse(result.hasConflict)
        assertEquals(IdentificationSource.OCR_OR_SYMBOL, result.identificationSource)
    }

    @Test
    fun ocrRicEvidence_conflictingWithVisualCategory_flagsConflict() {
        val visual = ClassificationResult(
            classId = 3,
            label = "glass",
            confidence = 0.88f,
            inferenceDurationMs = 15L,
            topKResults = listOf(ClassScore(3, "glass", 0.88f)),
            acceptanceState = AcceptanceState.ACCEPTED
        )
        val ocr = OcrPackagingEvidence(
            resinCode = 2,
            materialCode = "HDPE 02",
            materialName = "High-Density Polyethylene (HDPE)",
            coarseCategory = "plastic",
            detectedKeywords = emptyList(),
            confidence = 0.92f,
            rawMatchedText = "HDPE"
        )

        val result = engine.reconcile(
            visualResult = visual,
            barcodeProduct = null,
            ocrEvidence = ocr
        )

        assertTrue(result.hasConflict)
        assertTrue(result.conflictReason!!.contains("HDPE 02"))
    }
}
