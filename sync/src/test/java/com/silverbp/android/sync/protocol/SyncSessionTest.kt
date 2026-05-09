package com.silverbp.android.sync.protocol

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.HlcClock
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import com.silverbp.android.sync.transport.MemoryPipe
import com.silverbp.android.sync.transport.NoiseTransport
import com.silverbp.android.sync.transport.NoiseXk
import com.silverbp.android.sync.transport.NoiseXkHandshake
import com.silverbp.android.sync.transport.FrameChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1.2's marquee orchestration test. Two SyncSessions run concurrently
 * over an in-memory Noise pipe; each device starts with its own BP readings
 * and ends with both sets after one round.
 *
 * If this passes, "BP reading written on Pixel appears on iPhone after one
 * sync round" is structurally correct end-to-end (modulo the actual TCP
 * socket and Bonjour discovery, which are equivalent transports).
 */
class SyncSessionTest {

    @Test
    fun two_devices_converge_after_one_round() = runTest {
        val (deviceAChannel, deviceBChannel) = MemoryPipe.create()
        val responderStatic = NoiseXk.generateKeyPair()
        val initiatorStatic = NoiseXk.generateKeyPair()

        val transports = coroutineScope {
            val a = async {
                runInitiator(
                    handshake = NoiseXkHandshake(
                        role = NoiseXkHandshake.Role.INITIATOR,
                        localStatic = initiatorStatic,
                        remoteStatic = NoiseXk.publicKeyBytes(responderStatic.public),
                    ),
                    channel = deviceAChannel,
                )
            }
            val b = async {
                runResponder(
                    handshake = NoiseXkHandshake(
                        role = NoiseXkHandshake.Role.RESPONDER,
                        localStatic = responderStatic,
                        remoteStatic = NoiseXk.publicKeyBytes(initiatorStatic.public),
                    ),
                    channel = deviceBChannel,
                )
            }
            a.await() to b.await()
        }
        val (transportA, transportB) = transports

        // Each side starts with 3 records nobody else has.
        val recordsFromA = (0 until 3).map { i ->
            bpRecord(pk = "A-$i", physicalMs = 1_700_000_000_000L + i, nodeId = 0xAAAAL)
        }
        val recordsFromB = (0 until 3).map { i ->
            bpRecord(pk = "B-$i", physicalMs = 1_700_000_000_500L + i, nodeId = 0xBBBBL)
        }

        val storeA = mutableListOf<SyncRecord>().apply { addAll(recordsFromA) }
        val storeB = mutableListOf<SyncRecord>().apply { addAll(recordsFromB) }

        val sessionA = SyncSession(
            transport = transportA,
            localDeviceId = "device-a",
            clock = HlcClock(nodeId = 0xAAAAL),
            source = SyncRecordSource { peerHlc, _ -> storeA.filter { it.hlc > peerHlc } },
            sink = SyncRecordSink { rec -> storeA.add(rec) },
            getLocalLastHlcSeen = { Hlc.ZERO },
            updateLocalLastHlcSeen = { /* no-op for test */ },
        )
        val sessionB = SyncSession(
            transport = transportB,
            localDeviceId = "device-b",
            clock = HlcClock(nodeId = 0xBBBBL),
            source = SyncRecordSource { peerHlc, _ -> storeB.filter { it.hlc > peerHlc } },
            sink = SyncRecordSink { rec -> storeB.add(rec) },
            getLocalLastHlcSeen = { Hlc.ZERO },
            updateLocalLastHlcSeen = { /* no-op for test */ },
        )

        coroutineScope {
            val ja = async { sessionA.run() }
            val jb = async { sessionB.run() }
            ja.await()
            jb.await()
        }

        // Both stores now contain both record sets.
        val pksA = storeA.map { it.pk }.toSet()
        val pksB = storeB.map { it.pk }.toSet()
        val expected = (recordsFromA + recordsFromB).map { it.pk }.toSet()
        assertEquals(expected, pksA)
        assertEquals(expected, pksB)
        assertTrue(storeA.size == 6 && storeB.size == 6)
    }

