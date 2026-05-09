package com.silverbp.android.sync.engine

/**
 * Hybrid Logical Clock for cross-device LWW.
 *
 * Layout (16 bytes packed, hex-encoded as 32-char string for lex-sortable
 * storage in SQLite/SwiftData):
 * ```
 *   physicalMs : 48 bits  // wall-clock millis since epoch (good through year ~10889)
 *   logical    : 16 bits  // monotonic counter for ties at same physical ms
 *   nodeId     : 64 bits  // stable per-device identifier (random at install)
 * ```
 *
 * Lexicographic comparison of the hex string is identical to causal order
 * (physical first, then logical, then nodeId for total ordering).
 *
 * Causality:
 *  - On local write:   `next = max(now_ms, last.physicalMs).bumpLogical(if equal)`
 *  - On peer receive:  `next = max(local, peerHlc).bumpLogical(if equal)` — keeps
 *    our subsequent local writes strictly after anything we've seen.
 */
@JvmInline
value class Hlc(val packed: String) : Comparable<Hlc> {

    init {
        require(packed.length == HEX_LEN) {
            "Hlc must be $HEX_LEN-char hex string, got ${packed.length}"
        }
    }

    val physicalMs: Long get() = packed.substring(0, 12).toLong(16)
    val logical: Int get() = packed.substring(12, 16).toInt(16)
    val nodeId: Long get() = packed.substring(16, 32).toULong(16).toLong()

    override fun compareTo(other: Hlc): Int = packed.compareTo(other.packed)

    override fun toString(): String = packed

    companion object {
        const val HEX_LEN = 32
        private const val MAX_PHYSICAL_MS = (1L shl 48) - 1
        private const val MAX_LOGICAL = (1 shl 16) - 1

        /** Sentinel HLC representing "before sync existed"; lex-min so any real hlc wins. */
        val ZERO: Hlc = Hlc("0".repeat(HEX_LEN))

        /** Pack (physicalMs, logical, nodeId) into the 32-char hex form. */
        fun of(physicalMs: Long, logical: Int, nodeId: Long): Hlc {
            require(physicalMs in 0..MAX_PHYSICAL_MS) { "physicalMs out of range: $physicalMs" }
            require(logical in 0..MAX_LOGICAL) { "logical out of range: $logical" }
            val phys = physicalMs.toString(16).padStart(12, '0')
            val log = logical.toString(16).padStart(4, '0')
            val nid = nodeId.toULong().toString(16).padStart(16, '0')
            return Hlc(phys + log + nid)
        }
    }
}

/**
 * Generator for HLCs. Holds the (last seen physical, last logical) pair and
 * the local node id; thread-safe via an internal monitor.
 *
 * Use one [HlcClock] per device process; persist [lastSeen] across restarts so
 * we never go backwards even if the wall clock skews.
 *
 * Implementation note: we deliberately use a synchronized block rather than
 * `AtomicReference<Hlc>` because [Hlc] is a Kotlin value class — every boxing
 * creates a new object, so identity-based `compareAndSet` would loop forever.
 */
class HlcClock(
    private val nodeId: Long,
    private val now: () -> Long = System::currentTimeMillis,
    initial: Hlc = Hlc.ZERO,
) {
    private val lock = Any()
    private var state: Hlc = initial

    /** Snapshot of the most recently issued / observed HLC. */
    val lastSeen: Hlc get() = synchronized(lock) { state }

    /**
     * Issue an HLC for a local write. Always strictly greater than any HLC
     * previously issued or observed on this clock.
     */
    fun next(): Hlc = synchronized(lock) {
        val prev = state
        val nowMs = now()
        val nextPhys: Long
        val nextLog: Int
        if (nowMs > prev.physicalMs) {
            nextPhys = nowMs
            nextLog = 0
        } else {
            nextPhys = prev.physicalMs
            nextLog = prev.logical + 1
            check(nextLog <= 0xFFFF) {
                "HLC logical counter exhausted at $nextPhys ms — clock skew?"
            }
        }
        Hlc.of(nextPhys, nextLog, nodeId).also { state = it }
    }

    /**
     * Observe an HLC from a peer. Bumps our internal max so subsequent local
     * writes are strictly after the peer's. Returns the new high-water mark.
     */
    fun observe(peer: Hlc): Hlc = synchronized(lock) {
        val prev = state
        val nowMs = now()
        val maxPhys = maxOf(nowMs, prev.physicalMs, peer.physicalMs)
        val nextLog: Int = if (maxPhys > prev.physicalMs && maxPhys > peer.physicalMs) {
            0
        } else {
            var seed = -1
            if (maxPhys == prev.physicalMs) seed = maxOf(seed, prev.logical)
            if (maxPhys == peer.physicalMs) seed = maxOf(seed, peer.logical)
            val bumped = seed + 1
            check(bumped <= 0xFFFF) {
                "HLC logical counter exhausted at $maxPhys ms — clock skew?"
            }
            bumped
        }
        Hlc.of(maxPhys, nextLog, nodeId).also { state = it }
    }
}
