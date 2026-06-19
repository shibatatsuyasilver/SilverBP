package com.silverbp.android.sync.protocol

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.transport.CborWriter
import com.silverbp.android.sync.transport.SyncMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCodecTest {

    @Test
    fun decode_skips_unknown_envelope_keys() {
        val expectedHlc = Hlc.of(physicalMs = 1_700_000_000_000L, logical = 0, nodeId = 1L)
        val w = CborWriter()
        w.writeMapHeader(4)
        w.writeUInt(1L); w.writeUInt(SyncMessageType.HELLO.tag.toLong())
        w.writeUInt(99L)
        w.writeMapHeader(1)
        w.writeText("future")
        w.writeArrayHeader(2)
        w.writeUInt(7L)
        w.writeText("ignored")
        w.writeUInt(2L); w.writeText("device-a")
        w.writeUInt(3L); w.writeText(expectedHlc.packed)

        val decoded = ProtocolCodec.decode(w.toByteArray())

        assertEquals(ProtocolMessage.Hello("device-a", expectedHlc), decoded)
    }

    @Test
    fun decode_unknown_message_type_returns_protocol_error() {
        val w = CborWriter()
        w.writeMapHeader(2)
        w.writeUInt(1L); w.writeUInt(123L)
        w.writeUInt(99L); w.writeText("future")

        val decoded = ProtocolCodec.decode(w.toByteArray())

        assertTrue(decoded is ProtocolMessage.ProtocolError)
        val error = decoded as ProtocolMessage.ProtocolError
        assertEquals(123, error.typeTag)
        assertTrue(error.reason.contains("unknown protocol message type tag"))
    }
}
