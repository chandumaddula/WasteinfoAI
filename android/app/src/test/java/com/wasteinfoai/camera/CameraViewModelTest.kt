package com.wasteinfoai.camera

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeImageStore : ImageStore {
    private var counter = 0
    val deleted = mutableListOf<File>()
    var created = 0
    val importCalls = mutableListOf<String>()
    var createFails = false
    var importResult: (String) -> File = { File("/fake/imported_${counter++}.img") }

    override fun createCaptureFile(): File {
        if (createFails) throw java.io.IOException("disk full")
        created++
        return File("/fake/capture_${counter++}.jpg")
    }

    override suspend fun importFromUri(uri: String): File {
        importCalls += uri
        return importResult(uri)
    }

    override fun delete(file: File) {
        deleted += file
    }
}

private class FakeValidator : ImageValidator {
    var results: (File, ImageSourceType) -> ValidationResult = { f, s -> ValidationResult.Valid(image(f, s)) }
    val validated = mutableListOf<File>()

    override suspend fun validate(file: File, source: ImageSourceType): ValidationResult {
        validated += file
        return results(file, source)
    }
}

private fun image(file: File, source: ImageSourceType) = ValidatedImage(
    file = file, mimeType = "image/jpeg", widthPx = 4000, heightPx = 3000,
    orientation = ImageOrientation.NORMAL, source = source,
)

class CameraViewModelTest {
    private lateinit var store: FakeImageStore
    private lateinit var validator: FakeValidator
    private lateinit var vm: CameraViewModel

    @Before
    fun setUp() {
        store = FakeImageStore()
        validator = FakeValidator()
        // Unconfined: coroutines launched by the VM run eagerly, so assertions are deterministic.
        vm = CameraViewModel(store, validator, CoroutineScope(Dispatchers.Unconfined))
    }

    private val state get() = vm.uiState.value

    private fun captureAndAccept(): ValidatedImage {
        val file = vm.beginCapture()!!
        vm.onCaptureSaved(file)
        return (state as CameraUiState.ImageSelected).image
    }

    // ---------------------------------------------------------------- transitions
    @Test fun `starts in CameraReady`() = assertEquals(CameraUiState.CameraReady, state)

    @Test fun `successful capture ends in ImageSelected from camera`() {
        val file = vm.beginCapture()
        assertNotNull(file)
        assertEquals(CameraUiState.Capturing, state)
        vm.onCaptureSaved(file!!)
        val selected = state as CameraUiState.ImageSelected
        assertEquals(ImageSourceType.CAMERA, selected.image.source)
        assertEquals(file, selected.image.file)
        assertEquals(listOf(file), validator.validated)
    }

    @Test fun `rapid repeated taps cannot start overlapping captures`() {
        assertNotNull(vm.beginCapture())
        assertNull(vm.beginCapture())
        assertNull(vm.beginCapture())
        assertEquals(CameraUiState.Capturing, state)
        assertEquals(1, store.created) // exactly one capture file was ever created
    }

    @Test fun `cannot begin capture while an image is selected`() {
        captureAndAccept()
        assertNull(vm.beginCapture())
    }

    @Test fun `capture failure shows error, deletes file and retry returns to camera`() {
        val file = vm.beginCapture()!!
        vm.onCaptureFailed(file)
        assertEquals(CameraUiState.Failed(ImageProblem.CAPTURE_FAILED), state)
        assertTrue(file in store.deleted)
        vm.dismissError()
        assertEquals(CameraUiState.CameraReady, state)
        assertNotNull(vm.beginCapture()) // can capture again
    }

    @Test fun `capture file creation failure is reported without crashing`() {
        store.createFails = true
        assertNull(vm.beginCapture())
        assertEquals(CameraUiState.Failed(ImageProblem.CAPTURE_FAILED), state)
    }

    @Test fun `late save callback after leaving Capturing deletes the file and keeps state`() {
        val stray = File("/fake/stray.jpg")
        vm.onCaptureSaved(stray)
        assertEquals(CameraUiState.CameraReady, state)
        assertEquals(listOf(stray), store.deleted)
        assertTrue(validator.validated.isEmpty())
    }

    @Test fun `camera unavailable is reported`() {
        vm.onCameraUnavailable()
        assertEquals(CameraUiState.Failed(ImageProblem.CAMERA_UNAVAILABLE), state)
    }

    // ---------------------------------------------------------------- invalid images
    @Test fun `invalid captured image is rejected and deleted`() {
        validator.results = { _, _ -> ValidationResult.Invalid(ImageProblem.UNSUPPORTED_FORMAT) }
        val file = vm.beginCapture()!!
        vm.onCaptureSaved(file)
        assertEquals(CameraUiState.Failed(ImageProblem.UNSUPPORTED_FORMAT), state)
        assertTrue(file in store.deleted)
    }

