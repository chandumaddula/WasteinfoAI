package com.wasteinfoai.camera

import kotlin.math.max

/**
 * EXIF orientation expressed as a rotation (clockwise degrees) followed by an optional horizontal
 * mirror. Pure Kotlin so it can be unit-tested without Android.
 */
data class ImageOrientation(val rotationDegrees: Int, val mirrored: Boolean) {
    /** True when the displayed width/height are swapped relative to the stored pixels. */
    val swapsAxes: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270

    companion object {
        val NORMAL = ImageOrientation(0, false)

        /** Maps an EXIF Orientation tag value (1..8). Unknown or missing values are treated as normal. */
        fun fromExifTag(tag: Int): ImageOrientation = when (tag) {
            2 -> ImageOrientation(0, true)      // flip horizontal
            3 -> ImageOrientation(180, false)
            4 -> ImageOrientation(180, true)    // flip vertical
            5 -> ImageOrientation(90, true)     // transpose
            6 -> ImageOrientation(90, false)
            7 -> ImageOrientation(270, true)    // transverse
            8 -> ImageOrientation(270, false)
            else -> NORMAL
        }
    }
}

object ImageSampling {
    /**
     * Largest power-of-two `inSampleSize` that still leaves the longer edge at least [maxDimension]
     * pixels. Lets large photos be decoded at a bounded memory cost.
     */
    fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        require(width > 0 && height > 0 && maxDimension > 0) { "Dimensions must be positive" }
        val longest = max(width, height)
        var sample = 1
        while (longest / (sample * 2) >= maxDimension) sample *= 2
        return sample
    }
}
