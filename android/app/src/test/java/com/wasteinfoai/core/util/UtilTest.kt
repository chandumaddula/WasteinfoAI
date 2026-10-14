package com.wasteinfoai.core.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilTest {
    @Test fun `file names are safe and unique`() {
        val a = FileNaming.newImageFileName("capture", "jpg")
        val b = FileNaming.newImageFileName("capture", "jpg")
        assertTrue(Regex("^capture_\\d+_[a-z0-9]{8}\\.jpg$").matches(a))
        assertNotEquals(a, b)
        assertEquals("gallery_5_abc12345.img", FileNaming.newImageFileName("gallery", "img", 5, "ABC12345"))
    }

    @Test fun `unsafe name parts are rejected`() {
        for ((p, e) in listOf("../x" to "jpg", "cap ture" to "jpg", "capture" to "j/pg", "" to "jpg", "capture" to "")) {
            val ex = runCatching { FileNaming.newImageFileName(p, e) }.exceptionOrNull()
            assertTrue("expected rejection for '$p' '$e'", ex is IllegalArgumentException)
        }
        assertTrue(runCatching { FileNaming.newImageFileName("a", "b", 1, "../../etc") }.isFailure)
    }

    @Test fun `copy within the limit copies every byte`() {
        val data = ByteArray(50_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        assertEquals(50_000L, copyWithLimit(ByteArrayInputStream(data), out, 50_000))
        assertArrayEquals(data, out.toByteArray())
    }

    @Test fun `copy beyond the limit fails and stops early`() {
        val out = ByteArrayOutputStream()
        val ex = runCatching { copyWithLimit(ByteArrayInputStream(ByteArray(100_000)), out, 10_000) }.exceptionOrNull()
        assertTrue(ex is SizeLimitExceededException)
        assertTrue("wrote at most one buffer past nothing", out.size() <= 10_000)
    }

    @Test fun `empty input copies zero bytes`() {
        assertEquals(0L, copyWithLimit(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), 10))
    }
}
