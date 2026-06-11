package com.silverbp.android.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

class CalculateInSampleSizeTest {

    @Test fun `image within bounds decodes at full size`() {
        assertEquals(1, calculateInSampleSize(1024, 768, 2048))
        assertEquals(1, calculateInSampleSize(2048, 1536, 2048))
    }

    @Test fun `slightly oversized image halves once`() {
        assertEquals(2, calculateInSampleSize(2049, 1536, 2048))
        assertEquals(2, calculateInSampleSize(4000, 3000, 2048))
    }

    @Test fun `large image picks smallest power of two within bounds`() {
        assertEquals(4, calculateInSampleSize(8000, 6000, 2048))
        assertEquals(8, calculateInSampleSize(16000, 12000, 2048))
    }

    @Test fun `uses larger dimension regardless of orientation`() {
        assertEquals(calculateInSampleSize(3000, 6000, 2048), calculateInSampleSize(6000, 3000, 2048))
        assertEquals(2, calculateInSampleSize(100, 4096, 2048))
    }

    @Test fun `degenerate inputs fall back to full size`() {
        assertEquals(1, calculateInSampleSize(0, 0, 2048))
        assertEquals(1, calculateInSampleSize(-1, 500, 2048))
        assertEquals(1, calculateInSampleSize(500, 500, 0))
    }
}
