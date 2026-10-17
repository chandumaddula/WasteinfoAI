package com.wasteinfoai.ai.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

/**
 * Preprocessor matching ReXNet-150 / timm configuration:
 * - Input dimensions: 224 x 224
 * - Channel layout: NCHW, RGB
 * - ImageNet normalization: Mean = [0.485, 0.456, 0.406], Std = [0.229, 0.224, 0.225]
 */
object ImagePreprocessor {

    const val INPUT_WIDTH = 224
    const val INPUT_HEIGHT = 224
    const val NUM_CHANNELS = 3
    const val TENSOR_SIZE = INPUT_WIDTH * INPUT_HEIGHT * NUM_CHANNELS

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * Decodes, corrects orientation, center crops, and scales the image from [file] to 224x224.
     */
    fun loadAndPreprocessBitmap(file: File): Bitmap {
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

        // Center crop to square and scale to 224x224
        return scaleAndCenterCrop(oriented, INPUT_WIDTH, INPUT_HEIGHT)
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
        val scale = max(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
        val scaledW = (scale * srcW).toInt()
        val scaledH = (scale * srcH).toInt()

        val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)
        val cropX = max(0, (scaledW - targetW) / 2)
        val cropY = max(0, (scaledH - targetH) / 2)

        val cropped = Bitmap.createBitmap(scaled, cropX, cropY, targetW, targetH)
        if (scaled != source && scaled != cropped) {
            scaled.recycle()
        }
        if (source != cropped) {
            source.recycle()
        }
        return cropped
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
