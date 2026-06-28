package com.silverbp.android.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BpResponseParserTest {

    @Test fun `parses bare json`() {
        val r = BpResponseParser.parse("""{"systolic":135,"diastolic":85,"pulse":72,"confidence":0.95}""")
        assertEquals(135, r.systolic)
        assertEquals(85, r.diastolic)
        assertEquals(72, r.pulse)
    }

    @Test fun `strips json fences`() {
        val r = BpResponseParser.parse("```json\n{\"systolic\":120,\"diastolic\":80,\"confidence\":0.9}\n```")
        assertEquals(120, r.systolic)
    }

    @Test fun `strips bare fences`() {
        val r = BpResponseParser.parse("```\n{\"systolic\":118,\"diastolic\":76,\"confidence\":0.9}\n```")
        assertEquals(118, r.systolic)
    }

    @Test fun `tolerates leading prose`() {
        val r = BpResponseParser.parse("Reading: {\"systolic\":140,\"diastolic\":90,\"confidence\":0.8}")
        assertEquals(140, r.systolic)
    }

    @Test fun `rejects low confidence`() {
        assertThrows(BpExtractionError.LowConfidence::class.java) {
            BpResponseParser.parse("""{"systolic":120,"diastolic":80,"confidence":0.3}""")
        }
    }

    @Test fun `rejects missing systolic`() {
        assertThrows(BpExtractionError.MissingFields::class.java) {
            BpResponseParser.parse("""{"diastolic":80,"confidence":0.9}""")
        }
    }

    @Test fun `rejects malformed json`() {
        assertThrows(BpExtractionError.InvalidJson::class.java) {
            BpResponseParser.parse("not json at all")
        }
    }

    // --- Physiological sanity gate (#17, #16) -------------------------------

    @Test fun `accepts normal reading`() {
        val r = BpResponseParser.parse("""{"systolic":120,"diastolic":80,"pulse":70,"confidence":0.95}""")
        assertEquals(120, r.systolic)
        assertEquals(80, r.diastolic)
        assertEquals(70, r.pulse)
    }

    @Test fun `rejects systolic below range`() {
        // systolic must be in 40..300
        val e = assertThrows(BpExtractionError.InvalidReading::class.java) {
            BpResponseParser.parse("""{"systolic":39,"diastolic":30,"confidence":0.95}""")
        }
        assertEquals("systolicRange", e.reason)
    }

    @Test fun `rejects systolic above range`() {
        // systolic must be in 40..300
        val e = assertThrows(BpExtractionError.InvalidReading::class.java) {
            BpResponseParser.parse("""{"systolic":301,"diastolic":80,"confidence":0.95}""")
        }
        assertEquals("systolicRange", e.reason)
    }

    @Test fun `rejects diastolic below range`() {
        // diastolic must be in 30..200
        val e = assertThrows(BpExtractionError.InvalidReading::class.java) {
            BpResponseParser.parse("""{"systolic":120,"diastolic":29,"confidence":0.95}""")
        }
        assertEquals("diastolicRange", e.reason)
    }

    @Test fun `rejects diastolic above range`() {
        // diastolic must be in 30..200 (checked before the systolic<=diastolic gate)
        val e = assertThrows(BpExtractionError.InvalidReading::class.java) {
            BpResponseParser.parse("""{"systolic":150,"diastolic":201,"confidence":0.95}""")
        }
        assertEquals("diastolicRange", e.reason)
    }

    @Test fun `rejects systolic not above diastolic`() {
        val e = assertThrows(BpExtractionError.InvalidReading::class.java) {
            BpResponseParser.parse("""{"systolic":80,"diastolic":120,"confidence":0.95}""")
        }
        assertEquals("systolicNotAboveDiastolic", e.reason)
    }

    @Test fun `rejects systolic equal to diastolic`() {
        val e = assertThrows(BpExtractionError.InvalidReading::class.java) {
            BpResponseParser.parse("""{"systolic":100,"diastolic":100,"confidence":0.95}""")
        }
        assertEquals("systolicNotAboveDiastolic", e.reason)
    }

    @Test fun `rejects pulse out of range`() {
        // pulse, when present, must be in 20..300
        val e = assertThrows(BpExtractionError.InvalidReading::class.java) {
            BpResponseParser.parse("""{"systolic":120,"diastolic":80,"pulse":301,"confidence":0.95}""")
        }
        assertEquals("pulseRange", e.reason)
    }
}
