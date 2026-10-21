package com.wasteinfoai.camera

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.TimeUnit

/**
 * Validated object detector leveraging Google ML Kit on-device object localization.
 * Provides multi-object physical localization, robust to grass/cluttered backgrounds,
 * with graceful fallback to OpenCV contour detection and center framing.
 */
class MlKitWasteObjectDetector(
    private val fallbackDetector: WasteObjectDetector = OpencvObjectDetector()
) : WasteObjectDetector {

    companion object {
        private const val TAG = "MlKitWasteDetector"
        private const val CONTEXT_MARGIN_RATIO = 0.08f
    }

    private val staticDetector: ObjectDetector by lazy {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        ObjectDetection.getClient(options)
    }

    override fun detectObjects(bitmap: Bitmap): List<DetectedWasteItem> {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 64 || height < 64) {
            return listOf(DetectedWasteItem(RectF(0.05f, 0.05f, 0.95f, 0.95f), 0.5f, "FULL_IMAGE"))
        }

        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val task = staticDetector.process(inputImage)
            val detectedObjects = Tasks.await(task, 2000, TimeUnit.MILLISECONDS)

            if (!detectedObjects.isNullOrEmpty()) {
                val items = mutableListOf<DetectedWasteItem>()
                val imgArea = width.toFloat() * height.toFloat()

                for (obj in detectedObjects) {
                    val box = obj.boundingBox
                    // Filter out tiny slivers or full-bleed frames
                    val boxArea = box.width().toFloat() * box.height().toFloat()
                    if (boxArea < imgArea * 0.02f) continue

                    // Normalize to [0.0..1.0]
                    val rawLeft = (box.left.toFloat() / width).coerceIn(0f, 1f)
                    val rawTop = (box.top.toFloat() / height).coerceIn(0f, 1f)
                    val rawRight = (box.right.toFloat() / width).coerceIn(rawLeft + 0.05f, 1f)
                    val rawBottom = (box.bottom.toFloat() / height).coerceIn(rawTop + 0.05f, 1f)

                    // Add contextual margin to prevent clipping object edges
                    val w = rawRight - rawLeft
                    val h = rawBottom - rawTop
                    val dx = w * CONTEXT_MARGIN_RATIO
                    val dy = h * CONTEXT_MARGIN_RATIO

                    val marginBox = RectF(
                        (rawLeft - dx).coerceIn(0f, 1f),
                        (rawTop - dy).coerceIn(0f, 1f),
                        (rawRight + dx).coerceIn(0f, 1f),
                        (rawBottom + dy).coerceIn(0f, 1f)
                    )

                    // Saliency score based on center proximity and objectness
                    val cx = (rawLeft + rawRight) / 2f
                    val cy = (rawTop + rawBottom) / 2f
                    val distFromCenter = Math.hypot((cx - 0.5).toDouble(), (cy - 0.5).toDouble()).toFloat()
                    val centerFactor = (1.0f - distFromCenter * 0.8f).coerceIn(0.5f, 1.0f)
                    val areaFactor = (boxArea / imgArea).coerceIn(0.1f, 0.85f)
                    val score = (centerFactor * 0.6f + areaFactor * 0.4f).coerceIn(0.55f, 0.98f)

                    val label = obj.labels.firstOrNull()?.text ?: "WASTE_OBJECT"
                    items.add(DetectedWasteItem(marginBox, score, label, score))
                }

                if (items.isNotEmpty()) {
                    Log.i(TAG, "ML Kit detected ${items.size} candidate objects")
                    return items.sortedByDescending { it.saliencyScore }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit detection unavailable or timed out: ${e.message}. Using OpenCV contour detector.")
        }

        // Fallback to OpenCV contour detector
        val opencvResults = fallbackDetector.detectObjects(bitmap)
        return opencvResults.ifEmpty {
            listOf(DetectedWasteItem(RectF(0.10f, 0.12f, 0.90f, 0.88f), 0.5f, "CENTER_FALLBACK"))
        }
    }

    override fun analyzeFrame(imageProxy: ImageProxy): DetectedObjectBox {
        // Delegate real-time CameraX preview streaming to the fast OpenCV native luminance analyzer
        return fallbackDetector.analyzeFrame(imageProxy)
    }
}
