package com.silverbp.android.sync.protocol

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncRecord

/**
 * One frame of the post-handshake sync protocol. The wire envelope is a
 * CBOR map keyed by ints; field 1 is always [SyncMessageType.tag], remaining
 * fields are message-specific.
 *
 * Phase 1 covers HELLO / RECORDS / ACK / BYE — enough for a full bidirectional
 * BP-reading sync round. Digest-based diffing (DIGEST_REQ/RESP) and BLOB
 * fetch land in later phases.
 */
sealed class ProtocolMessage {
    /**
     * First message each side sends after the Noise handshake completes.
     * Communicates the local device id (for the peer to log) and the highest
     * HLC we've ever observed (so the peer can skip records we've already seen).
     */
    data class Hello(val deviceId: String, val lastHlcSeen: Hlc) : ProtocolMessage()

    /**
     * Bundle of records the peer should apply. Sent in response to the peer's
     * `Hello.lastHlcSeen` — we ship every record with `hlc > peer.lastHlcSeen`.
     */
    data class Records(val records: List<SyncRecord>) : ProtocolMessage()

    /**
     * Acknowledges receipt of records up to and including the given HLC.
     * The peer uses this to advance its `lastHlcSeen` watermark for our device,
     * so it knows what to ship next round.
     */
    data class Ack(val hlc: Hlc) : ProtocolMessage()

    /** Polite session terminator. */
    data object Bye : ProtocolMessage()
}
