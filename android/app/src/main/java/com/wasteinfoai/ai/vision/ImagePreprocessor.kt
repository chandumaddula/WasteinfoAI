package com.wasteinfoai.ai.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

/**
 * Preprocessing strategies for image preparation.
 */
enum class PreprocessingStrategy {
    CENTER_CROP,
    LETTERBOX,
    ROI_CROP
}

/**
 * Preprocessor matching ReXNet-150 / timm configuration:
 * - Input dimensions: 224 x 224
 * - Channel layout: NCHW, RGB
 * - ImageNet normalization: Mean = [0.485, 0.456, 0.406], Std = [0.229, 0.224, 0.225]
 *
 * Supports multiple validated strategies:
 * 1. CENTER_CROP: Legacy baseline cropping the central square.
 * 2. LETTERBOX: Aspect-ratio-preserving downscale with neutral gray padding, preventing cutoff.
 * 3. ROI_CROP: Crops a detected object's bounding box with contextual margin, then letterboxes.
 */
object ImagePreprocessor {

    const val INPUT_WIDTH = 224
    const val INPUT_HEIGHT = 224
    const val NUM_CHANNELS = 3
    const val TENSOR_SIZE = INPUT_WIDTH * INPUT_HEIGHT * NUM_CHANNELS

    // Neutral gray fill value for letterboxing: RGB (114, 114, 114)
    const val NEUTRAL_GRAY_PAD = 0xFF727272.toInt()

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * Decodes, corrects orientation, and prepares the image from [file] to 224x224 using [strategy].
     */
    fun loadAndPreprocessBitmap(
        file: File,
        strategy: PreprocessingStrategy = PreprocessingStrategy.LETTERBOX,
        roi: RectF? = null
    ): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val origW = options.outWidth
        val origH = options.outHeight

        // Sample size calculation to prevent large bitmap allocations
        val minSide = minOf(origW, origH)
        var sampleSize = 1
        while (minSide / (sampleSize * 2) >= INPUT_WIDTH) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sampled = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            ?: throw IllegalArgumentException("Could not decode bitmap from ${file.path}")

        // Correct EXIF orientation
        val oriented = correctExifOrientation(file.absolutePath, sampled)

