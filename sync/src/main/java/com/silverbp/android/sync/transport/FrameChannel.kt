package com.silverbp.android.sync.transport

import kotlinx.coroutines.channels.Channel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Bidirectional framed byte channel. Each `send`/`receive` exchanges one
 * complete length-prefixed frame; chunking and reassembly are the channel's
 * concern.
 *
 * Wire layout per frame:
 * ```
 *   length : 4 bytes big-endian uint
 *   body   : `length` bytes (encrypted by Noise)
 * ```
 *
 * The framing length applies to whatever bytes the caller hands in (for
 * `NoiseTransport`, that's already-encrypted Noise output). Caller must
 * cap frame size at [SyncFraming.MAX_FRAME_BYTES].
 */
interface FrameChannel {
    /** Send one length-prefixed frame. Suspends if the underlying transport blocks. */
    suspend fun send(frame: ByteArray)

    /** Receive one length-prefixed frame. Returns null on clean end-of-stream. */
    suspend fun receive(): ByteArray?

    suspend fun close()
}

/**
 * Adapt a blocking [InputStream]/[OutputStream] pair (e.g. a TCP `Socket`'s
 * streams) to a coroutine-friendly [FrameChannel]. The actual blocking I/O
 * runs on the calling dispatcher; production callers should pump it on
 * `Dispatchers.IO`.
 */
class StreamFrameChannel(
    private val input: InputStream,
    private val output: OutputStream,
) : FrameChannel {
    private val din = DataInputStream(input)
    private val dout = DataOutputStream(output)

    override suspend fun send(frame: ByteArray) {
        require(frame.size <= SyncFraming.MAX_FRAME_BYTES) {
            "frame too large: ${frame.size} > ${SyncFraming.MAX_FRAME_BYTES}"
        }
        dout.writeInt(frame.size)
        dout.write(frame)
        dout.flush()
    }

    override suspend fun receive(): ByteArray? {
        val len = try {
            din.readInt()
        } catch (e: java.io.EOFException) {
            return null
        }
        require(len in 0..SyncFraming.MAX_FRAME_BYTES) {
            "frame length out of range: $len"
        }
        val buf = ByteArray(len)
        din.readFully(buf)
        return buf
    }

    override suspend fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }
}

/**
 * In-memory back-to-back [FrameChannel] pair. Useful for testing the
 * Noise + CBOR pipeline without sockets.
 *
 * Returns `(a, b)` where bytes sent on `a` arrive at `b`'s `receive` and
 * vice-versa. Closing one side EOFs the other.
 */
object MemoryPipe {
    fun create(): Pair<FrameChannel, FrameChannel> {
        val aToB = Channel<ByteArray>(capacity = Channel.UNLIMITED)
        val bToA = Channel<ByteArray>(capacity = Channel.UNLIMITED)
        val a = ChannelFrameChannel(send = aToB, receive = bToA)
        val b = ChannelFrameChannel(send = bToA, receive = aToB)
        return a to b
    }
}

internal class ChannelFrameChannel(
    private val send: Channel<ByteArray>,
    private val receive: Channel<ByteArray>,
) : FrameChannel {
    override suspend fun send(frame: ByteArray) {
        send.send(frame)
    }

    override suspend fun receive(): ByteArray? {
        return try {
            receive.receive()
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            null
        }
    }

    override suspend fun close() {
        send.close()
    }
}
