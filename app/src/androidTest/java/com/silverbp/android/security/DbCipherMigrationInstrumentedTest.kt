package com.silverbp.android.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.silverbp.android.core.db.SilverBpDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end round-trip for [DbCipherMigration] against a real on-disk DB:
 * seed plaintext → encrypt (data preserved, file no longer readable plaintext)
 * → decrypt (data preserved, readable plaintext again, key cleared).
 */
@RunWith(AndroidJUnit4::class)
class DbCipherMigrationInstrumentedTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dbFile: File
    private lateinit var keyStore: DbKeyStore

    @Before fun setUp() {
        System.loadLibrary("sqlcipher")
        dbFile = ctx.getDatabasePath(SilverBpDatabase.DB_NAME)
        dbFile.parentFile?.mkdirs()
        listOf("", "-wal", "-shm", "-journal", ".mig", ".bak").forEach {
            File(dbFile.path + it).delete()
        }
        keyStore = DbKeyStore.create(ctx).also { it.clear() }
        seedPlaintext(rows = 7)
    }

    @After fun tearDown() {
        keyStore.clear()
        listOf("", "-wal", "-shm", "-journal", ".mig", ".bak").forEach {
            File(dbFile.path + it).delete()
        }
    }

    @Test fun encrypt_then_decrypt_round_trips_without_data_loss() {
        // --- encrypt ---
        assertEquals(DbCipherMigration.Outcome.Success, DbCipherMigration.encrypt(ctx, keyStore))
        assertTrue("marker should be set", keyStore.isDbEncrypted())
        assertFalse("plaintext open must now fail", canOpenPlaintext())
        assertEquals(7, countWith(keyStore.getOrCreatePassphrase()))

        // --- decrypt ---
        assertEquals(DbCipherMigration.Outcome.Success, DbCipherMigration.decrypt(ctx, keyStore))
        assertFalse("marker cleared", keyStore.isDbEncrypted())
        assertEquals(null, keyStore.passphraseOrNull())
        assertTrue("plaintext readable again", canOpenPlaintext())
        assertEquals(7, countWith(""))
    }

    private inline fun <R> openDb(pass: String, block: (SQLiteDatabase) -> R): R {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile.path, pass, null, null)
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    private fun seedPlaintext(rows: Int) = openDb("") { db ->
        db.execSQL("CREATE TABLE bp_reading (id INTEGER PRIMARY KEY, sys INT, dia INT);")
        db.execSQL("CREATE TABLE chat_message (id INTEGER PRIMARY KEY, body TEXT);")
        repeat(rows) { db.execSQL("INSERT INTO bp_reading (sys,dia) VALUES (120,80);") }
        db.version = 11
    }

    private fun canOpenPlaintext(): Boolean = runCatching {
        openDb("") { db ->
            db.rawQuery("SELECT count(*) FROM bp_reading;", null as Array<String>?)
                .use { it.moveToFirst() }
        }
        true
    }.getOrDefault(false)

    private fun countWith(pass: String): Long = openDb(pass) { db ->
        db.rawQuery("SELECT count(*) FROM bp_reading;", null as Array<String>?).use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        }
    }
}
