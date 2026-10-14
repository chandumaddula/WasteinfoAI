package com.wasteinfoai.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

data class ImageBounds(val width: Int, val height: Int, val mimeType: String)

/**
 * Memory-safe decoding helpers shared by validation, the review screen and (later) the classifier.
 * Never decodes more pixels than needed: bounds first, then a power-of-two subsampled decode.
 */
object ImageDecoding {
    const val PREVIEW_MAX_DIMENSION = 1600

    /** Reads size and MIME type without allocating pixels; null if the file is not a decodable image. */
    fun readBounds(file: File): ImageBounds? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val mime = options.outMimeType
        return if (options.outWidth > 0 && options.outHeight > 0 && mime != null) {
            ImageBounds(options.outWidth, options.outHeight, mime)
        } else {
            null
        }
    }

    /** EXIF orientation of [file]; unreadable or missing EXIF means "normal". */
    fun readOrientation(file: File): ImageOrientation = try {
        val tag = ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ImageOrientation.fromExifTag(tag)
    } catch (e: Exception) {
        ImageOrientation.NORMAL
    }

    /**
     * Decodes [file] so its longer edge is roughly [maxDimension] (never below it), with EXIF
     * rotation/mirroring applied. Returns null if it cannot be decoded or memory is insufficient.
     */
    fun decodeOriented(file: File, maxDimension: Int): Bitmap? {
        val bounds = readBounds(file) ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageSampling.sampleSize(bounds.width, bounds.height, maxDimension)
        }
        val decoded = try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null

        val orientation = readOrientation(file)
        if (orientation == ImageOrientation.NORMAL) return decoded

        val matrix = Matrix().apply {
            postRotate(orientation.rotationDegrees.toFloat())
            if (orientation.mirrored) postScale(-1f, 1f)
        }
        return try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } catch (e: OutOfMemoryError) {
            decoded.recycle()
            null
        }
    }
}
