package com.wasteinfoai.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State holder for capture / gallery selection / confirmation. Holds no Activity, View or Context.
 * All public functions are expected on the main thread; file work happens inside the injected
 * [ImageStore]/[ImageValidator] (which switch to a background dispatcher themselves).
 *
 * File ownership: every file created here is deleted when it is replaced, retaken, discarded, or the
 * ViewModel is cleared, EXCEPT after [confirm], when ownership transfers to the consumer.
 */
class CameraViewModel(
    private val store: ImageStore,
    private val validator: ImageValidator,
    scope: CoroutineScope? = null, // injectable for tests; defaults to viewModelScope
) : ViewModel() {

    private val scope: CoroutineScope = scope ?: viewModelScope
    private val _uiState = MutableStateFlow<CameraUiState>(CameraUiState.CameraReady)
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    /** The currently accepted, not-yet-confirmed image (main thread only). */
    private var selected: ValidatedImage? = null
    private var pendingCaptureFile: File? = null

    // ------------------------------------------------------------------ capture
    /**
     * Atomically moves CameraReady -> Capturing and returns the file CameraX should write to.
     * Returns null (and does nothing) if a capture/load is already in progress or an image is
     * already selected, which is what prevents overlapping captures from rapid taps.
     */
    fun beginCapture(): File? {
        if (!_uiState.compareAndSet(CameraUiState.CameraReady, CameraUiState.Capturing)) return null
        return try {
            store.createCaptureFile().also { pendingCaptureFile = it }
        } catch (e: IOException) {
            _uiState.value = CameraUiState.Failed(ImageProblem.CAPTURE_FAILED)
            null
        }
    }

    fun onCaptureSaved(file: File) {
        if (_uiState.value != CameraUiState.Capturing || file != pendingCaptureFile) {
            store.delete(file) // late or stray callback: never leak the file
            return
        }
        pendingCaptureFile = null
        _uiState.value = CameraUiState.LoadingImage
        scope.launch {
            when (val result = validator.validate(file, ImageSourceType.CAMERA)) {
                is ValidationResult.Valid -> accept(result.image)
                is ValidationResult.Invalid -> {
                    store.delete(file)
                    _uiState.value = CameraUiState.Failed(result.problem)
                }
            }
        }
    }

    fun onCaptureFailed(file: File?) {
        file?.let(store::delete)
        pendingCaptureFile = null
        if (_uiState.value == CameraUiState.Capturing) {
            _uiState.value = CameraUiState.Failed(ImageProblem.CAPTURE_FAILED)
        }
    }

    /** CameraX could not bind a camera (none present, in use, or provider failure). */
    fun onCameraUnavailable() {
        if (_uiState.value == CameraUiState.CameraReady) {
            _uiState.value = CameraUiState.Failed(ImageProblem.CAMERA_UNAVAILABLE)
        }
    }

    // ------------------------------------------------------------------ gallery
    /**
     * [uri] is the string form of the picker's content URI; null means the user cancelled, which
     * changes nothing. Ignored while a capture or another import is running.
     */
    fun onImagePicked(uri: String?) {
        if (uri == null) return
        val current = _uiState.value
        if (current == CameraUiState.Capturing || current == CameraUiState.LoadingImage) return
        _uiState.value = CameraUiState.LoadingImage
        scope.launch {
            val file = try {
                store.importFromUri(uri)
            } catch (e: ImageImportException) {
                _uiState.value = CameraUiState.Failed(e.problem)
                return@launch
            }
            when (val result = validator.validate(file, ImageSourceType.GALLERY)) {
                is ValidationResult.Valid -> accept(result.image)
                is ValidationResult.Invalid -> {
                    store.delete(file)
                    _uiState.value = CameraUiState.Failed(result.problem)
                }
            }
        }
    }

    // ------------------------------------------------------------------ review actions
    /** Back to the live camera, discarding the current selection. */
    fun retake() {
        val state = _uiState.value
        if (state !is CameraUiState.ImageSelected && state !is CameraUiState.Failed) return
        discardSelected()
        _uiState.value = CameraUiState.CameraReady
    }

    /** Leaves an error: back to the previous selection if there is one, otherwise the camera. */
    fun dismissError() {
        if (_uiState.value !is CameraUiState.Failed) return
        _uiState.value = selected?.let { CameraUiState.ImageSelected(it) } ?: CameraUiState.CameraReady
    }

    /**
     * Confirms the selected image. Returns it exactly once; returns null (state unchanged) when there
     * is no valid selected image, so confirmation can never happen without a validated image.
     */
    fun confirm(): ValidatedImage? {
        val state = _uiState.value as? CameraUiState.ImageSelected ?: return null
        selected = null // ownership of the file moves to the caller
        _uiState.value = CameraUiState.Confirmed(state.image)
        return state.image
    }

    /** After a confirmed image has been handed off, return to a fresh camera state. */
    fun startOver() {
        if (_uiState.value is CameraUiState.Confirmed) _uiState.value = CameraUiState.CameraReady
    }

    /** Deletes files this ViewModel still owns (never a confirmed file). Called from [onCleared]. */
    fun discardUnconfirmed() {
        pendingCaptureFile?.let(store::delete)
        pendingCaptureFile = null
        discardSelected()
    }

    override fun onCleared() {
        discardUnconfirmed()
    }

    // ------------------------------------------------------------------ internals
    private fun accept(image: ValidatedImage) {
        val previous = selected
        selected = image
        if (previous != null && previous.file != image.file) store.delete(previous.file)
        _uiState.value = CameraUiState.ImageSelected(image)
    }

    private fun discardSelected() {
        selected?.let { store.delete(it.file) }
        selected = null
    }
}
