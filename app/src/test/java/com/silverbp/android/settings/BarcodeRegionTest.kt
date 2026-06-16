package com.silverbp.android.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeRegionTest {

    @Test
    fun `well-covered OFF markets are supported`() {
        listOf("US", "CA", "GB", "FR", "DE", "JP", "AU").forEach {
            assertTrue("$it should be supported", BarcodeRegion.isCountrySupported(it))
        }
    }

    @Test
    fun `taiwan is not supported (convenience hot food not in OFF)`() {
        assertFalse(BarcodeRegion.isCountrySupported("TW"))
    }

    @Test
    fun `check is case-insensitive`() {
        assertTrue(BarcodeRegion.isCountrySupported("us"))
        assertTrue(BarcodeRegion.isCountrySupported("Jp"))
    }

    @Test
    fun `blank or unknown country is not supported`() {
        assertFalse(BarcodeRegion.isCountrySupported(""))
        assertFalse(BarcodeRegion.isCountrySupported("ZZ"))
        assertFalse(BarcodeRegion.isCountrySupported("CN"))
    }
}
