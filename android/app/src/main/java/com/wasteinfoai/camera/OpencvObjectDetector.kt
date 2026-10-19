package com.wasteinfoai.camera

import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.ArrayList

/**
 * Result of OpenCV real-time object contour and bounding box analysis.
 */
data class DetectedObjectBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val hasTarget: Boolean = true,
    val confidence: Float = 0.85f,
    val label: String = "OBJECT DETECTED"
)

/**
 * Real-time object detector utilizing OpenCV computer vision algorithms:
 * - Y-plane luminance extraction
 * - Gaussian smoothing for noise reduction
 * - Canny edge detection / Otsu thresholding
 * - Morphological dilation for contour closure
 * - External contour analysis and salient bounding box estimation
 * - Rotation mapping to display space
 * - Temporal box smoothing for jitter-free tracking
 */
class OpencvObjectDetector {

    companion object {
        private const val TAG = "OpencvObjectDetector"
        private var isOpenCvAvailable: Boolean = false

        init {
            try {
                isOpenCvAvailable = OpenCVLoader.initLocal()
                Log.i(TAG, "OpenCV native library initialized: $isOpenCvAvailable")
            } catch (t: Throwable) {
                Log.w(TAG, "OpenCVLoader.initLocal() exception: ${t.message}. Using fallback CV pipeline.")
                isOpenCvAvailable = false
            }
        }
    }

    private var prevLeft = 0.15f
    private var prevTop = 0.25f
    private var prevRight = 0.85f
    private var prevBottom = 0.70f
    private val smoothingFactor = 0.35f // Responsive yet smooth tracking

