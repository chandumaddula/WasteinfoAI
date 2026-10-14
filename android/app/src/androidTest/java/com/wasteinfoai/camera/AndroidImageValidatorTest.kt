package com.wasteinfoai.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests (need an emulator or device): they exercise the real BitmapFactory/ExifInterface
 * behind [AndroidImageValidator] and [ImageDecoding]. NOT executed in the environment that wrote them.
 */
@RunWith(AndroidJUnit4::class)
class AndroidImageValidatorTest {
    private lateinit var dir: File
    private val validator = AndroidImageValidator()

    @Before fun setUp() {
        dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "validator_test").apply { mkdirs() }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun bitmapFile(name: String, w: Int, h: Int, format: Bitmap.CompressFormat): File {
        val file = File(dir, name)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        FileOutputStream(file).use { bitmap.compress(format, 90, it) }
        bitmap.recycle()
        return file
    }

    private fun validate(file: File) = runBlocking { validator.validate(file, ImageSourceType.GALLERY) }

    private fun bmpFile(name: String, size: Int): File {
        val rowBytes = (size * 3 + 3) / 4 * 4
        val pixels = rowBytes * size
        val buf = ByteBuffer.allocate(54 + pixels).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('B'.code.toByte()).put('M'.code.toByte()).putInt(54 + pixels).putInt(0).putInt(54)
        buf.putInt(40).putInt(size).putInt(size).putShort(1).putShort(24).putInt(0).putInt(pixels)
        buf.putInt(2835).putInt(2835).putInt(0).putInt(0)
        return File(dir, name).apply { writeBytes(buf.array()) }
    }

    @Test fun validJpegAndPngAreAccepted() {
        for ((name, format, mime) in listOf(
            Triple("a.jpg", Bitmap.CompressFormat.JPEG, "image/jpeg"),
            Triple("a.png", Bitmap.CompressFormat.PNG, "image/png"),
        )) {
            val result = validate(bitmapFile(name, 200, 100, format)) as ValidationResult.Valid
            assertEquals(mime, result.image.mimeType)
            assertEquals(200, result.image.widthPx)
            assertEquals(100, result.image.heightPx)
        }
    }

    @Test fun garbageEmptyAndMissingFilesAreNotImages() {
        val garbage = File(dir, "g.bin").apply { writeBytes(ByteArray(2000) { (it * 31).toByte() }) }
        val empty = File(dir, "e.bin").apply { writeBytes(ByteArray(0)) }
        for (f in listOf(garbage, empty, File(dir, "missing.jpg"))) {
            assertEquals(ValidationResult.Invalid(ImageProblem.NOT_AN_IMAGE), validate(f))
        }
    }

    @Test fun decodableButUnsupportedFormatIsRejected() {
        assertEquals(ValidationResult.Invalid(ImageProblem.UNSUPPORTED_FORMAT), validate(bmpFile("x.bmp", 40)))
    }

    @Test fun tinyImagesAreRejected() {
        val result = validate(bitmapFile("tiny.jpg", 8, 8, Bitmap.CompressFormat.JPEG))
        assertEquals(ValidationResult.Invalid(ImageProblem.INVALID_DIMENSIONS), result)
    }

    @Test fun exifOrientationIsReadAndAppliedWhenDecoding() {
        val file = bitmapFile("rot.jpg", 200, 100, Bitmap.CompressFormat.JPEG)
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val image = (validate(file) as ValidationResult.Valid).image
        assertEquals(ImageOrientation(90, false), image.orientation)
        assertEquals(100, image.displayWidthPx)
        assertEquals(200, image.displayHeightPx)

        val bitmap = ImageDecoding.decodeOriented(file, ImageDecoding.PREVIEW_MAX_DIMENSION)
        assertNotNull(bitmap)
        assertEquals(100, bitmap!!.width)
        assertEquals(200, bitmap.height)
        assertTrue(!bitmap.isRecycled)
    }
}
