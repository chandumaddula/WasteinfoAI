package com.wasteinfoai.camera

import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Checks that a file is an image this app can use: supported format, sane dimensions, and actually
 * decodable within bounded memory. It does NOT assess sharpness, exposure or whether the picture
 * shows waste; no such analysis exists in this phase. A truncated JPEG that the platform decoder
 * still partially decodes is accepted.
 */
class AndroidImageValidator(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImageValidator {

    override suspend fun validate(file: File, source: ImageSourceType): ValidationResult =
        withContext(ioDispatcher) { validateBlocking(file, source) }

    private fun validateBlocking(file: File, source: ImageSourceType): ValidationResult {
        if (!file.isFile || file.length() == 0L) return invalid(ImageProblem.NOT_AN_IMAGE)

        val bounds = ImageDecoding.readBounds(file) ?: return invalid(ImageProblem.NOT_AN_IMAGE)
        if (bounds.mimeType.lowercase() !in supportedMimeTypes) return invalid(ImageProblem.UNSUPPORTED_FORMAT)
        if (bounds.width < MIN_DIMENSION_PX || bounds.height < MIN_DIMENSION_PX) {
            return invalid(ImageProblem.INVALID_DIMENSIONS)
        }
        if (bounds.width.toLong() * bounds.height.toLong() > MAX_PIXELS) return invalid(ImageProblem.TOO_LARGE)

        // Decode a subsampled copy to prove the pixel data is readable within a small, fixed budget.
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageSampling.sampleSize(bounds.width, bounds.height, PROBE_MAX_DIMENSION)
        }
        val probe = try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: OutOfMemoryError) {
            return invalid(ImageProblem.TOO_LARGE)
        } ?: return invalid(ImageProblem.NOT_AN_IMAGE)
        probe.recycle()

        return ValidationResult.Valid(
            ValidatedImage(
                file = file,
                mimeType = bounds.mimeType,
                widthPx = bounds.width,
                heightPx = bounds.height,
                orientation = ImageDecoding.readOrientation(file),
                source = source,
            ),
        )
    }

    private fun invalid(problem: ImageProblem) = ValidationResult.Invalid(problem)

    companion object {
        const val MIN_DIMENSION_PX = 32
        const val MAX_PIXELS = 200_000_000L
        private const val PROBE_MAX_DIMENSION = 1024

        private val supportedMimeTypes: Set<String> = buildSet {
            add("image/jpeg")
            add("image/png")
            add("image/webp")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add("image/heif")
                add("image/heic")
            }
        }
    }
}
