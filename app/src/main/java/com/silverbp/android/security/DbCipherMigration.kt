package com.silverbp.android.security

import android.content.Context
import android.util.Log
import com.silverbp.android.core.db.SilverBpDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream

private const val TAG = "DbCipherMigration"

/** Swap journal next to the db — exists only while step 5 is in flight. */
private const val SWAP_JOURNAL_SUFFIX = ".swap"

/**
 * One-shot, reversible migration of the Room database between plaintext and
 * SQLCipher-encrypted, for the opt-in app-lock + at-rest encryption feature.
 *
 * This is the highest-risk code in the feature (health data, irreversible if
 * botched), so every path is defensive — see notes/biometric-app-lock-plan.md
 * §3. Discipline, identical for [encrypt] and [decrypt]:
 *
 *   1. Close Room ([SilverBpDatabase.resetForMigration]) so the file is idle.
 *   2. Snapshot the live db + `-wal`/`-shm` to `*.bak`.
 *   3. `sqlcipher_export` source → a fresh side file, copying `user_version`
 *      so Room does not think a schema migration / destructive recreate is due.
 *   4. Verify the side file: opens, `PRAGMA quick_check` = ok, `user_version`
 *      preserved, and per-table row counts equal the source.
 *   5. Only then journal + swap: land a `*.swap` journal (fsynced), delete
 *      sidecars + the original, rename the side file in, flip the keystore
 *      marker with a synchronous commit, then delete the journal and `*.bak`.
 *   6. Any failure → restore from `*.bak`, leave the marker false, no data loss.
 *
 * Process death at ANY point inside step 5 is repaired before Room's next
 * open by [reconcileSwapOnStartup] — see its KDoc for the recovery state
 * machine.
 *
 * SQLCipher KDF input is the Base64 passphrase string from [DbKeyStore]
 * (SQL-safe, identical bytes to what `SupportOpenHelperFactory` receives).
 *
 * Runs blocking I/O — callers must invoke off the main thread.
 */
object DbCipherMigration {

    sealed interface Outcome {
        /** DB is now in the requested state; keystore marker updated. */
        data object Success : Outcome
        /**
         * Migration aborted. [restored] is true when the original DB was put
         * back intact (the normal failure case — no data loss, still
         * plaintext/encrypted as before). [restored] = false is the rare
         * worst case where even rollback failed; the `*.bak` files are kept.
         */
        data class Failed(val reason: String, val restored: Boolean) : Outcome
    }

    /** Plaintext `silverbp.db` → SQLCipher-encrypted. */
    fun encrypt(context: Context, keyStore: DbKeyStore): Outcome =
        transform(context, keyStore, toEncrypted = true)

    /** SQLCipher-encrypted `silverbp.db` → plaintext, then clears the key. */
    fun decrypt(context: Context, keyStore: DbKeyStore): Outcome =
        transform(context, keyStore, toEncrypted = false)

    /**
     * Startup repair for a swap interrupted by process death. Called from
     * [SilverBpDatabase] before Room first opens the file; the normal path is
     * a single `File.exists()` on the journal and out.
     *
     * The journal (`silverbp.db.swap`) lands (fsynced) before step 5 touches
     * anything and is deleted only after the marker commit, so its presence
     * means "swap in flight". It carries no payload — the surviving files
     * decide the repair ([swapRecoveryFor]), and the target marker value is
     * re-derived from the main file's own header ([looksEncrypted]):
     *
     *   main ✓ side ✓ → killed before the original was deleted; original is
     *                   intact → [SwapRecovery.ROLL_BACK] (drop the side file).
     *   main ✗ side ✓ → killed between delete and rename; the side file was
     *                   already verified in step 4 → [SwapRecovery.ROLL_FORWARD]
     *                   (rename it into place).
     *   main ✓ side ✗ → killed before the marker landed →
     *                   [SwapRecovery.FINALIZE] (re-commit the marker only).
     *   main ✗ side ✗ → unreachable in step 5; defensive →
     *                   [SwapRecovery.RESTORE_BACKUP] from `*.bak`.
     *
     * Every branch ends by committing the marker to match the file itself and
     * only THEN deleting the journal + `*.bak` files; if the repair fails they
     * are all kept for the next launch. (A decrypt killed before
     * [DbKeyStore.clear] leaves a stale passphrase behind — harmless, it is
     * ignored while the marker is false.)
     */
    fun reconcileSwapOnStartup(context: Context, keyStore: DbKeyStore) {
        val main = context.getDatabasePath(SilverBpDatabase.DB_NAME)
        val swapJournal = File(main.path + SWAP_JOURNAL_SUFFIX)
        if (!swapJournal.exists()) return

        val side = File(main.path + ".mig")
        val recovery = swapRecoveryFor(mainExists = main.exists(), sideExists = side.exists())
        Log.w(TAG, "interrupted swap detected — repairing via $recovery")
        when (recovery) {
            SwapRecovery.ROLL_BACK -> side.delete()
            SwapRecovery.ROLL_FORWARD ->
                if (!side.renameTo(main)) {
                    Log.e(TAG, "roll-forward rename failed; journal kept for next launch")
                    return
                }
            SwapRecovery.FINALIZE -> Unit
            SwapRecovery.RESTORE_BACKUP -> {
                val ok = runCatching {
                    (listOf(main) + sidecarFiles(main)).forEach { f ->
                        val bak = File(f.path + ".bak")
                        if (bak.exists()) bak.copyTo(f, overwrite = true)
                    }
                }.isSuccess
                if (!ok || !main.exists()) {
                    Log.e(TAG, "backup restore failed; journal + *.bak kept for next launch")
                    return
                }
            }
        }
        keyStore.setDbEncrypted(looksEncrypted(main))
        swapJournal.delete()
        (listOf(main) + sidecarFiles(main)).forEach { File(it.path + ".bak").delete() }
    }

