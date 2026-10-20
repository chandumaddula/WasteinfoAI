package com.wasteinfoai.ai

import com.wasteinfoai.ai.barcode.ProductLookupService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ProductLookupServiceTest {

    private lateinit var service: ProductLookupService

    @Before
    fun setUp() {
        service = ProductLookupService()
    }

    @Test
    fun testOfflineLocalCatalogBisleriWaterBottle() = runBlocking {
        val result = service.lookup("8901764012235")
        assertNotNull(result)
        assertEquals("plastic", result!!.coarseCategory)
        assertEquals("PET Beverage Bottle", result.specificItem)
        assertEquals("PET 01", result.materialCode)
        assertEquals("Local Packaging DB", result.dataSource)
    }

    @Test
    fun testOfflineLocalCatalogCokeAluminumCan() = runBlocking {
        val result = service.lookup("5449000000996")
        assertNotNull(result)
        assertEquals("metal", result!!.coarseCategory)
        assertEquals("Aluminum Beverage Can", result.specificItem)
        assertEquals("ALU 41", result.materialCode)
    }

    @Test
    fun testBlankBarcodeReturnsNull() = runBlocking {
        val result = service.lookup("   ")
        assertNull(result)
    }
}
