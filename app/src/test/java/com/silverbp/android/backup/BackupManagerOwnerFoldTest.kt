package com.silverbp.android.backup

import com.silverbp.android.sync.engine.Hlc
import com.silverbp.android.sync.engine.SyncEntityType
import com.silverbp.android.sync.engine.SyncRecord
import com.silverbp.android.sync.engine.SyncValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for "Merge restore adds an extra member holding imported data".
 * Covers [BackupManager.ownerFoldIds] / [BackupManager.remapMemberId] — the pure
 * decision that folds backup owner(s) onto the local owner. The full
 * [BackupManager.import] can't run as a JVM unit test (Argon2/AES need native
 * libs), so the fold logic is verified directly here.
 */
class BackupManagerOwnerFoldTest {

    private fun hlc() = Hlc.of(physicalMs = 1_700_000_000_000L, logical = 0, nodeId = 1L)

    private fun member(pk: String, isOwner: Boolean, name: String = "") = SyncRecord(
        type = SyncEntityType.MEMBER,
        pk = pk,
        hlc = hlc(),
        deletedAt = null,
        payload = mapOf(1 to SyncValue.Text(name), 2 to SyncValue.Bool(isOwner)),
    )

    private fun bp(pk: String, memberId: String) = SyncRecord(
        type = SyncEntityType.BP_READING,
        pk = pk,
        hlc = hlc(),
        deletedAt = null,
        payload = mapOf(1 to SyncValue.Int64(120L), 2 to SyncValue.Int64(80L), 17 to SyncValue.Text(memberId)),
    )

    private fun memberIdOf(r: SyncRecord) = (r.payload[17] as? SyncValue.Text)?.value

    @Test
    fun `clean cross-device backup folds the single owner onto local owner`() {
        val records = listOf(
            member("owner-A", isOwner = true, name = "Me"),
            member("family-M1", isOwner = false, name = "Mom"),
            bp("bp-1", "owner-A"),
            bp("bp-2", "family-M1"),
        )
        val fold = BackupManager.ownerFoldIds(records, localOwnerId = "owner-B")
        assertEquals(setOf("owner-A"), fold)

        // owner-A's BP folds to owner-B; family member's BP is untouched.
        assertEquals("owner-B", memberIdOf(BackupManager.remapMemberId(bp("x", "owner-A"), fold, "owner-B")))
        assertEquals("family-M1", memberIdOf(BackupManager.remapMemberId(bp("y", "family-M1"), fold, "owner-B")))
    }

    @Test
    fun `two owner rows (source merged two owner devices) both fold onto local owner`() {
        // Previously only firstOrNull was folded, so the SECOND owner row became
        // a stray demoted member. Now every isOwner row collapses to local.
        val records = listOf(
            member("owner-A", isOwner = true),
            member("owner-C", isOwner = true),
            member("family-M1", isOwner = false),
        )
        val fold = BackupManager.ownerFoldIds(records, localOwnerId = "owner-B")
        assertEquals(setOf("owner-A", "owner-C"), fold)
        assertFalse("family member must never be folded", "family-M1" in fold)
        assertEquals("owner-B", memberIdOf(BackupManager.remapMemberId(bp("x", "owner-C"), fold, "owner-B")))
    }

    @Test
    fun `owner id equal to local owner is not folded (idempotent re-merge)`() {
        val records = listOf(member("owner-B", isOwner = true), bp("bp-1", "owner-B"))
        assertTrue(BackupManager.ownerFoldIds(records, localOwnerId = "owner-B").isEmpty())
    }

    @Test
    fun `blank local owner folds nothing`() {
        val records = listOf(member("owner-A", isOwner = true))
        assertTrue(BackupManager.ownerFoldIds(records, localOwnerId = "").isEmpty())
    }

    @Test
    fun `demoted-member pollution is NOT auto-folded so cleanup UI must handle it`() {
        // The known residual: a source phone polluted by a pre-fix merge ships a
        // blank isOwner=true row plus a demoted isOwner=false member that holds
        // the real data. Only the blank owner folds; the demoted member with
        // data is wire-indistinguishable from a family member, so it imports as
        // a separate member. This documents WHY the "merge into me" cleanup UI is
        // required — prevention alone leaves this case to the user.
        val records = listOf(
            member("blank-owner", isOwner = true, name = ""),
            member("demoted-me", isOwner = false, name = ""),
            bp("bp-1", "demoted-me"),
        )
        val fold = BackupManager.ownerFoldIds(records, localOwnerId = "owner-B")
        assertEquals(setOf("blank-owner"), fold)
        assertFalse("demoted member with data is not folded by prevention", "demoted-me" in fold)
        // Its BP stays under the demoted member → surfaces as the extra member.
        assertEquals("demoted-me", memberIdOf(BackupManager.remapMemberId(bp("z", "demoted-me"), fold, "owner-B")))
    }
}