    /** Repair action for an interrupted swap. See [reconcileSwapOnStartup]. */
    enum class SwapRecovery { ROLL_BACK, ROLL_FORWARD, FINALIZE, RESTORE_BACKUP }

    /**
     * Pure, unit-testable core of [reconcileSwapOnStartup]: which repair
     * applies given which step-5 files survived the crash.
     */
    fun swapRecoveryFor(mainExists: Boolean, sideExists: Boolean): SwapRecovery = when {
        mainExists && sideExists -> SwapRecovery.ROLL_BACK
        sideExists -> SwapRecovery.ROLL_FORWARD
        mainExists -> SwapRecovery.FINALIZE
        else -> SwapRecovery.RESTORE_BACKUP
    }

    /**
     * True when [file] holds SQLCipher ciphertext: it exists with content but
     * its header is not the plaintext SQLite magic. Missing/short files are
     * "not encrypted" — plain SQLite may create or reject them safely; an
     * unreadable file is treated as encrypted (fail safe — never plain-open
     * what might be ciphertext). The file itself is the most robust signal of
     * the at-rest state (survives a broken Keystore or a lost marker), so both
     * reconciliation and the [SilverBpDatabase] keystore-failure fallback
     * decide from it.
     */
    fun looksEncrypted(file: File): Boolean {
        if (!file.exists() || file.length() < SQLITE_MAGIC.size) return false
        val header = ByteArray(SQLITE_MAGIC.size)
        runCatching {
            file.inputStream().use { DataInputStream(it).readFully(header) }
        }.getOrElse { return true }
        return !isPlaintextSqliteHeader(header)
    }

    /** Pure, unit-testable: header starts with the 16-byte `SQLite format 3\0` plaintext magic. */
    fun isPlaintextSqliteHeader(header: ByteArray): Boolean =
        header.size >= SQLITE_MAGIC.size &&
            header.copyOf(SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)

    /**
     * Pure, unit-testable core of step 4: the migration succeeds only if the
     * exported copy has exactly the same tables with exactly the same row
     * counts as the source.
     */
    fun rowCountsMatch(before: Map<String, Long>, after: Map<String, Long>): Boolean =
        before.keys == after.keys && before.all { (t, n) -> after[t] == n }

