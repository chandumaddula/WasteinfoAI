package com.wasteinfoai.ai

import com.wasteinfoai.location.JurisdictionResolver
import com.wasteinfoai.location.WasteJurisdiction
import org.junit.Assert.assertEquals
import org.junit.Test

class JurisdictionResolverTest {

    @Test
    fun resolveJurisdiction_delhiCoordinates_returnsDelhiMcd() {
        val result = JurisdictionResolver.resolveJurisdiction(28.6139, 77.2090)
        assertEquals(WasteJurisdiction.DELHI_MCD, result)
    }

    @Test
    fun resolveJurisdiction_mumbaiCoordinates_returnsMumbaiBmc() {
        val result = JurisdictionResolver.resolveJurisdiction(19.0760, 72.8777)
        assertEquals(WasteJurisdiction.MUMBAI_BMC, result)
    }

    @Test
    fun resolveJurisdiction_bengaluruCoordinates_returnsBengaluruBbmp() {
        val result = JurisdictionResolver.resolveJurisdiction(12.9716, 77.5946)
        assertEquals(WasteJurisdiction.BENGALURU_BBMP, result)
    }

    @Test
    fun resolveJurisdiction_hyderabadCoordinates_returnsIndiaNational() {
        val result = JurisdictionResolver.resolveJurisdiction(17.3850, 78.4867)
        assertEquals(WasteJurisdiction.INDIA_NATIONAL, result)
    }

    @Test
    fun resolveJurisdiction_outsideIndiaCoordinates_returnsGlobalFallback() {
        val result = JurisdictionResolver.resolveJurisdiction(51.5074, -0.1278)
        assertEquals(WasteJurisdiction.GLOBAL_FALLBACK, result)
    }

    @Test
    fun resolveJurisdiction_nanCoordinates_returnsIndiaNationalDefault() {
        val result = JurisdictionResolver.resolveJurisdiction(Double.NaN, 77.2090)
        assertEquals(WasteJurisdiction.INDIA_NATIONAL, result)
    }

    @Test
    fun resolveJurisdiction_outOfRangeCoordinates_returnsIndiaNationalDefault() {
        val result = JurisdictionResolver.resolveJurisdiction(95.0, 77.2090)
        assertEquals(WasteJurisdiction.INDIA_NATIONAL, result)
    }

    @Test
    fun resolveJurisdiction_nullCoordinates_returnsIndiaNationalDefault() {
        val result = JurisdictionResolver.resolveJurisdiction(null, null)
        assertEquals(WasteJurisdiction.INDIA_NATIONAL, result)
    }
}
