package com.silverbp.android.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [GlucoseResponseParser]'s pre-save sanity gate (#17)
 * and unit-inference flagging (#16). Bounds copied from the parser:
 *  - mg/dL valid range 10.0..1000.0
 *  - mmol/L valid range 0.5..55.0
 *  - a null/unknown unit is treated as mg/dL.
 * Input is the raw model string (JSON, optionally fenced); output is [ExtractedGlucose].
 */
class GlucoseResponseParserTest {

    @Test fun `accepts normal mgdl value`() {
        val r = GlucoseResponseParser.parse("""{"value":100,"unit":"mgdl","confidence":0.95}""")
        assertEquals(100.0, r.value!!, 0.0)
        assertEquals("mgdl", r.unit)
        // Unit was read explicitly off the meter → not inferred.
        assertFalse(r.unitInferred)
    }

    @Test fun `accepts normal mmol value`() {
        val r = GlucoseResponseParser.parse("""{"value":5.5,"unit":"mmol","confidence":0.95}""")
        assertEquals(5.5, r.value!!, 0.0)
        assertEquals("mmol", r.unit)
        assertFalse(r.unitInferred)
    }

    @Test fun `rejects out of range mgdl value`() {
        // 1500 > MGDL_VALID_MAX (1000.0)
        val e = assertThrows(BpExtractionError.InvalidReading::class.java) {
            GlucoseResponseParser.parse("""{"value":1500,"unit":"mgdl","confidence":0.95}""")
        }
        assertEquals("glucoseRange", e.reason)
    }

    @Test fun `rejects out of range mmol value`() {
        // 100 > MMOL_VALID_MAX (55.0) for an explicit mmol claim
        val e = assertThrows(BpExtractionError.InvalidReading::class.java) {
            GlucoseResponseParser.parse("""{"value":100,"unit":"mmol","confidence":0.95}""")
        }
        assertEquals("glucoseRange", e.reason)
    }

    @Test fun `rejects missing value`() {
        assertThrows(BpExtractionError.MissingFields::class.java) {
            GlucoseResponseParser.parse("""{"unit":"mgdl","confidence":0.9}""")
        }
    }

    @Test fun `infers unit and flags it when none given`() {
        // No unit label → parser infers from the range (100 is unambiguously mg/dL)
        // and marks the read as inferred so the confirm screen forces confirmation.
        val r = GlucoseResponseParser.parse("""{"value":100,"confidence":0.95}""")
        assertEquals("mgdl", r.unit)
        assertTrue(r.unitInferred)
    }

    @Test fun `does not flag unitInferred when unit explicit`() {
        val r = GlucoseResponseParser.parse("""{"value":100,"unit":"mgdl","confidence":0.95}""")
        assertFalse(r.unitInferred)
    }
}
