package com.wasteinfoai.ai.vision

import android.graphics.Bitmap
import java.io.File

enum class QualityStatus {
    ACCEPTABLE,
    BLUR_WARNING,
    TOO_DARK,
    OVEREXPOSED,
    LOW_RESOLUTION,
    INVALID_IMAGE
}

data class ImageQualityAssessment(
    val status: QualityStatus,
    val blurScore: Float,
    val meanBrightness: Float,
    val message: String,
    val canProceed: Boolean
)

/**
 * Evaluates image quality prior to neural network inference to catch blur, extreme lighting,
 * and low-resolution inputs before feeding them to the classifier.
 */
object ImageQualityAssessor {

    const val MIN_DIMENSION = 128
    const val BLUR_THRESHOLD = 38.0f // Variance of Laplacian threshold on downsampled grayscale
    const val TOO_DARK_THRESHOLD = 32.0f // Average luminance [0..255]
    const val OVEREXPOSED_THRESHOLD = 230.0f // Average luminance [0..255]

    /**
     * Assesses an image file from disk.
     */
    fun assessFile(file: File): ImageQualityAssessment {
        if (!file.exists() || file.length() == 0L) {
            return ImageQualityAssessment(
                status = QualityStatus.INVALID_IMAGE,
                blurScore = 0f,
                meanBrightness = 0f,
                message = "Image file not found or empty.",
                canProceed = false
            )
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        // Judge resolution on the ORIGINAL photo, never on the thumbnail used for the sharpness test.
        if (bounds.outWidth > 0 && bounds.outHeight > 0 &&
            (bounds.outWidth < MIN_DIMENSION || bounds.outHeight < MIN_DIMENSION)
        ) {
            return ImageQualityAssessment(
                status = QualityStatus.LOW_RESOLUTION,
                blurScore = 0f,
                meanBrightness = 0f,
                message = "Image resolution (${bounds.outWidth}x${bounds.outHeight}) is too low for accurate identification. Minimum is ${MIN_DIMENSION}x${MIN_DIMENSION}.",
                canProceed = false
            )
        }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 480) sample *= 2
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        return try {
            assess(bmp)
        } finally {
            bmp?.recycle()
        }
    }

    /**
     * Assesses a loaded [bitmap] for optical suitability.
     */
    fun assess(bitmap: Bitmap?): ImageQualityAssessment {
        if (bitmap == null || bitmap.isRecycled) {
            return ImageQualityAssessment(
                status = QualityStatus.INVALID_IMAGE,
                blurScore = 0f,
                meanBrightness = 0f,
                message = "Could not decode image data. Please choose or capture another image.",
                canProceed = false
            )
        }

        if (bitmap.width < MIN_DIMENSION || bitmap.height < MIN_DIMENSION) {
            return ImageQualityAssessment(
                status = QualityStatus.LOW_RESOLUTION,
                blurScore = 0f,
                meanBrightness = 0f,
                message = "Image resolution (${bitmap.width}x${bitmap.height}) is too low for accurate identification. Minimum is ${MIN_DIMENSION}x${MIN_DIMENSION}.",
                canProceed = false
            )
        }

        // Subsample for fast, lightweight quality assessment (~3-5ms)
        val sampleW = 160
        val sampleH = ((bitmap.height.toFloat() / bitmap.width) * sampleW).toInt().coerceAtLeast(100)
        val small = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, true)

        val pixels = IntArray(sampleW * sampleH)
        small.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
        if (small !== bitmap) small.recycle()

        // The sampled thumbnail is intentionally small (e.g. 160x120): its size says nothing about the photo.
        return assessPixels(pixels, sampleW, sampleH, checkResolution = false)
    }

    /**
     * Algorithmic evaluation of raw pixel arrays for luminance, sharpness, and resolution.
     * Can run in pure JVM unit tests without Android OS graphics binaries.
     */
    fun assessPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        checkResolution: Boolean = true
    ): ImageQualityAssessment {
        if (checkResolution && (width < MIN_DIMENSION || height < MIN_DIMENSION)) {
            return ImageQualityAssessment(
                status = QualityStatus.LOW_RESOLUTION,
                blurScore = 0f,
                meanBrightness = 0f,
                message = "Image resolution (${width}x${height}) is too low for accurate identification. Minimum is ${MIN_DIMENSION}x${MIN_DIMENSION}.",
                canProceed = false
            )
        }

        // 1. Calculate Mean Luminance
        var totalLuminance = 0.0
        val gray = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            gray[i] = lum
            totalLuminance += lum
        }
        val meanBrightness = (totalLuminance / pixels.size).toFloat()

        if (meanBrightness < TOO_DARK_THRESHOLD) {
            return ImageQualityAssessment(
                status = QualityStatus.TOO_DARK,
                blurScore = 0f,
                meanBrightness = meanBrightness,
                message = "The image is too dark to clearly distinguish materials. Please use flash or improve ambient lighting.",
                canProceed = true
            )
        }

        if (meanBrightness > OVEREXPOSED_THRESHOLD) {
            return ImageQualityAssessment(
                status = QualityStatus.OVEREXPOSED,
                blurScore = 0f,
                meanBrightness = meanBrightness,
                message = "The image is overexposed or has strong glare washed out. Please adjust angle or shade the item.",
                canProceed = true
            )
        }

        // 2. Laplacian Variance (Sharpness / Focus metric)
        // Standard discrete 3x3 Laplacian kernel:
        // [ 0,  1,  0 ]
        // [ 1, -4,  1 ]
        // [ 0,  1,  0 ]
        var lapSum = 0.0
        var lapSqSum = 0.0
        var count = 0

        for (y in 1 until height - 1) {
            val rowOffset = y * width
            for (x in 1 until width - 1) {
                val center = gray[rowOffset + x]
                val top = gray[(y - 1) * width + x]
                val bottom = gray[(y + 1) * width + x]
                val left = gray[rowOffset + (x - 1)]
                val right = gray[rowOffset + (x + 1)]

                val lapVal = top + bottom + left + right - 4f * center
                lapSum += lapVal
                lapSqSum += (lapVal * lapVal)
                count++
            }
        }

        val lapMean = if (count > 0) lapSum / count else 0.0
        val lapVariance = if (count > 0) (lapSqSum / count - lapMean * lapMean).coerceAtLeast(0.0).toFloat() else 0f

        if (lapVariance < BLUR_THRESHOLD) {
            return ImageQualityAssessment(
                status = QualityStatus.BLUR_WARNING,
                blurScore = lapVariance,
                meanBrightness = meanBrightness,
                message = "The image appears blurry or out of focus. For highest accuracy, hold steady and tap to focus.",
                canProceed = true
            )
        }

        return ImageQualityAssessment(
            status = QualityStatus.ACCEPTABLE,
            blurScore = lapVariance,
            meanBrightness = meanBrightness,
            message = "Image quality is optimal.",
            canProceed = true
        )
    }
}