    @Test fun `each validation problem is surfaced for gallery images`() {
        for (problem in listOf(ImageProblem.NOT_AN_IMAGE, ImageProblem.UNSUPPORTED_FORMAT,
            ImageProblem.INVALID_DIMENSIONS, ImageProblem.TOO_LARGE)) {
            validator.results = { _, _ -> ValidationResult.Invalid(problem) }
            vm.onImagePicked("content://media/picker/1")
            assertEquals(CameraUiState.Failed(problem), state)
            vm.dismissError()
            assertEquals(CameraUiState.CameraReady, state)
        }
    }

    @Test fun `unreadable gallery uri is reported and retry returns to camera`() {
        store.importResult = { throw ImageImportException(ImageProblem.IMPORT_FAILED) }
        vm.onImagePicked("content://media/picker/missing")
        assertEquals(CameraUiState.Failed(ImageProblem.IMPORT_FAILED), state)
        assertTrue(validator.validated.isEmpty())
        vm.dismissError()
        assertEquals(CameraUiState.CameraReady, state)
    }

    @Test fun `too large import is reported`() {
        store.importResult = { throw ImageImportException(ImageProblem.TOO_LARGE) }
        vm.onImagePicked("content://media/picker/huge")
        assertEquals(CameraUiState.Failed(ImageProblem.TOO_LARGE), state)
    }

    // ---------------------------------------------------------------- gallery & cancellation
    @Test fun `cancelling the picker changes nothing`() {
        vm.onImagePicked(null)
        assertEquals(CameraUiState.CameraReady, state)
        val img = captureAndAccept()
        vm.onImagePicked(null)
        assertEquals(CameraUiState.ImageSelected(img), state)
        assertTrue(store.importCalls.isEmpty())
    }

    @Test fun `gallery selection becomes ImageSelected from gallery`() {
        vm.onImagePicked("content://media/picker/1")
        val selected = state as CameraUiState.ImageSelected
        assertEquals(ImageSourceType.GALLERY, selected.image.source)
        assertEquals(listOf("content://media/picker/1"), store.importCalls)
    }

    @Test fun `choosing another image replaces the selection and deletes the old file`() {
        vm.onImagePicked("content://media/picker/1")
        val first = (state as CameraUiState.ImageSelected).image
        vm.onImagePicked("content://media/picker/2")
        val second = (state as CameraUiState.ImageSelected).image
        assertFalse(first.file == second.file)
        assertTrue(first.file in store.deleted)
        assertFalse(second.file in store.deleted)
    }

    @Test fun `failed replacement keeps the previous image`() {
        vm.onImagePicked("content://media/picker/1")
        val first = (state as CameraUiState.ImageSelected).image
        store.importResult = { throw ImageImportException(ImageProblem.IMPORT_FAILED) }
        vm.onImagePicked("content://media/picker/bad")
        assertEquals(CameraUiState.Failed(ImageProblem.IMPORT_FAILED), state)
        assertFalse(first.file in store.deleted)
        vm.dismissError()
        assertEquals(CameraUiState.ImageSelected(first), state)
    }

    @Test fun `picking is ignored while capturing`() {
        vm.beginCapture()
        vm.onImagePicked("content://media/picker/1")
        assertEquals(CameraUiState.Capturing, state)
        assertTrue(store.importCalls.isEmpty())
    }

    // ---------------------------------------------------------------- retake / confirm
    @Test fun `retake deletes the image and returns to the camera`() {
        val img = captureAndAccept()
        vm.retake()
        assertEquals(CameraUiState.CameraReady, state)
        assertTrue(img.file in store.deleted)
    }

    @Test fun `confirm without a valid image does nothing`() {
        assertNull(vm.confirm())
        assertEquals(CameraUiState.CameraReady, state)
        vm.beginCapture()
        assertNull(vm.confirm())
        assertEquals(CameraUiState.Capturing, state)
        vm.onCaptureFailed(null)
        assertNull(vm.confirm()) // Failed state
        assertTrue(state is CameraUiState.Failed)
    }

    @Test fun `confirm hands the image over exactly once and keeps the file`() {
        val img = captureAndAccept()
        val confirmed = vm.confirm()
        assertSame(img, confirmed)
        assertEquals(CameraUiState.Confirmed(img), state)
        assertNull(vm.confirm()) // no double hand-off
        vm.discardUnconfirmed() // what onCleared does
        assertFalse(img.file in store.deleted) // consumer owns it now
    }

    @Test fun `startOver after confirm returns to the camera`() {
        captureAndAccept()
        vm.confirm()
        vm.startOver()
        assertEquals(CameraUiState.CameraReady, state)
    }

    @Test fun `clearing the view model deletes unconfirmed files`() {
        val pending = vm.beginCapture()!!
        vm.discardUnconfirmed()
        assertTrue(pending in store.deleted)

        val vm2 = CameraViewModel(store, validator, CoroutineScope(Dispatchers.Unconfined))
        vm2.onImagePicked("content://media/picker/9")
        val img = (vm2.uiState.value as CameraUiState.ImageSelected).image
        vm2.discardUnconfirmed()
        assertTrue(img.file in store.deleted)
    }
}
