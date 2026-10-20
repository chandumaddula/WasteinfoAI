package com.wasteinfoai.camera

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.camera.core.ImageProxy

/**
 * A detected waste item candidate with bounding box in normalized [0..1] coordinates.
 */
data class DetectedWasteItem(
    val boundingBox: RectF,
    val saliencyScore: Float,
    val label: String = "WASTE_OBJECT",
    val confidence: Float = saliencyScore
)

/**
 * General contract for on-device waste object localization.
 */
interface WasteObjectDetector {
    /**
     * Detects prominent waste objects in a static [bitmap].
     */
    fun detectObjects(bitmap: Bitmap): List<DetectedWasteItem>

    /**
     * Processes live CameraX preview frames.
     */
    fun analyzeFrame(imageProxy: ImageProxy): DetectedObjectBox
}
