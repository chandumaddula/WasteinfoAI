package com.wasteinfoai.camera

import java.io.File

enum class ImageSourceType { CAMERA, GALLERY }

/** Why an image could not be used. The UI maps each value to a user-facing string. */
enum class ImageProblem {
    CAPTURE_FAILED,
    CAMERA_UNAVAILABLE,
    IMPORT_FAILED,
    NOT_AN_IMAGE,
    UNSUPPORTED_FORMAT,
    INVALID_DIMENSIONS,
    TOO_LARGE,
}

/**
 * An image that was opened, decoded and checked (format, dimensions, memory). It does NOT imply
 * anything about sharpness or content: no such analysis exists in this phase.
 *
 * [widthPx]/[heightPx] are the stored pixel dimensions; apply [orientation] when displaying or
 * decoding (see [displayWidthPx]/[displayHeightPx]).
 */
data class ValidatedImage(
    val file: File,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val orientation: ImageOrientation,
    val source: ImageSourceType,
) {
    val displayWidthPx: Int get() = if (orientation.swapsAxes) heightPx else widthPx
    val displayHeightPx: Int get() = if (orientation.swapsAxes) widthPx else heightPx
}

sealed interface CameraUiState {
    /** Camera permission granted (or not yet needed by the VM) and ready to capture. */
    object CameraReady : CameraUiState
    object Capturing : CameraUiState
    /** Copying / validating a captured or picked image off the main thread. */
    object LoadingImage : CameraUiState
    data class ImageSelected(val image: ValidatedImage) : CameraUiState
    data class Failed(val problem: ImageProblem) : CameraUiState
    /** The image was handed to the consumer; the consumer now owns (and must delete) the file. */
    data class Confirmed(val image: ValidatedImage) : CameraUiState
}

sealed interface ValidationResult {
    data class Valid(val image: ValidatedImage) : ValidationResult
    data class Invalid(val problem: ImageProblem) : ValidationResult
}

class ImageImportException(val problem: ImageProblem, cause: Throwable? = null) :
    Exception("Image import failed: $problem", cause)

/** File access for the capture flow. Implemented on Android by AppImageStore; faked in tests. */
interface ImageStore {
    /** A new, unique, app-private file for CameraX to write a JPEG into. */
    fun createCaptureFile(): File

    /** Copies the picked image into app-private storage. Throws [ImageImportException]. */
    suspend fun importFromUri(uri: String): File

    fun delete(file: File)
}

interface ImageValidator {
    suspend fun validate(file: File, source: ImageSourceType): ValidationResult
}

/**
 * Hand-off point for the future classification pipeline (Phase 4). Called once per confirmed image;
 * ownership of [ValidatedImage.file] transfers to the implementer, who must delete it when done.
 */
fun interface ConfirmedImageHandler {
    fun onImageConfirmed(image: ValidatedImage)
}
