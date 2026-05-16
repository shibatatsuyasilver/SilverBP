package com.silverbp.android.coach

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Pure-function tests for [CoachNarrator]'s nickname-injection helper. Full
 * end-to-end prompt assembly is exercised on-device via the manual coach
 * flow — here we only verify the deterministic instruction line.
 *
 * Locale handling: the helper now switches between zh-TW and en based on
 * `Locale.getDefault()`, so each test sets the default explicitly and the
 * `@After` restores the JVM default (other tests in this module rely on it).
 */
class CoachNarratorTest {

    private val previousLocale: Locale = Locale.getDefault()

    @Before
    fun setUp() {
        // Default the suite to zh-TW; English-locale cases override per-test.
        Locale.setDefault(Locale.TAIWAN)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `blank nickname yields null instruction`() {
        assertNull(CoachNarrator.nicknameInstruction(""))
        assertNull(CoachNarrator.nicknameInstruction("   "))
        assertNull(CoachNarrator.nicknameInstruction("\n\t"))
    }

    @Test
    fun `non-blank nickname is quoted into the instruction`() {
        val s = CoachNarrator.nicknameInstruction("阿公")
        assertNotNull(s)
        assertTrue("expected nickname quoted: $s", s!!.contains("「阿公」"))
    }

    @Test
    fun `nickname is trimmed before quoting`() {
        val s = CoachNarrator.nicknameInstruction("  阿公  ")
        assertNotNull(s)
        assertTrue("expected trimmed nickname quoted: $s", s!!.contains("「阿公」"))
    }

    @Test
    fun `english locale yields english instruction with double-quoted nickname`() {
        Locale.setDefault(Locale.ENGLISH)
        val s = CoachNarrator.nicknameInstruction("Grandpa")
        assertNotNull(s)
        // English variant uses ASCII double quotes; lacks the zh corner-bracket form.
        assertTrue("expected ASCII-quoted nickname in: $s", s!!.contains("\"Grandpa\""))
        assertTrue("expected English wording in: $s", s.contains("user prefers"))
    }

    @Test
    fun `english locale still nulls a blank nickname`() {
        Locale.setDefault(Locale.ENGLISH)
        assertNull(CoachNarrator.nicknameInstruction(""))
        assertNull(CoachNarrator.nicknameInstruction("   "))
    }
}
