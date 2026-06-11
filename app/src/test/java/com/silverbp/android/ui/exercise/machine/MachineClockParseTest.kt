package com.silverbp.android.ui.exercise.machine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MachineClockParseTest {

    @Test fun `bare minutes scale to seconds`() {
        // Consoles show e.g. "32" for 32:00, not 0:32.
        assertEquals(32 * 60, parseClockToSeconds("32"))
    }

    @Test fun `mm ss parses to total seconds`() {
        assertEquals(32 * 60 + 15, parseClockToSeconds("32:15"))
    }

    @Test fun `h mm ss parses to total seconds`() {
        assertEquals(1 * 3600 + 2 * 60 + 30, parseClockToSeconds("1:02:30"))
    }

    @Test fun `trims surrounding whitespace`() {
        assertEquals(32 * 60, parseClockToSeconds("  32  "))
    }

    @Test fun `blank or null is null`() {
        assertNull(parseClockToSeconds(null))
        assertNull(parseClockToSeconds(""))
        assertNull(parseClockToSeconds("   "))
    }

    @Test fun `non-numeric segment is null`() {
        assertNull(parseClockToSeconds("12:ab"))
    }

    @Test fun `too many segments is null`() {
        assertNull(parseClockToSeconds("1:2:3:4"))
    }
}
