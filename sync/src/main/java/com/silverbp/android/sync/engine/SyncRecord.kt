package com.silverbp.android.sync.engine

/**
 * Numeric registry of synced entity types. Wire format uses the [tag]
 * (uint) rather than the string name so frames stay compact and forward-
 * compatible. iOS side mirrors this enum 1:1 — keep tag values in lockstep.
 *
 * Phase 1 only emits [BP_READING], [READING_TAG], [TAG], [MEDICATION] and
 * [USER_PROFILE]; the remaining tags are reserved for Phase 2.
 */
enum class SyncEntityType(val tag: Int, val tableName: String) {
    BP_READING(1, "bp_reading"),
    USER_PROFILE(2, "user_profile"),
    MEDICATION(3, "medication"),
    MEDICATION_SCHEDULE(4, "medication_schedule"),
    TAG(5, "tag"),
    READING_TAG(6, "reading_tag"),
    EXERCISE_SESSION(7, "exercise_session"),
    ROUTE_POINT(8, "route_point"),
    ACHIEVEMENT(9, "achievement"),
    DAILY_STEP_LOG(10, "daily_step_log"),
    CHAT_SESSION(11, "chat_session"),
    CHAT_MESSAGE(12, "chat_message"),
    COACH_PLAN(13, "coach_plan"),
    COACH_TASK(14, "coach_task"),
    SLEEP_LOG(15, "sleep_log"),
    DIET_CHECK(16, "diet_check"),
    MEDICATION_DOSE(17, "medication_dose"),
    SETTINGS_KV(64, "settings_kv"),
    BLOB_META(65, "blob_meta");

    companion object {
        private val byTag = entries.associateBy { it.tag }
        fun fromTag(tag: Int): SyncEntityType =
            byTag[tag] ?: error("Unknown SyncEntityType tag: $tag")
    }
}

/**
 * Sealed payload value types we transit on the wire. CBOR can carry any of
 * these natively; mappers translate entity columns to these. Mirror of iOS
 * `SyncValue`.
 */
sealed class SyncValue {
    object Null : SyncValue() { override fun toString() = "null" }
    data class Bool(val value: Boolean) : SyncValue()
    data class Int64(val value: Long) : SyncValue()
    data class Double(val value: kotlin.Double) : SyncValue()
    data class Text(val value: String) : SyncValue()
    data class Bytes(val value: ByteArray) : SyncValue() {
        override fun equals(other: Any?): Boolean =
            other is Bytes && value.contentEquals(other.value)
        override fun hashCode(): Int = value.contentHashCode()
    }
}

/**
 * Decoded form of a CBOR `SyncRecord` frame. The on-the-wire CBOR map uses
 * numeric keys (1=type, 2=pk, 3=hlc, 4=deletedAt, 5=payload) for compactness;
 * see [com.silverbp.android.sync.transport.SyncRecordCodec] for the encoding
 * boundary.
 *
 * `payload` is an entity-specific map keyed by integer tag. When [deletedAt]
 * is non-null the record is a tombstone and [payload] is empty.
 */
data class SyncRecord(
    val type: SyncEntityType,
    val pk: String,
    val hlc: Hlc,
    val deletedAt: Long?,
    val payload: Map<Int, SyncValue>,
) {
    val isTombstone: Boolean get() = deletedAt != null
}
