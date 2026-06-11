package com.silverbp.android.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-logic tests for the migration's integrity gate. The DB-touching parts
 * are exercised on-device by [DbCipherMigrationInstrumentedTest]; here we pin
 * the row-count comparison that decides whether the swap is allowed to happen,
 * the swap-recovery state machine behind [DbCipherMigration.reconcileSwapOnStartup],
 * and the SQLite header sniff that re-derives the encryption marker.
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

    // ---- swap-recovery state machine (interrupted step-5 swap) ----------

    @Test fun both_files_surviving_rolls_back() {
        assertEquals(
            DbCipherMigration.SwapRecovery.ROLL_BACK,
            DbCipherMigration.swapRecoveryFor(mainExists = true, sideExists = true),
        )
    }

    @Test fun only_side_surviving_rolls_forward() {
        assertEquals(
            DbCipherMigration.SwapRecovery.ROLL_FORWARD,
            DbCipherMigration.swapRecoveryFor(mainExists = false, sideExists = true),
        )
    }

    @Test fun only_main_surviving_finalizes_marker() {
        assertEquals(
            DbCipherMigration.SwapRecovery.FINALIZE,
            DbCipherMigration.swapRecoveryFor(mainExists = true, sideExists = false),
        )
    }

    @Test fun nothing_surviving_restores_backup() {
        assertEquals(
            DbCipherMigration.SwapRecovery.RESTORE_BACKUP,
            DbCipherMigration.swapRecoveryFor(mainExists = false, sideExists = false),
        )
    }

    // ---- SQLite header sniff ---------------------------------------------

    private val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    @Test fun plaintext_magic_is_recognised() {
        assertTrue(DbCipherMigration.isPlaintextSqliteHeader(magic))
    }

    @Test fun magic_with_trailing_page_bytes_is_recognised() {
        assertTrue(DbCipherMigration.isPlaintextSqliteHeader(magic + byteArrayOf(0x10, 0x00)))
    }

    @Test fun ciphertext_salt_is_not_plaintext() {
        assertFalse(DbCipherMigration.isPlaintextSqliteHeader(ByteArray(16) { (it * 37).toByte() }))
    }

    @Test fun truncated_header_is_not_plaintext() {
        assertFalse(DbCipherMigration.isPlaintextSqliteHeader("SQLite".toByteArray(Charsets.US_ASCII)))
    }

    @Test fun looksEncrypted_false_for_missing_and_plaintext_files() {
        val f = File.createTempFile("silverbp", ".db")
        try {
            f.writeBytes(magic + ByteArray(100))
            assertFalse(DbCipherMigration.looksEncrypted(f))
        } finally {
            f.delete()
        }
        assertFalse("missing file is not ciphertext", DbCipherMigration.looksEncrypted(f))
    }

    @Test fun looksEncrypted_true_for_ciphertext_header_file() {
        val f = File.createTempFile("silverbp", ".db")
        try {
            f.writeBytes(ByteArray(116) { (it * 37).toByte() })
            assertTrue(DbCipherMigration.looksEncrypted(f))
        } finally {
            f.delete()
        }
    }
}
