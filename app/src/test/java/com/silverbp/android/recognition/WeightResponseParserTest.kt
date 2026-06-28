package com.silverbp.android.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Regression coverage for [WeightResponseParser]'s pre-save range gate. Bounds
 * copied from the parser (normalised to kg): MIN_KG = 2.0, MAX_KG = 400.0. The
 * on-screen unit (default "kg") is used to normalise lb → kg before the check.
 * Input is the raw model string (JSON, optionally fenced); output is [ExtractedWeight].
 */
class WeightResponseParserTest {

    @Test fun `accepts normal kg weight`() {
        val r = WeightResponseParser.parse("""{"value":70,"unit":"kg","confidence":0.95}""")
        assertEquals(70.0, r.value!!, 0.0)
        assertEquals("kg", r.unit)
    }

    @Test fun `accepts normal lb weight`() {
        // 154 lb ≈ 69.85 kg, within 2..400
        val r = WeightResponseParser.parse("""{"value":154,"unit":"lb","confidence":0.95}""")
        assertEquals(154.0, r.value!!, 0.0)
        assertEquals("lb", r.unit)
    }

    @Test fun `rejects weight above range`() {
        // 500 kg > MAX_KG (400.0)
        val e = assertThrows(BpExtractionError.InvalidReading::class.java) {
            WeightResponseParser.parse("""{"value":500,"unit":"kg","confidence":0.95}""")
        }
        assertEquals("weightOutOfRange", e.reason)
    }

    @Test fun `rejects weight below range`() {
        // 1 kg < MIN_KG (2.0)
        val e = assertThrows(BpExtractionError.InvalidReading::class.java) {
            WeightResponseParser.parse("""{"value":1,"unit":"kg","confidence":0.95}""")
        }
        assertEquals("weightOutOfRange", e.reason)
    }

    @Test fun `rejects out of range lb weight after normalising to kg`() {
        // 1000 lb ≈ 453.6 kg > MAX_KG (400.0)
        val e = assertThrows(BpExtractionError.InvalidReading::class.java) {
            WeightResponseParser.parse("""{"value":1000,"unit":"lb","confidence":0.95}""")
        }
        assertEquals("weightOutOfRange", e.reason)
    }

    @Test fun `rejects missing value`() {
        // A null value is an unreadable display → InvalidJson (per parser).
        assertThrows(BpExtractionError.InvalidJson::class.java) {
            WeightResponseParser.parse("""{"unit":"kg","confidence":0.9}""")
        }
    }
}