        return preprocessBitmap(oriented, strategy, roi)
    }

    /**
     * Preprocesses an in-memory [source] bitmap to exact 224x224 using [strategy].
     */
    fun preprocessBitmap(
        source: Bitmap,
        strategy: PreprocessingStrategy = PreprocessingStrategy.LETTERBOX,
        roi: RectF? = null
    ): Bitmap {
        return when (strategy) {
            PreprocessingStrategy.CENTER_CROP -> {
                scaleAndCenterCrop(source, INPUT_WIDTH, INPUT_HEIGHT)
            }
            PreprocessingStrategy.LETTERBOX -> {
                letterbox(source, INPUT_WIDTH, INPUT_HEIGHT, NEUTRAL_GRAY_PAD)
            }
            PreprocessingStrategy.ROI_CROP -> {
                if (roi != null && roi.width() > 0.05f && roi.height() > 0.05f) {
                    val croppedRoi = cropRoi(source, roi, marginRatio = 0.12f)
                    val result = letterbox(croppedRoi, INPUT_WIDTH, INPUT_HEIGHT, NEUTRAL_GRAY_PAD)
                    if (croppedRoi !== source && croppedRoi !== result) {
                        croppedRoi.recycle()
                    }
                    result
                } else {
                    // Fall back to letterboxing entire image if ROI is unavailable or invalid
                    letterbox(source, INPUT_WIDTH, INPUT_HEIGHT, NEUTRAL_GRAY_PAD)
                }
            }
        }
    }

    /**
     * Resizes [source] preserving aspect ratio and pads with [padColor] into [targetW] x [targetH].
     */
    fun letterbox(source: Bitmap, targetW: Int, targetH: Int, padColor: Int = NEUTRAL_GRAY_PAD): Bitmap {
        val srcW = source.width
        val srcH = source.height

        if (srcW == targetW && srcH == targetH) {
            return source
        }

        val scale = minOf(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
        val scaledW = (srcW * scale).toInt().coerceAtLeast(1)
        val scaledH = (srcH * scale).toInt().coerceAtLeast(1)

        val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)

        val output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(padColor)

        val dx = (targetW - scaledW) / 2f
        val dy = (targetH - scaledH) / 2f
        canvas.drawBitmap(scaled, dx, dy, null)

        if (scaled !== source) {
            scaled.recycle()
        }

        return output
    }

    /**
     * Crops the region defined by normalized coordinates [roi] (0.0f..1.0f) with an optional [marginRatio].
     */
    fun cropRoi(source: Bitmap, roi: RectF, marginRatio: Float = 0.10f): Bitmap {
        val srcW = source.width
        val srcH = source.height

        val marginX = roi.width() * marginRatio
        val marginY = roi.height() * marginRatio

        val leftNorm = (roi.left - marginX).coerceIn(0.0f, 1.0f)
        val topNorm = (roi.top - marginY).coerceIn(0.0f, 1.0f)
        val rightNorm = (roi.right + marginX).coerceIn(0.0f, 1.0f)
        val bottomNorm = (roi.bottom + marginY).coerceIn(0.0f, 1.0f)

        val x = (leftNorm * srcW).toInt().coerceIn(0, srcW - 1)
        val y = (topNorm * srcH).toInt().coerceIn(0, srcH - 1)
        val w = ((rightNorm - leftNorm) * srcW).toInt().coerceIn(1, srcW - x)
        val h = ((bottomNorm - topNorm) * srcH).toInt().coerceIn(1, srcH - y)

        return Bitmap.createBitmap(source, x, y, w, h)
    }

    private fun correctExifOrientation(filePath: String, bitmap: Bitmap): Bitmap {
        val exif = runCatching { ExifInterface(filePath) }.getOrNull() ?: return bitmap
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    fun scaleAndCenterCrop(source: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val srcW = source.width
        val srcH = source.height

        if (srcW == targetW && srcH == targetH) {
            return source
        }

        val scale = max(targetW.toDouble() / srcW, targetH.toDouble() / srcH)
        val scaledW = max(targetW, kotlin.math.ceil(scale * srcW).toInt())
        val scaledH = max(targetH, kotlin.math.ceil(scale * srcH).toInt())

        val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)

        val maxCropX = max(0, scaled.width - targetW)
        val maxCropY = max(0, scaled.height - targetH)
        val cropX = ((scaled.width - targetW) / 2).coerceIn(0, maxCropX)
        val cropY = ((scaled.height - targetH) / 2).coerceIn(0, maxCropY)

        val safeW = minOf(targetW, scaled.width - cropX)
        val safeH = minOf(targetH, scaled.height - cropY)

        val cropped = Bitmap.createBitmap(scaled, cropX, cropY, safeW, safeH)
        if (scaled !== source && scaled !== cropped) {
            scaled.recycle()
        }
        if (source !== cropped) {
            source.recycle()
        }

        return if (cropped.width == targetW && cropped.height == targetH) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, targetW, targetH, true).also {
                if (it !== cropped) cropped.recycle()
            }
        }
    }

    /**
     * Converts a 224x224 ARGB_8888 [bitmap] to normalized NCHW FloatArray.
     */
    fun bitmapToNormalizedFloatArray(bitmap: Bitmap): FloatArray {
        require(bitmap.width == INPUT_WIDTH && bitmap.height == INPUT_HEIGHT) {
            "Bitmap must be ${INPUT_WIDTH}x${INPUT_HEIGHT}, got ${bitmap.width}x${bitmap.height}"
        }

        val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
        bitmap.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)
        return normalizePixels(pixels)
    }

    /**
     * Converts ARGB_8888 pixel values into ImageNet-normalized NCHW FloatArray.
     */
    fun normalizePixels(pixels: IntArray): FloatArray {
        require(pixels.size == INPUT_WIDTH * INPUT_HEIGHT) {
            "Pixel array must be ${INPUT_WIDTH * INPUT_HEIGHT}, got ${pixels.size}"
        }

        val floatBuffer = FloatArray(TENSOR_SIZE)
        val planeSize = INPUT_WIDTH * INPUT_HEIGHT

        for (i in pixels.indices) {
            val pixel = pixels[i]
            // Extract RGB (0..255)
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            // Normalize and place in planar NCHW order
            floatBuffer[i] = (r - MEAN[0]) / STD[0]
            floatBuffer[planeSize + i] = (g - MEAN[1]) / STD[1]
            floatBuffer[2 * planeSize + i] = (b - MEAN[2]) / STD[2]
        }

        return floatBuffer
    }
}