    /**
     * Analyzes an incoming camera frame and returns the normalized bounding box [0.0f..1.0f]
     * of the detected object in display space.
     */
    fun analyzeFrame(imageProxy: ImageProxy): DetectedObjectBox {
        return try {
            if (isOpenCvAvailable) {
                detectWithOpenCv(imageProxy)
            } else {
                detectWithFallback(imageProxy)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Detection error: ${e.message}")
            fallbackBox()
        }
    }

    private fun detectWithOpenCv(imageProxy: ImageProxy): DetectedObjectBox {
        val yPlane = imageProxy.planes[0]
        val buffer = yPlane.buffer
        buffer.rewind()

        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = yPlane.rowStride

        // Allocate and populate raw grayscale Mat
        val rawBytes = ByteArray(rowStride * height)
        buffer.get(rawBytes)

        val fullMat = Mat(height, rowStride, CvType.CV_8UC1)
        fullMat.put(0, 0, rawBytes)
        val grayMat = if (rowStride > width) fullMat.submat(0, height, 0, width) else fullMat

        // Downsample for high frame-rate real-time processing (~2-3ms per frame)
        val targetWidth = 240
        val targetHeight = ((height.toDouble() / width.toDouble()) * targetWidth).toInt().coerceAtLeast(120)
        val smallMat = Mat()
        Imgproc.resize(grayMat, smallMat, Size(targetWidth.toDouble(), targetHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)

        // Gaussian blur to eliminate high-frequency camera sensor noise
        val blurredMat = Mat()
        Imgproc.GaussianBlur(smallMat, blurredMat, Size(5.0, 5.0), 1.5)

        // Canny edge detection to identify object boundaries
        val edgesMat = Mat()
        Imgproc.Canny(blurredMat, edgesMat, 40.0, 120.0)

        // Morphological dilation to bridge broken object contours
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val dilatedMat = Mat()
        Imgproc.dilate(edgesMat, dilatedMat, kernel)

        // Find external object contours
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(dilatedMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val totalArea = targetWidth.toDouble() * targetHeight.toDouble()
        val centerX = targetWidth / 2.0
        val centerY = targetHeight / 2.0

        var bestRect: org.opencv.core.Rect? = null
        var highestScore = 0.0

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            // Filter: Ignore tiny noise (< 2.5% of view) and full-screen edges (> 85% of view)
            if (area < totalArea * 0.025 || area > totalArea * 0.85) continue

            val r = Imgproc.boundingRect(contour)
            if (r.width < targetWidth * 0.12 || r.height < targetHeight * 0.12) continue

            // Distance to center penalty (user points camera at the target object)
            val rectCenterX = r.x + r.width / 2.0
            val rectCenterY = r.y + r.height / 2.0
            val dx = (rectCenterX - centerX) / targetWidth
            val dy = (rectCenterY - centerY) / targetHeight
            val distSq = dx * dx + dy * dy

            val saliencyScore = area / (1.0 + distSq * 3.0)
            if (saliencyScore > highestScore) {
                highestScore = saliencyScore
                bestRect = r
            }
        }

        // Clean up OpenCV native memory
        fullMat.release()
        if (rowStride > width) grayMat.release()
        smallMat.release()
        blurredMat.release()
        edgesMat.release()
        kernel.release()
        dilatedMat.release()
        hierarchy.release()
        contours.forEach { it.release() }

        return if (bestRect != null) {
            val sLeft = bestRect.x.toFloat() / targetWidth
            val sTop = bestRect.y.toFloat() / targetHeight
            val sRight = (bestRect.x + bestRect.width).toFloat() / targetWidth
            val sBottom = (bestRect.y + bestRect.height).toFloat() / targetHeight

            val (dispLeft, dispTop, dispRight, dispBottom) = mapToDisplay(
                sLeft, sTop, sRight, sBottom,
                imageProxy.imageInfo.rotationDegrees
            )
            applySmoothing(dispLeft, dispTop, dispRight, dispBottom, hasTarget = true)
        } else {
            // No prominent isolated object -> default to smooth center scanning reticle
            applySmoothing(0.18f, 0.22f, 0.82f, 0.68f, hasTarget = false)
        }
    }

    private fun detectWithFallback(imageProxy: ImageProxy): DetectedObjectBox {
        val yPlane = imageProxy.planes[0]
        val buffer = yPlane.buffer
        buffer.rewind()

        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = yPlane.rowStride

        // Fast downsampled gradient scanner on luminance plane
        val step = 8
        var minX = width
        var maxX = 0
        var minY = height
        var maxY = 0
        var salientCount = 0

        val rowBytes = ByteArray(width)
        val nextRowBytes = ByteArray(width)

        for (y in step until height - step step step) {
            buffer.position(y * rowStride)
            buffer.get(rowBytes)
            buffer.position((y + step) * rowStride)
            buffer.get(nextRowBytes)

            for (x in step until width - step step step) {
                val dx = Math.abs((rowBytes[x + step].toInt() and 0xFF) - (rowBytes[x - step].toInt() and 0xFF))
                val dy = Math.abs((nextRowBytes[x].toInt() and 0xFF) - (rowBytes[x].toInt() and 0xFF))
                val gradient = dx + dy
                if (gradient > 45) { // Edge threshold
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    salientCount++
                }
            }
        }

        return if (salientCount > 25 && maxX > minX && maxY > minY) {
            val sLeft = (minX.toFloat() / width).coerceIn(0.05f, 0.85f)
            val sTop = (minY.toFloat() / height).coerceIn(0.05f, 0.85f)
            val sRight = (maxX.toFloat() / width).coerceIn(sLeft + 0.15f, 0.95f)
            val sBottom = (maxY.toFloat() / height).coerceIn(sTop + 0.15f, 0.95f)

            val (dispLeft, dispTop, dispRight, dispBottom) = mapToDisplay(
                sLeft, sTop, sRight, sBottom,
                imageProxy.imageInfo.rotationDegrees
            )
            applySmoothing(dispLeft, dispTop, dispRight, dispBottom, hasTarget = true)
        } else {
            applySmoothing(0.18f, 0.22f, 0.82f, 0.68f, hasTarget = false)
        }
    }

    private fun mapToDisplay(
        sLeft: Float, sTop: Float, sRight: Float, sBottom: Float,
        rotationDegrees: Int
    ): FloatArray {
        return when (rotationDegrees) {
            90 -> {
                val dispLeft = sTop
                val dispTop = sLeft
                val dispRight = sBottom
                val dispBottom = sRight
                floatArrayOf(
                    minOf(dispLeft, dispRight).coerceIn(0.05f, 0.85f),
                    minOf(dispTop, dispBottom).coerceIn(0.08f, 0.85f),
                    maxOf(dispLeft, dispRight).coerceIn(0.15f, 0.95f),
                    maxOf(dispTop, dispBottom).coerceIn(0.18f, 0.92f)
                )
            }
            270 -> {
                val dispLeft = 1f - sBottom
                val dispTop = 1f - sRight
                val dispRight = 1f - sTop
                val dispBottom = 1f - sLeft
                floatArrayOf(
                    minOf(dispLeft, dispRight).coerceIn(0.05f, 0.85f),
                    minOf(dispTop, dispBottom).coerceIn(0.08f, 0.85f),
                    maxOf(dispLeft, dispRight).coerceIn(0.15f, 0.95f),
                    maxOf(dispTop, dispBottom).coerceIn(0.18f, 0.92f)
                )
            }
            180 -> {
                floatArrayOf(
                    (1f - sRight).coerceIn(0.05f, 0.85f),
                    (1f - sBottom).coerceIn(0.08f, 0.85f),
                    (1f - sLeft).coerceIn(0.15f, 0.95f),
                    (1f - sTop).coerceIn(0.18f, 0.92f)
                )
            }
            else -> {
                floatArrayOf(
                    sLeft.coerceIn(0.05f, 0.85f),
                    sTop.coerceIn(0.08f, 0.85f),
                    sRight.coerceIn(0.15f, 0.95f),
                    sBottom.coerceIn(0.18f, 0.92f)
                )
            }
        }
    }

    private fun applySmoothing(
        targetLeft: Float,
        targetTop: Float,
        targetRight: Float,
        targetBottom: Float,
        hasTarget: Boolean
    ): DetectedObjectBox {
        // Enforce minimum box dimensions (at least 18% width and 16% height)
        val validRight = maxOf(targetRight, targetLeft + 0.18f).coerceAtMost(0.96f)
        val validBottom = maxOf(targetBottom, targetTop + 0.16f).coerceAtMost(0.92f)

        prevLeft += smoothingFactor * (targetLeft - prevLeft)
        prevTop += smoothingFactor * (targetTop - prevTop)
        prevRight += smoothingFactor * (validRight - prevRight)
        prevBottom += smoothingFactor * (validBottom - prevBottom)

        return DetectedObjectBox(
            left = prevLeft,
            top = prevTop,
            right = prevRight,
            bottom = prevBottom,
            hasTarget = hasTarget,
            confidence = if (hasTarget) 0.92f else 0.50f,
            label = if (hasTarget) "OBJECT DETECTED" else "SCANNING OBJECT..."
        )
    }

    private fun fallbackBox(): DetectedObjectBox {
        return DetectedObjectBox(
            left = 0.18f,
            top = 0.22f,
            right = 0.82f,
            bottom = 0.68f,
            hasTarget = false,
            confidence = 0.5f,
            label = "SCANNING OBJECT..."
        )
    }
}