    @Test
    fun lastHlcSeen_filters_already_acknowledged_records() = runTest {
        val (deviceAChannel, deviceBChannel) = MemoryPipe.create()
        val responderStatic = NoiseXk.generateKeyPair()
        val initiatorStatic = NoiseXk.generateKeyPair()
        val transports = coroutineScope {
            val a = async {
                runInitiator(
                    handshake = NoiseXkHandshake(
                        role = NoiseXkHandshake.Role.INITIATOR,
                        localStatic = initiatorStatic,
                        remoteStatic = NoiseXk.publicKeyBytes(responderStatic.public),
                    ),
                    channel = deviceAChannel,
                )
            }
            val b = async {
                runResponder(
                    handshake = NoiseXkHandshake(
                        role = NoiseXkHandshake.Role.RESPONDER,
                        localStatic = responderStatic,
                        remoteStatic = NoiseXk.publicKeyBytes(initiatorStatic.public),
                    ),
                    channel = deviceBChannel,
                )
            }
            a.await() to b.await()
        }
        val (tA, tB) = transports

        // A has 5 records, B already saw the first 3 (high watermark = R2's hlc).
        val rs = (0 until 5).map { i ->
            bpRecord(pk = "x-$i", physicalMs = 1_700_000_000_000L + i, nodeId = 1L)
        }
        val storeA = rs.toMutableList()
        val storeB = mutableListOf<SyncRecord>()
        val bWatermark = rs[2].hlc // B claims to have already seen R0..R2

        var sourceCalls = 0
        val sessionA = SyncSession(
            transport = tA,
            localDeviceId = "a",
            clock = HlcClock(nodeId = 1L),
            source = SyncRecordSource { peerHlc, _ ->
                sourceCalls++
                storeA.filter { it.hlc > peerHlc }
            },
            sink = SyncRecordSink { rec -> storeA.add(rec) },
            getLocalLastHlcSeen = { Hlc.ZERO },
            updateLocalLastHlcSeen = { },
        )
        val sessionB = SyncSession(
            transport = tB,
            localDeviceId = "b",
            clock = HlcClock(nodeId = 2L),
            source = SyncRecordSource { _, _ -> emptyList() },
            sink = SyncRecordSink { rec -> storeB.add(rec) },
            getLocalLastHlcSeen = { bWatermark },
            updateLocalLastHlcSeen = { },
        )

        coroutineScope {
            val ja = async { sessionA.run() }
            val jb = async { sessionB.run() }
            ja.await()
            jb.await()
        }

        // A's source was asked for records since B's watermark — should
        // ship R3 and R4 only.
        assertEquals(1, sourceCalls)
        assertEquals(2, storeB.size)
        assertEquals(setOf("x-3", "x-4"), storeB.map { it.pk }.toSet())
    }

    // ---- helpers ----

    private fun bpRecord(pk: String, physicalMs: Long, nodeId: Long): SyncRecord {
        return SyncRecord(
            type = SyncEntityType.BP_READING,
            pk = pk,
            hlc = Hlc.of(physicalMs = physicalMs, logical = 0, nodeId = nodeId),
            deletedAt = null,
            payload = mapOf(
                1 to SyncValue.Int64(120),
                2 to SyncValue.Int64(80),
                4 to SyncValue.Int64(physicalMs),
            ),
        )
    }

    private suspend fun runInitiator(
        handshake: NoiseXkHandshake,
        channel: FrameChannel,
    ): NoiseTransport {
        channel.send(handshake.writeFirst())
        val m2 = channel.receive() ?: error("EOF m2")
        handshake.readSecond(m2)
        channel.send(handshake.writeThird())
        val (s, r) = handshake.transportCiphers()
        return NoiseTransport(channel = channel, send = s, receive = r)
    }

    private suspend fun runResponder(
        handshake: NoiseXkHandshake,
        channel: FrameChannel,
    ): NoiseTransport {
        val m1 = channel.receive() ?: error("EOF m1")
        val (_, m2) = handshake.readFirstAndWriteSecond(m1)
        channel.send(m2)
        val m3 = channel.receive() ?: error("EOF m3")
        handshake.readThird(m3)
        val (s, r) = handshake.transportCiphers()
        return NoiseTransport(channel = channel, send = s, receive = r)
    }
}
