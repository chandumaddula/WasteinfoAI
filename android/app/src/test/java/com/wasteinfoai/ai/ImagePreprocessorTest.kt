package com.wasteinfoai.ai

import com.wasteinfoai.ai.vision.ImagePreprocessor
import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePreprocessorTest {

    @Test
    fun verifyTargetDimensionsAndSize() {
        assertEquals(256, ImagePreprocessor.INPUT_WIDTH)
        assertEquals(256, ImagePreprocessor.INPUT_HEIGHT)
        assertEquals(3, ImagePreprocessor.NUM_CHANNELS)
        assertEquals(256 * 256 * 3, ImagePreprocessor.TENSOR_SIZE)
    }

    @Test
    fun verifyFloatArrayNormalizationAndPlanarLayout() {
        // Create 256x256 pixels filled with pure red (R=255, G=0, B=0, A=255)
        val pixels = IntArray(256 * 256) { 0xFFFF0000.toInt() }

        val floatBuffer = ImagePreprocessor.normalizePixels(pixels)
        assertEquals(ImagePreprocessor.TENSOR_SIZE, floatBuffer.size)

        val planeSize = 256 * 256

        // R = 1.0 -> (1.0 - 0.485) / 0.229 = 0.515 / 0.229 ~= 2.2489
        val expectedR = (1.0f - 0.485f) / 0.229f
        assertEquals(expectedR, floatBuffer[0], 0.01f)

        // G = 0.0 -> (0.0 - 0.456) / 0.224 ~= -2.0357
        val expectedG = (0.0f - 0.456f) / 0.224f
        assertEquals(expectedG, floatBuffer[planeSize], 0.01f)

        // B = 0.0 -> (0.0 - 0.406) / 0.225 ~= -1.8044
        val expectedB = (0.0f - 0.406f) / 0.225f
        assertEquals(expectedB, floatBuffer[2 * planeSize], 0.01f)
    }
}