    private fun transform(
        context: Context,
        keyStore: DbKeyStore,
        toEncrypted: Boolean,
    ): Outcome {
        // Guard against a no-op / wrong-direction call corrupting state.
        if (keyStore.isDbEncrypted() == toEncrypted) {
            return Outcome.Success
        }

        SilverBpDatabase.resetForMigration()

        val main = context.getDatabasePath(SilverBpDatabase.DB_NAME)
        val sidecars = sidecarFiles(main)
        val side = File(main.path + ".mig")          // export target
        val swapJournal = File(main.path + SWAP_JOURNAL_SUFFIX)
        val backups = (listOf(main) + sidecars).map { it to File(it.path + ".bak") }

        // Fresh-install opt-in: no data yet. Just flip the marker; Room will
        // create the file already encrypted (or plaintext) on next open.
        if (!main.exists()) {
            keyStore.setDbEncrypted(toEncrypted)
            if (!toEncrypted) keyStore.clear()
            return Outcome.Success
        }

        System.loadLibrary("sqlcipher")
        side.delete()
        swapJournal.delete()    // defensive — startup reconciliation consumes it

        // ---- 2. snapshot ------------------------------------------------
        try {
            backups.forEach { (src, bak) -> if (src.exists()) src.copyTo(bak, overwrite = true) }
        } catch (e: Exception) {
            Log.e(TAG, "snapshot failed", e)
            backups.forEach { (_, bak) -> bak.delete() }
            return Outcome.Failed("snapshot: ${e.message}", restored = true)
        }

        try {
            // ---- 3. export source → side file ---------------------------
            val srcPass = if (toEncrypted) "" else keyStore.getOrCreatePassphrase()
            val dstPass = if (toEncrypted) keyStore.getOrCreatePassphrase() else ""

            val (before, userVersion) = openDb(main.path, srcPass) { db ->
                db.rawExecSQL("PRAGMA wal_checkpoint(TRUNCATE);")
                val v = db.version
                val counts = tableRowCounts(db)
                db.rawExecSQL("ATTACH DATABASE '${sqlLit(side.path)}' AS mig KEY '${sqlLit(dstPass)}';")
                db.query("SELECT sqlcipher_export('mig');").use { it.moveToFirst() }
                db.rawExecSQL("PRAGMA mig.user_version = $v;")
                db.rawExecSQL("DETACH DATABASE mig;")
                counts to v
            }

            // ---- 4. verify the side file --------------------------------
            openDb(side.path, dstPass) { db ->
                val check = db.query("PRAGMA quick_check;").use { c ->
                    if (c.moveToFirst()) c.getString(0) else "no-result"
                }
                require(check == "ok") { "quick_check=$check" }
                require(db.version == userVersion) { "user_version drift ${db.version}!=$userVersion" }
                val after = tableRowCounts(db)
                require(rowCountsMatch(before, after)) {
                    "row-count mismatch before=$before after=$after"
                }
            }

            // ---- 5. journal + swap --------------------------------------
            // The journal lands (fsynced) before anything destructive, so a
            // process death at ANY later point is repaired on the next launch
            // by [reconcileSwapOnStartup] — see its KDoc for the state machine.
            writeSwapJournal(swapJournal)
            sidecars.forEach { it.delete() }            // stale journals must not replay
            if (!main.delete()) error("could not delete original db")
            if (!side.renameTo(main)) error("could not move encrypted db into place")

            keyStore.setDbEncrypted(toEncrypted)        // synchronous commit inside
            if (!toEncrypted) keyStore.clear()

            swapJournal.delete()
            backups.forEach { (_, bak) -> bak.delete() }
            Log.i(TAG, "migration ok toEncrypted=$toEncrypted tables=${before.size}")
            return Outcome.Success
        } catch (e: Exception) {
            Log.e(TAG, "migration failed toEncrypted=$toEncrypted — rolling back", e)
            side.delete()
            val restored = runCatching {
                backups.forEach { (orig, bak) ->
                    orig.delete()
                    if (bak.exists()) bak.copyTo(orig, overwrite = true)
                }
            }.isSuccess
            if (restored) {
                // Healthy original back in place → the journal has nothing
                // left to repair. On a failed rollback both are kept so the
                // startup reconciliation can retry from the `*.bak` files.
                swapJournal.delete()
                backups.forEach { (_, bak) -> bak.delete() }
            }
            return Outcome.Failed(e.message ?: e.javaClass.simpleName, restored)
        }
    }

    /** The `-wal` / `-shm` / `-journal` sidecars SQLite may keep next to [main]. */
    private fun sidecarFiles(main: File): List<File> =
        listOf("-wal", "-shm", "-journal").map { File(main.path + it) }

    /** Land the swap journal durably (write + fsync) before any destructive step. */
    private fun writeSwapJournal(file: File) {
        FileOutputStream(file).use { out ->
            out.write(1)
            out.fd.sync()
        }
    }

    /** 16-byte plaintext SQLite header magic; SQLCipher overwrites it with the KDF salt. */
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** Row count of every user table (skips sqlite internal, android_metadata, Room bookkeeping). */
    private fun tableRowCounts(db: SQLiteDatabase): Map<String, Long> {
        val names = mutableListOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('android_metadata','room_master_table');",
        ).use { c -> while (c.moveToNext()) names += c.getString(0) }
        return names.associateWith { t ->
            db.query("SELECT count(*) FROM \"$t\";").use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
        }
    }

    /** net.zetetic [SQLiteDatabase] is not [java.io.Closeable]; open/try-finally instead. */
    private inline fun <R> openDb(path: String, pass: String, block: (SQLiteDatabase) -> R): R {
        val db = SQLiteDatabase.openOrCreateDatabase(path, pass, null, null)
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    /** rawQuery overload disambiguation: bind no args, return a Cursor (Closeable). */
    private fun SQLiteDatabase.query(sql: String) = rawQuery(sql, null as Array<String>?)

    /** Escape single quotes for embedding in an `ATTACH ... KEY '...'` literal. */
    private fun sqlLit(s: String): String = s.replace("'", "''")
}
