package com.wasteinfoai.ai

import com.wasteinfoai.ai.vision.AcceptancePolicyConfig
import com.wasteinfoai.ai.vision.AcceptanceState
import com.wasteinfoai.ai.vision.PredictionAcceptancePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PredictionAcceptancePolicyTest {

    private val canonical17Classes = listOf(
        "Battery", "Keyboard", "Microwave", "Mobile", "Mouse",
        "PCB", "Player", "Printer", "Television", "Washing Machine",
        "cardboard", "glass", "metal", "organic", "paper",
        "plastic", "trash"
    )

    private lateinit var policy: PredictionAcceptancePolicy

    @Before
    fun setUp() {
        policy = PredictionAcceptancePolicy(
            AcceptancePolicyConfig(
                confidenceThreshold = 0.62f,
                minMarginThreshold = 0.15f,
                maxNormalizedEntropy = 0.70f,
                oodConfidenceFloor = 0.35f
            )
        )
    }

    @Test
    fun `confident genuine waste prediction is ACCEPTED`() {
        // High confidence for "plastic", low for others
        val logits = FloatArray(17) { 0.1f }
        logits[15] = 5.5f // plastic logit >> others

        val result = policy.evaluate(logits, canonical17Classes)

        assertEquals(AcceptanceState.ACCEPTED, result.state)
        assertTrue(result.isAccepted)
        assertEquals("plastic", result.top1Class)
        assertEquals(15, result.top1ClassId)
        assertTrue("Confidence should be high", result.confidence > 0.85f)
        assertTrue("Margin should be high", result.topMargin > 0.60f)
        assertTrue("Entropy should be low", result.normalizedEntropy < 0.50f)
        assertFalse(result.shouldOfferManualSelection)
    }

    @Test
    fun `empty background or scenery produces diffuse probabilities and is rejected as LIKELY_NON_WASTE`() {
        // All logits almost identical (~0.0588 per class)
        val logits = FloatArray(17) { 0.05f }
        val result = policy.evaluate(logits, canonical17Classes)

        assertEquals(AcceptanceState.LIKELY_NON_WASTE, result.state)
        assertFalse(result.isAccepted)
        assertTrue("Max confidence below OOD floor", result.confidence < 0.35f)
        assertEquals(
            "No clear waste item detected. Please position the item inside the frame and try again.",
            result.userFacingMessage
        )
        assertTrue(result.shouldOfferRecapture)
        assertTrue(result.shouldOfferManualSelection)
    }

    @Test
    fun `non-waste input like hand or pet with high entropy is rejected as OOD_SUSPECTED`() {
        // Probabilities spread across 4-5 classes with no decisive winner
        val probs = FloatArray(17) { 0.02f }
        probs[13] = 0.36f // organic (e.g. skin/fur)
        probs[15] = 0.20f // plastic
        probs[16] = 0.16f // trash
        // Remaining ~0.28 spread over 14 classes -> ~0.02 each

        val result = policy.evaluateProbabilities(probs, canonical17Classes)

        assertEquals(AcceptanceState.OOD_SUSPECTED, result.state)
        assertFalse(result.isAccepted)
        assertTrue(result.normalizedEntropy > 0.70f)
        assertEquals(
            "No clear waste item detected. Please position the item inside the frame and try again.",
            result.userFacingMessage
        )
    }

    @Test
    fun `competing classes with low margin triggers AMBIGUOUS state`() {
        // Top 2 classes very close (e.g., cardboard 45% vs paper 42% -> margin 3% < 15%)
        val probs = FloatArray(17) { 0.008f }
        probs[10] = 0.45f // cardboard
        probs[14] = 0.42f // paper

        val result = policy.evaluateProbabilities(probs, canonical17Classes)

        assertEquals(AcceptanceState.AMBIGUOUS, result.state)
        assertFalse(result.isAccepted)
        assertEquals(0.03f, result.topMargin, 0.005f)
        assertTrue(result.userFacingMessage.contains("Ambiguous material detected"))
        assertTrue(result.userFacingMessage.contains("cardboard"))
        assertTrue(result.userFacingMessage.contains("paper"))
        assertTrue(result.shouldOfferManualSelection)
    }

    @Test
    fun `prediction below 62 percent acceptance threshold triggers LOW_CONFIDENCE`() {
        // Dominant class has 56% (> 35% floor and > 15% margin over #2 (15%), but < 62%)
        val probs = FloatArray(17) { 0.018f }
        probs[12] = 0.56f // metal
        probs[16] = 0.15f // trash

        val result = policy.evaluateProbabilities(probs, canonical17Classes)

        assertEquals(AcceptanceState.LOW_CONFIDENCE, result.state)
        assertFalse(result.isAccepted)
        assertEquals(0.56f, result.confidence, 0.01f)
        assertEquals(
            "No clear waste item detected. Please position the item inside the frame and try again.",
            result.userFacingMessage
        )
    }

    @Test
    fun `boundary threshold test - 61 percent rejected vs 63 percent accepted`() {
        val probsRejected = FloatArray(17) { 0.015f }
        probsRejected[0] = 0.61f // Battery
        probsRejected[1] = 0.15f

        val resultRejected = policy.evaluateProbabilities(probsRejected, canonical17Classes)
        assertEquals(AcceptanceState.LOW_CONFIDENCE, resultRejected.state)
        assertFalse(resultRejected.isAccepted)

        val probsAccepted = FloatArray(17) { 0.015f }
        probsAccepted[0] = 0.63f // Battery
        probsAccepted[1] = 0.13f

        val resultAccepted = policy.evaluateProbabilities(probsAccepted, canonical17Classes)
        assertEquals(AcceptanceState.ACCEPTED, resultAccepted.state)
        assertTrue(resultAccepted.isAccepted)
        assertEquals("Battery", resultAccepted.top1Class)
    }

    @Test
    fun `non-finite logits containing NaN or Infinity are handled safely as INVALID_OUTPUT`() {
        val nanLogits = FloatArray(17) { 0.1f }
        nanLogits[5] = Float.NaN

        val resultNan = policy.evaluate(nanLogits, canonical17Classes)
        assertEquals(AcceptanceState.INVALID_OUTPUT, resultNan.state)
        assertFalse(resultNan.isAccepted)

        val infLogits = FloatArray(17) { 0.1f }
        infLogits[3] = Float.POSITIVE_INFINITY

        val resultInf = policy.evaluate(infLogits, canonical17Classes)
        assertEquals(AcceptanceState.INVALID_OUTPUT, resultInf.state)
        assertFalse(resultInf.isAccepted)
    }

    @Test
    fun `empty logits returns INVALID_OUTPUT without throwing exception`() {
        val result = policy.evaluate(FloatArray(0), canonical17Classes)
        assertEquals(AcceptanceState.INVALID_OUTPUT, result.state)
        assertFalse(result.isAccepted)
    }
}
