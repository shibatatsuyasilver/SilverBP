package com.silverbp.android.security

import android.content.Context
import android.util.Log
import com.silverbp.android.core.db.SilverBpDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

private const val TAG = "DbCipherMigration"

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
 *   5. Only then atomically swap the side file in and flip the keystore marker.
 *   6. Any failure → restore from `*.bak`, leave the marker false, no data loss.
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
        val wal = File(main.path + "-wal")
        val shm = File(main.path + "-shm")
        val journal = File(main.path + "-journal")
        val sidecars = listOf(wal, shm, journal)
        val side = File(main.path + ".mig")          // export target
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

            // ---- 5. atomic swap -----------------------------------------
            sidecars.forEach { it.delete() }            // stale journals must not replay
            if (!main.delete()) error("could not delete original db")
            if (!side.renameTo(main)) error("could not move encrypted db into place")

            keyStore.setDbEncrypted(toEncrypted)
            if (!toEncrypted) keyStore.clear()

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
            if (restored) backups.forEach { (_, bak) -> bak.delete() }
            return Outcome.Failed(e.message ?: e.javaClass.simpleName, restored)
        }
    }

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
