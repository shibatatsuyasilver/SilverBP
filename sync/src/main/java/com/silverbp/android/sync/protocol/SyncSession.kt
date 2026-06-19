package com.silverbp.android.sync.protocol

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.HlcClock
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.transport.NoiseTransport

/**
 * Read source of records to ship to the peer. The session asks for every
 * record with `hlc > peerLastHlcSeen` so the peer doesn't see what it
 * already has.
 */
fun interface SyncRecordSource {
    suspend fun recordsSince(peerLastHlcSeen: Hlc, limit: Int): List<SyncRecord>
}

/**
 * Apply incoming records to the local store. The mapper handles LWW gating;
 * the session is just the message pump.
 */
fun interface SyncRecordSink {
    suspend fun apply(record: SyncRecord)
}

class SyncProtocolException(message: String) : IllegalStateException(message)

/**
 * Drives one round of bidirectional BP-reading sync over a [NoiseTransport].
 *
 * Wire trace (six frames per round, each direction interleaves):
 * ```
 *   A → B  HELLO   {deviceId=A, lastHlcSeen=H_A}
 *   B → A  HELLO   {deviceId=B, lastHlcSeen=H_B}
 *   A → B  RECORDS [records hlc > H_B]
 *   B → A  RECORDS [records hlc > H_A]
 *   A → B  ACK     {hlc=max received from B}
 *   B → A  ACK     {hlc=max received from A}
 *   A → B  BYE
 *   B → A  BYE
 * ```
 *
 * Both peers must call [run] concurrently — the protocol doesn't survive
 * a serial run because each `transport.receiveFrame()` would block forever
 * waiting on a peer that's also blocked on receive.
 */
class SyncSession(
    private val transport: NoiseTransport,
    private val localDeviceId: String,
    private val clock: HlcClock,
    private val source: SyncRecordSource,
    private val sink: SyncRecordSink,
    private val getLocalLastHlcSeen: suspend () -> Hlc,
    private val updateLocalLastHlcSeen: suspend (Hlc) -> Unit,
    // 50k chosen so a single-pair workload (BP+exercise+route_point+meds+
    // step_log+achievements across years) fits in one round on LAN. Per
    // record averages ~80 bytes after CBOR; 50k × 80 ≈ 4 MB single frame,
    // well under Noise's 64 KiB per-message ceiling once we chunk in the
    // future. For now the framing layer handles it as one message.
    private val recordsBatchLimit: Int = 50_000,
) {
    /** One full sync round-trip. Returns the highest HLC observed from peer. */
    suspend fun run(): Hlc {
        // 1. HELLO exchange
        val ourHello = ProtocolMessage.Hello(localDeviceId, getLocalLastHlcSeen())
        transport.sendFrame(ProtocolCodec.encode(ourHello))
        val peerHelloBytes = transport.receiveFrame() ?: error("EOF before peer HELLO")
        val peerHello = decodeExpected<ProtocolMessage.Hello>(peerHelloBytes, "HELLO")

        // 2. RECORDS exchange — ship everything since peer's last seen
        val outgoing = source.recordsSince(peerHello.lastHlcSeen, recordsBatchLimit)
        transport.sendFrame(ProtocolCodec.encode(ProtocolMessage.Records(outgoing)))
        val peerRecordsBytes = transport.receiveFrame() ?: error("EOF before peer RECORDS")
        val peerRecords = decodeExpected<ProtocolMessage.Records>(peerRecordsBytes, "RECORDS")

        // 3. Apply incoming, advance HLC clock + watermark
        var maxIncoming = ourHello.lastHlcSeen
        for (rec in peerRecords.records) {
            sink.apply(rec)
            clock.observe(rec.hlc)
            if (rec.hlc > maxIncoming) maxIncoming = rec.hlc
        }
        updateLocalLastHlcSeen(maxIncoming)

        // 4. ACK exchange
        transport.sendFrame(ProtocolCodec.encode(ProtocolMessage.Ack(maxIncoming)))
        val peerAckBytes = transport.receiveFrame() ?: error("EOF before peer ACK")
        decodeExpected<ProtocolMessage.Ack>(peerAckBytes, "ACK")

        // 5. BYE — best-effort; the peer may have closed already.
        try {
            transport.sendFrame(ProtocolCodec.encode(ProtocolMessage.Bye))
        } catch (_: Throwable) { /* peer already gone */ }

        return maxIncoming
    }

    private inline fun <reified T : ProtocolMessage> decodeExpected(
        bytes: ByteArray,
        expected: String,
    ): T {
        return when (val msg = ProtocolCodec.decode(bytes)) {
            is ProtocolMessage.ProtocolError -> throw SyncProtocolException(msg.reason)
            is T -> msg
            else -> throw SyncProtocolException("expected $expected, got ${messageName(msg)}")
        }
    }

    private fun messageName(msg: ProtocolMessage): String = when (msg) {
        is ProtocolMessage.Hello -> "HELLO"
        is ProtocolMessage.Records -> "RECORDS"
        is ProtocolMessage.Ack -> "ACK"
        ProtocolMessage.Bye -> "BYE"
        is ProtocolMessage.ProtocolError -> "PROTOCOL_ERROR"
    }
}
