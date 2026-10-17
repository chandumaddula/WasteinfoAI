package com.wasteinfoai.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinatesTest {

    @Test
    fun `valid coordinates pass validation`() {
        assertTrue(Coordinates(13.0827, 80.2707).isValid)
        assertTrue(Coordinates(0.0, 0.0).isValid)
        assertTrue(Coordinates(-90.0, -180.0).isValid)
        assertTrue(Coordinates(90.0, 180.0).isValid)
    }

    @Test
    fun `invalid latitude fails validation`() {
        assertFalse(Coordinates(91.0, 80.0).isValid)
        assertFalse(Coordinates(-90.5, 80.0).isValid)
        assertFalse(Coordinates(Double.NaN, 80.0).isValid)
    }

    @Test
    fun `invalid longitude fails validation`() {
        assertFalse(Coordinates(20.0, 181.0).isValid)
        assertFalse(Coordinates(20.0, -180.5).isValid)
        assertFalse(Coordinates(20.0, Double.NaN).isValid)
    }
}
