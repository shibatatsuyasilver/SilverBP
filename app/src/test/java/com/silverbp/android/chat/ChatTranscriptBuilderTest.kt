package com.silverbp.android.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTranscriptBuilderTest {

    private fun user(text: String) = ChatMessage(role = ChatMessage.Role.User, text = text)
    private fun assistant(text: String) = ChatMessage(role = ChatMessage.Role.Assistant, text = text)

    @Test fun `system block is always present`() {
        val out = ChatTranscriptBuilder.build("SYS", emptyList(), budget = 1000)
        assertEquals(1, out.size)
        assertEquals(ChatMessage.Role.System, out[0].role)
        assertEquals("SYS", out[0].text)
    }

    @Test fun `under budget keeps full history verbatim`() {
        val history = listOf(
            user("a"),
            assistant("b"),
            user("c"),
            assistant("d"),
            user("latest"),
        )
        val out = ChatTranscriptBuilder.build("SYS", history, budget = 10_000)
        // system + 5 history msgs, no marker
        assertEquals(6, out.size)
        assertEquals(ChatMessage.Role.System, out[0].role)
        assertEquals("a", out[1].text)
        assertEquals("latest", out.last().text)
        assertFalse(out.any { it.text.startsWith("[earlier turns omitted") })
    }

    @Test fun `over budget drops oldest pairs and inserts marker`() {
        val history = listOf(
            user("OLD-USER-1-XXXXXXXXXXXXXXXXXXXXXXXX"),       // 18+4=22 tokens
            assistant("OLD-ASSIST-1-YYYYYYYYYYYYYYYYYYYY"),    // 16+4=20
            user("OLD-USER-2-ZZZZZZZZZZZZZZZZ"),               // 14+4=18
            assistant("OLD-ASSIST-2-WWWWWWW"),                 // 11+4=15
            user("RECENT-USER"),                               // 6+4=10
            assistant("RECENT-ASSIST"),                        // 7+4=11
            user("LATEST"),                                    // 3+4=7
        )
        // System=4. Latest=7. Pairs cost: oldest 22+20=42, mid 18+15=33, recent 10+11=21.
        // Budget 50: room after system+latest = 39. Take recent pair (21). Room 18 < 33 + 42, stop.
        // Dropped pairs = 2 (4 turns) → marker.
        val out = ChatTranscriptBuilder.build("SYS!", history, budget = 50)
        assertEquals(ChatMessage.Role.System, out[0].role)
        assertEquals(ChatMessage.Role.Assistant, out[1].role)
        assertTrue(out[1].text.startsWith("[earlier turns omitted: 4]"))
        assertEquals("RECENT-USER", out[2].text)
        assertEquals("RECENT-ASSIST", out[3].text)
        assertEquals("LATEST", out.last().text)
        assertEquals(ChatMessage.Role.User, out.last().role)
    }

    @Test fun `dropping exactly one pair still inserts marker (two turns)`() {
        // Two pairs in earlier history; budget room only for the recent pair.
        val history = listOf(
            user("OLD-USER-AAAAAAAAAAAA"),                     // 11+4=15
            assistant("OLD-ASSIST-BBBBBBBBBBBB"),              // 13+4=17
            user("RECENT-USER-CCC"),                           // 8+4=12
            assistant("RECENT-ASSIST-DDD"),                    // 10+4=14
            user("Q"),                                         // 1+4=5
        )
        // System=4. Latest=5. Pairs: oldest 32, recent 26. Budget 40 → room 31; drop oldest (32 > 31), keep recent (26 fits).
        val out = ChatTranscriptBuilder.build("SYS!", history, budget = 40)
        assertNotNull(out.find { it.text.startsWith("[earlier turns omitted: 2]") })
        assertEquals("Q", out.last().text)
    }

    @Test fun `latest user turn is preserved even if larger than budget`() {
        val huge = "X".repeat(2_000)
        val history = listOf(
            user("history"),
            assistant("history-r"),
            user(huge),
        )
        val out = ChatTranscriptBuilder.build("SYS", history, budget = 50)
        // Invariant 5: only [system, lastUser]
        assertEquals(2, out.size)
        assertEquals(ChatMessage.Role.System, out[0].role)
        assertEquals(huge, out[1].text)
    }

    @Test fun `pair never split — drop both sides of an old turn together`() {
        val history = listOf(
            user("OLD-U-XXXXXXXX"),                            // 8+4=12
            assistant("OLD-A-YYYYYYYY"),                       // 8+4=12
            user("KEEP-U"),                                    // 4+4=8
            assistant("KEEP-A"),                               // 4+4=8
            user("LAST"),                                      // 2+4=6
        )
        // System=4. Latest=6. Recent pair 16. Old pair 24. Budget 35 → room 25; recent fits (16). Old (24) does not.
        val out = ChatTranscriptBuilder.build("SYS", history, budget = 35)
        // The old user/assistant should both be absent.
        assertFalse(out.any { it.text == "OLD-U-XXXXXXXX" })
        assertFalse(out.any { it.text == "OLD-A-YYYYYYYY" })
        assertEquals("LAST", out.last().text)
    }

    @Test fun `single pair under budget produces no marker`() {
        val history = listOf(
            user("hi"),
            assistant("hello"),
            user("how are you"),
        )
        val out = ChatTranscriptBuilder.build("SYS", history, budget = 1_000)
        assertFalse(out.any { it.text.startsWith("[earlier turns omitted") })
        assertEquals(4, out.size) // system + 3 messages
    }
}
