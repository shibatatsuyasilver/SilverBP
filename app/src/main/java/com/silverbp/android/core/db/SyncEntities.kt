package com.silverbp.android.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Soft-delete record for any synced entity. When a row is deleted locally, we
 * remove it from its origin table and append a tombstone so peers can converge
 * on the delete via LWW (delete wins iff [hlc] > peer's row hlc).
 *
 * GC eligibility: when every paired device's `lastHlcSeen` exceeds [hlc] by 90+
 * days, the tombstone is safe to remove.
 */
@Entity(
    tableName = "tombstone",
    primaryKeys = ["entityType", "pk"],
    indices = [Index("hlc")],
)
data class TombstoneEntity(
    /** Numeric registry tag (see SyncEntityType in :sync). String for forward-compat. */
    val entityType: String,
    /** Origin row's primary key. */
    val pk: String,
    val hlc: String,
    val deletedAt: Long,
)

/**
 * Trusted peer device established via QR + SAS pairing. [pubKey] is the peer's
 * X25519 long-term public key; the shared root key derived from ECDH is stored
 * in EncryptedSharedPreferences (not in Room) keyed by [deviceId].
 */
@Entity(tableName = "sync_device")
data class SyncDeviceEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val pubKey: ByteArray,
    val lastSeenAt: Long,
    /** Highest HLC the local outbox has confirmed delivered to this peer. */
    val lastHlcSeen: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SyncDeviceEntity) return false
        return deviceId == other.deviceId &&
            name == other.name &&
            pubKey.contentEquals(other.pubKey) &&
            lastSeenAt == other.lastSeenAt &&
            lastHlcSeen == other.lastHlcSeen
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + pubKey.contentHashCode()
        result = 31 * result + lastSeenAt.hashCode()
        result = 31 * result + lastHlcSeen.hashCode()
        return result
    }
}

/**
 * Append-only queue of CBOR-encoded SyncRecord payloads waiting for a peer to
 * come online. Drained by the sync coordinator after each successful round; we
 * keep [createdAt] for ordering and observability.
 */
@Entity(tableName = "sync_outbox", indices = [Index("createdAt")])
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val payload: ByteArray,
    val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SyncOutboxEntity) return false
        return seq == other.seq &&
            payload.contentEquals(other.payload) &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = seq.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
