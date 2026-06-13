package com.silverbp.android.ui.member

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbp.android.R
import com.silverbp.android.core.Member
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.ui.paywall.GateReason
import com.silverbp.android.ui.paywall.LocalPaywallController
import kotlinx.coroutines.launch

/**
 * Fixed 0..7 palette used for member avatars and chart identity colours. The
 * index is persisted in [Member.colorIndex]; both the editor swatch picker and
 * the management/list avatars resolve through here so they always agree.
 */
object MemberPalette {
    val colors: List<Color> = listOf(
        Color(0xFF1F6FEB), // blue
        Color(0xFF34C759), // green
        Color(0xFFFF9500), // orange
        Color(0xFFAF52DE), // purple
        Color(0xFFFF2D55), // pink/red
        Color(0xFF00C7BE), // teal
        Color(0xFFFFCC00), // amber
        Color(0xFF8E8E93), // grey
    )

    /** White reads acceptably on every swatch above. */
    val onColor: Color = Color.White

    fun colorFor(index: Int): Color = colors[index.coerceIn(0, colors.lastIndex)]
}

/**
 * Family-member management (roadmap §3-6). Lists active members (owner first,
 * then by sortOrder), supports up/down reorder, archive (owner excluded), an
 * archived section with restore, and add/edit via [MemberEditorSheet].
 *
 * No premium gating yet — Phase 3 adds it at the single point marked below
 * ([onAddMember]). Opened from the Settings 家人成員 card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberManagementScreen(onClose: () -> Unit) {
    val repo = remember { ServiceLocator.memberRepository }
    val entitlements = remember { ServiceLocator.entitlementManager }
    val scope = rememberCoroutineScope()

    val members by repo.observeActive().collectAsStateWithLifecycle(initialValue = emptyList())
    // Archived members aren't in observeActive(); pull the full list and filter.
    var archived by remember { mutableStateOf<List<Member>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(members) {
        archived = repo.getAll().filter { it.archived }
    }

    // Premium gate (Phase 3): the app-wide hoisted paywall (PaywallHost). A free
    // user tapping "add member" calls paywall.show(GateReason.AddMember).
    val paywall = LocalPaywallController.current

    var editing by remember { mutableStateOf<Member?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingArchive by remember { mutableStateOf<Member?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.member_manage_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    // SINGLE PREMIUM GATE POINT (Phase 3). Free allows only the
                    // owner; adding ANY further member needs Premium. With
                    // PREMIUM_ENFORCED=false isPremium() is always true, so this
                    // behaves exactly as before (editor opens). Editing an existing
                    // member is NOT gated — that path is in MemberRow.onEdit.
                    if (!entitlements.isPremium()) {
                        paywall.show(GateReason.AddMember)
                        return@ExtendedFloatingActionButton
                    }
                    editing = null
                    showEditor = true
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.member_add)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "manage-hint") {
                Text(
                    stringResource(R.string.member_manage_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(members, key = { it.id.toString() }) { member ->
                val index = members.indexOf(member)
                MemberRow(
                    member = member,
                    canMoveUp = index > 0,
                    canMoveDown = index < members.lastIndex,
                    onMoveUp = { scope.launch { swap(repo, members, index, index - 1) } },
                    onMoveDown = { scope.launch { swap(repo, members, index, index + 1) } },
                    onEdit = {
                        editing = member
                        showEditor = true
                    },
                    onArchive = { pendingArchive = member },
                )
            }

            if (archived.isNotEmpty()) {
                item(key = "archived-header") {
                    Text(
                        stringResource(R.string.member_archived_section),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(archived, key = { "archived-${it.id}" }) { member ->
                    ArchivedMemberRow(
                        member = member,
                        onUnarchive = { scope.launch { repo.unarchive(member.id) } },
                    )
                }
            }
        }
    }

    if (showEditor) {
        MemberEditorSheet(
            member = editing,
            sheetState = sheetState,
            onDismiss = { showEditor = false },
        )
    }

    pendingArchive?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingArchive = null },
            title = { Text(stringResource(R.string.member_delete_confirm_title)) },
            text = { Text(stringResource(R.string.member_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingArchive = null
                    scope.launch { repo.archive(target.id) }
                }) { Text(stringResource(R.string.member_delete_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingArchive = null }) {
                    Text(stringResource(R.string.member_delete_confirm_cancel))
                }
            },
        )
    }
}

/**
 * Swap [a]'s sortOrder with [b]'s. Reads the persisted orders off the two rows
 * rather than reusing list indices so the values stay stable even if the active
 * list has gaps (archived rows removed from the sequence).
 */
private suspend fun swap(
    repo: com.silverbp.android.core.member.MemberRepository,
    members: List<Member>,
    a: Int,
    b: Int,
) {
    if (a !in members.indices || b !in members.indices) return
    val first = members[a]
    val second = members[b]
    repo.updateSortOrder(first.id, second.sortOrder)
    repo.updateSortOrder(second.id, first.sortOrder)
}

@Composable
private fun MemberRow(
    member: Member,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MemberAvatar(member)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    displayNameOrFallback(member),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (member.isOwner) {
                    Text(
                        stringResource(R.string.member_owner_badge),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // Reorder controls — disabled at the ends. contentDescription on each
            // icon-only button (audit M31).
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.member_move_up),
                )
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.member_move_down),
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.member_edit),
                )
            }
            // Owner can never be archived (single-owner invariant).
            if (!member.isOwner) {
                IconButton(onClick = onArchive) {
                    Icon(
                        Icons.Filled.Archive,
                        contentDescription = stringResource(R.string.member_archive),
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchivedMemberRow(member: Member, onUnarchive: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MemberAvatar(member)
            Spacer(Modifier.size(12.dp))
            Text(
                displayNameOrFallback(member),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onUnarchive) {
                Icon(
                    Icons.Filled.Unarchive,
                    contentDescription = stringResource(R.string.member_unarchive),
                )
            }
        }
    }
}

@Composable
private fun MemberAvatar(member: Member) {
    val name = displayNameOrFallback(member)
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(MemberPalette.colorFor(member.colorIndex), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1).uppercase(),
            color = MemberPalette.onColor,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Owner with an empty name shows the localized "Me"; others show their name. */
@Composable
private fun displayNameOrFallback(member: Member): String =
    member.displayName.ifBlank { stringResource(R.string.member_me) }
