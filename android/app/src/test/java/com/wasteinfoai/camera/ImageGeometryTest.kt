package com.wasteinfoai.camera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGeometryTest {
    @Test fun `exif tags map to rotation and mirror`() {
        val expected = mapOf(
            1 to ImageOrientation(0, false), 2 to ImageOrientation(0, true),
            3 to ImageOrientation(180, false), 4 to ImageOrientation(180, true),
            5 to ImageOrientation(90, true), 6 to ImageOrientation(90, false),
            7 to ImageOrientation(270, true), 8 to ImageOrientation(270, false),
        )
        expected.forEach { (tag, o) -> assertEquals("tag $tag", o, ImageOrientation.fromExifTag(tag)) }
    }

    @Test fun `unknown exif tags are treated as normal`() {
        for (tag in listOf(0, 9, -1, 255)) assertEquals(ImageOrientation.NORMAL, ImageOrientation.fromExifTag(tag))
    }

    @Test fun `only quarter turns swap axes`() {
        assertTrue(ImageOrientation.fromExifTag(6).swapsAxes)
        assertTrue(ImageOrientation.fromExifTag(8).swapsAxes)
        assertTrue(ImageOrientation.fromExifTag(5).swapsAxes)
        assertFalse(ImageOrientation.fromExifTag(1).swapsAxes)
        assertFalse(ImageOrientation.fromExifTag(3).swapsAxes)
    }

    @Test fun `display dimensions account for orientation`() {
        val portraitStoredLandscape = ValidatedImage(File("a"), "image/jpeg", 4000, 3000,
            ImageOrientation.fromExifTag(6), ImageSourceType.CAMERA)
        assertEquals(3000, portraitStoredLandscape.displayWidthPx)
        assertEquals(4000, portraitStoredLandscape.displayHeightPx)
        val normal = portraitStoredLandscape.copy(orientation = ImageOrientation.NORMAL)
        assertEquals(4000, normal.displayWidthPx)
    }

    @Test fun `sample size keeps the longer edge at or above the target`() {
        assertEquals(1, ImageSampling.sampleSize(800, 600, 1024))
        assertEquals(1, ImageSampling.sampleSize(2047, 1000, 1024))
        assertEquals(2, ImageSampling.sampleSize(2048, 1000, 1024))
        assertEquals(2, ImageSampling.sampleSize(4000, 3000, 1024))
        assertEquals(4, ImageSampling.sampleSize(4096, 4096, 1024))
        assertEquals(8, ImageSampling.sampleSize(12000, 9000, 1024))
        for (size in listOf(1, 17, 4000, 12000)) {
            val s = ImageSampling.sampleSize(size, size, 1024)
            assertTrue(s and (s - 1) == 0) // power of two
            assertTrue(size / s >= 1024 || s == 1)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sample size rejects non positive dimensions`() { ImageSampling.sampleSize(0, 10, 1024) }
}
