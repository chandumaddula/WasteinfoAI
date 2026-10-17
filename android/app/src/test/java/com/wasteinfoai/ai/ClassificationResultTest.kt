package com.wasteinfoai.ai

import com.wasteinfoai.ai.vision.ClassScore
import com.wasteinfoai.ai.vision.ClassificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassificationResultTest {

    @Test
    fun `confident prediction retains valid label and properties`() {
        val topK = listOf(
            ClassScore(11, "plastic", 0.94f),
            ClassScore(1, "cardboard", 0.04f),
            ClassScore(4, "metal", 0.02f)
        )
        val result = ClassificationResult(
            classId = 11,
            label = "plastic",
            confidence = 0.94f,
            inferenceDurationMs = 38L,
            modelVersion = "1.0.0-rexnet150",
            isUncertain = false,
            topKResults = topK
        )

        assertFalse(result.isUncertain)
        assertEquals(11, result.classId)
        assertEquals("plastic", result.label)
        assertEquals(0.94f, result.confidence, 0.001f)
        assertEquals(38L, result.inferenceDurationMs)
        assertEquals(3, result.topKResults.size)
    }

    @Test
    fun `uncertain result flags isUncertain and retains explanation`() {
        val topK = listOf(
            ClassScore(15, "trash", 0.38f),
            ClassScore(8, "organic", 0.35f)
        )
        val uncertain = ClassificationResult.uncertain(
            reason = "Confidence score (38.0%) below acceptance threshold (45.0%)",
            durationMs = 42L,
            topK = topK
        )

        assertTrue(uncertain.isUncertain)
        assertEquals(-1, uncertain.classId)
        assertNotNull(uncertain.uncertaintyReason)
        assertEquals(0.0f, uncertain.confidence, 0.001f)
    }
}
