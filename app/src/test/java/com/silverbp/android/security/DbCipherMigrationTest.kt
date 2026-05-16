package com.silverbp.android.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the migration's integrity gate. The DB-touching parts
 * are exercised on-device by [DbCipherMigrationInstrumentedTest]; here we pin
 * the row-count comparison that decides whether the swap is allowed to happen.
 */
class DbCipherMigrationTest {

    @Test fun identical_maps_match() {
        val m = mapOf("bp_reading" to 12L, "chat_message" to 3L)
        assertTrue(DbCipherMigration.rowCountsMatch(m, m.toMap()))
    }

    @Test fun differing_count_fails() {
        assertFalse(
            DbCipherMigration.rowCountsMatch(
                mapOf("bp_reading" to 12L),
                mapOf("bp_reading" to 11L),
            ),
        )
    }

    @Test fun missing_table_fails() {
        assertFalse(
            DbCipherMigration.rowCountsMatch(
                mapOf("bp_reading" to 1L, "medication" to 0L),
                mapOf("bp_reading" to 1L),
            ),
        )
    }

    @Test fun extra_table_fails() {
        assertFalse(
            DbCipherMigration.rowCountsMatch(
                mapOf("bp_reading" to 1L),
                mapOf("bp_reading" to 1L, "tombstone" to 0L),
            ),
        )
    }

    @Test fun empty_db_matches_empty() {
        assertTrue(DbCipherMigration.rowCountsMatch(emptyMap(), emptyMap()))
    }
}
