package com.silverbp.android.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CI unit tests for [GlucoseResponseParser] (no model / no network — runs in
 * `:app:testDebugUnitTest`). Mirrors [BpResponseParserTest]'s idiom (bare-JSON
 * parse, fence stripping, leading-prose tolerance, error-type assertions) and
 * adds coverage for the glucose-specific unit/range cross-check in
 * [GlucoseResponseParser.crossCheckUnit].
 *
 * Constants under test (from the parser): MMOL_MAX = 35.0, MGDL_MIN = 40.0,
 * CONFLICT_MAX_CONFIDENCE = 0.4, inference confidence ceiling = 0.9.
 */
class GlucoseResponseParserTest {

    private val eps = 1e-9

    // --- 1. Accu-Chek / Roche-style mg/dL integers parse cleanly ---------------

    @Test fun `parses Accu-Chek style mgdl integer 95`() {
        val r = GlucoseResponseParser.parse("""{"value":95,"unit":"mgdl","confidence":0.95}""")
        assertEquals(95.0, r.value!!, eps)
        assertEquals("mgdl", r.unit)
    }

    @Test fun `parses mgdl integer 137`() {
        val r = GlucoseResponseParser.parse("""{"value":137,"unit":"mgdl","confidence":0.9}""")
        assertEquals(137.0, r.value!!, eps)
        assertEquals("mgdl", r.unit)
    }

    @Test fun `parses high mgdl integer 220`() {
        val r = GlucoseResponseParser.parse("""{"value":220,"unit":"mgdl","confidence":0.92}""")
        assertEquals(220.0, r.value!!, eps)
        assertEquals("mgdl", r.unit)
    }

    @Test fun `accepts mg per dl unit spelling and normalizes to mgdl`() {
        // GlucosePrompt may surface "mg/dl"; crossCheckUnit normalizes it to "mgdl".
        val r = GlucoseResponseParser.parse("""{"value":110,"unit":"mg/dl","confidence":0.9}""")
        assertEquals(110.0, r.value!!, eps)
        assertEquals("mgdl", r.unit)
    }

    // --- 2. mmol/L decimals parse cleanly --------------------------------------

    @Test fun `parses mmol decimal 5_3`() {
        val r = GlucoseResponseParser.parse("""{"value":5.3,"unit":"mmol","confidence":0.95}""")
        assertEquals(5.3, r.value!!, eps)
        assertEquals("mmol", r.unit)
    }

    @Test fun `parses high mmol decimal 11_1`() {
        val r = GlucoseResponseParser.parse("""{"value":11.1,"unit":"mmol","confidence":0.9}""")
        assertEquals(11.1, r.value!!, eps)
        assertEquals("mmol", r.unit)
    }

    // --- 3. Unit conflict caps confidence at <= 0.4 ----------------------------

    @Test fun `value 137 claimed mmol conflicts and caps confidence`() {
        // 137 >= MMOL_MAX (35.0) so it can't be mmol/L → confidence capped at 0.4.
        // Parser leaves the claimed unit as-is here (only the mgdl branch rewrites unit).
        val r = GlucoseResponseParser.parse("""{"value":137,"unit":"mmol","confidence":0.95}""")
        assertEquals(137.0, r.value!!, eps)
        assertTrue("confidence should be capped <= 0.4 but was ${r.confidence}", r.confidence!! <= 0.4)
    }

    @Test fun `value 6_5 claimed mgdl conflicts caps confidence and keeps mgdl`() {
        // 6.5 < MGDL_MIN (40.0) so it can't be mg/dL → confidence capped at 0.4.
        // SURPRISING: the parser does NOT flip the unit to mmol; it keeps the
        // claimed "mgdl" and only lowers confidence (see crossCheckUnit mgdl branch).
        val r = GlucoseResponseParser.parse("""{"value":6.5,"unit":"mgdl","confidence":0.95}""")
        assertEquals(6.5, r.value!!, eps)
        assertEquals("mgdl", r.unit)
        assertTrue("confidence should be capped <= 0.4 but was ${r.confidence}", r.confidence!! <= 0.4)
    }

