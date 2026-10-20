package com.wasteinfoai.ai

import com.wasteinfoai.ai.vision.ImageQualityAssessor
import com.wasteinfoai.ai.vision.QualityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageQualityAssessorTest {

    @Test
    fun testLowResolutionImageRejection() {
        val smallPixels = IntArray(64 * 64) { 0xFF888888.toInt() }
        val result = ImageQualityAssessor.assessPixels(smallPixels, 64, 64)

        assertEquals(QualityStatus.LOW_RESOLUTION, result.status)
        assertFalse("Low resolution images must not proceed to inference", result.canProceed)
    }

    @Test
    fun testTooDarkImageDetection() {
        // Pixel luminance near 10 (< 32.0 threshold)
        val darkPixels = IntArray(160 * 160) { 0xFF0A0A0A.toInt() }
        val result = ImageQualityAssessor.assessPixels(darkPixels, 160, 160)

        assertEquals(QualityStatus.TOO_DARK, result.status)
        assertTrue("Dark images can still proceed with user warning", result.canProceed)
        assertTrue(result.meanBrightness < ImageQualityAssessor.TOO_DARK_THRESHOLD)
    }

    @Test
    fun testOverexposedImageDetection() {
        // Pure white washed-out pixels (luminance 255 > 230.0 threshold)
        val brightPixels = IntArray(160 * 160) { 0xFFFFFFFF.toInt() }
        val result = ImageQualityAssessor.assessPixels(brightPixels, 160, 160)

        assertEquals(QualityStatus.OVEREXPOSED, result.status)
        assertTrue("Overexposed images can proceed with warning", result.canProceed)
        assertTrue(result.meanBrightness > ImageQualityAssessor.OVEREXPOSED_THRESHOLD)
    }

    @Test
    fun testBlurWarningOnUniformFlatImage() {
        // Uniform gray image (luminance = 128, Laplacian variance = 0 < 38.0 threshold)
        val flatPixels = IntArray(160 * 160) { 0xFF808080.toInt() }
        val result = ImageQualityAssessor.assessPixels(flatPixels, 160, 160)

        assertEquals(QualityStatus.BLUR_WARNING, result.status)
        assertTrue(result.canProceed)
        assertEquals(0f, result.blurScore, 0.001f)
    }

    @Test
    fun testSharpHighContrastPatternIsAcceptable() {
        // Checkerboard high-frequency pattern simulating high edge detail
        val width = 160
        val height = 160
        val sharpPixels = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            if ((x / 4 + y / 4) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val result = ImageQualityAssessor.assessPixels(sharpPixels, width, height)

        assertEquals(QualityStatus.ACCEPTABLE, result.status)
        assertTrue(result.canProceed)
        assertTrue(result.blurScore > ImageQualityAssessor.BLUR_THRESHOLD)
    }
}
