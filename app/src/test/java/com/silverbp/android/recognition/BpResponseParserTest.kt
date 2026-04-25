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
}