    // --- 4. Unit inference from range when unit is null ------------------------

    @Test fun `infers mmol from low value when unit missing`() {
        val r = GlucoseResponseParser.parse("""{"value":6.5,"confidence":0.95}""")
        assertEquals(6.5, r.value!!, eps)
        assertEquals("mmol", r.unit)
        // Inferred (not read) units are slightly less certain → capped at 0.9.
        assertTrue("inferred confidence should be <= 0.9 but was ${r.confidence}", r.confidence!! <= 0.9)
    }

    @Test fun `infers mgdl from high value when unit missing`() {
        val r = GlucoseResponseParser.parse("""{"value":137,"confidence":0.95}""")
        assertEquals(137.0, r.value!!, eps)
        assertEquals("mgdl", r.unit)
        assertTrue("inferred confidence should be <= 0.9 but was ${r.confidence}", r.confidence!! <= 0.9)
    }

    @Test fun `ambiguous overlap value leaves unit null when missing`() {
        // 37 is in the 35.0–40.0 overlap: neither looksMmol nor looksMgdl → unit
        // stays null so the confirm screen asks the user. Confidence untouched.
        val r = GlucoseResponseParser.parse("""{"value":37,"confidence":0.95}""")
        assertEquals(37.0, r.value!!, eps)
        assertNull(r.unit)
        assertEquals(0.95, r.confidence!!, eps)
    }

    // --- 5. Markdown fence stripping + leading-prose tolerance ------------------

    @Test fun `strips json fences`() {
        val r = GlucoseResponseParser.parse("```json\n{\"value\":98,\"unit\":\"mgdl\",\"confidence\":0.9}\n```")
        assertEquals(98.0, r.value!!, eps)
        assertEquals("mgdl", r.unit)
    }

    @Test fun `strips bare fences`() {
        val r = GlucoseResponseParser.parse("```\n{\"value\":5.5,\"unit\":\"mmol\",\"confidence\":0.9}\n```")
        assertEquals(5.5, r.value!!, eps)
        assertEquals("mmol", r.unit)
    }

    @Test fun `tolerates leading prose`() {
        val r = GlucoseResponseParser.parse("Reading: {\"value\":142,\"unit\":\"mgdl\",\"confidence\":0.85}")
        assertEquals(142.0, r.value!!, eps)
        assertEquals("mgdl", r.unit)
    }

    // --- 6. measureContext parsing (measure_context JSON key) ------------------

    @Test fun `parses fasting measure context`() {
        val r = GlucoseResponseParser.parse(
            """{"value":95,"unit":"mgdl","measure_context":"fasting","confidence":0.9}"""
        )
        assertEquals("fasting", r.measureContext)
    }

    @Test fun `parses after_meal measure context`() {
        val r = GlucoseResponseParser.parse(
            """{"value":160,"unit":"mgdl","measure_context":"after_meal","confidence":0.9}"""
        )
        assertEquals("after_meal", r.measureContext)
    }

    @Test fun `measure context null when meter shows no marker`() {
        val r = GlucoseResponseParser.parse("""{"value":100,"unit":"mgdl","confidence":0.9}""")
        assertNull(r.measureContext)
    }

    // --- 7. Error cases --------------------------------------------------------

    @Test fun `missing value throws MissingFields`() {
        assertThrows(BpExtractionError.MissingFields::class.java) {
            GlucoseResponseParser.parse("""{"unit":"mgdl","confidence":0.9}""")
        }
    }

    @Test fun `malformed json throws InvalidJson`() {
        assertThrows(BpExtractionError.InvalidJson::class.java) {
            GlucoseResponseParser.parse("not json at all")
        }
    }

    @Test fun `non-numeric value throws InvalidJson`() {
        // value is required to be a Double; a string breaks decode → InvalidJson.
        assertThrows(BpExtractionError.InvalidJson::class.java) {
            GlucoseResponseParser.parse("""{"value":"abc","unit":"mgdl","confidence":0.9}""")
        }
    }
}
