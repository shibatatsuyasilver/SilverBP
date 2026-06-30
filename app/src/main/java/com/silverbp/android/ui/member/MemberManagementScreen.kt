package com.silverbp.android.ui.member

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbp.android.R
import com.silverbp.android.core.Member
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.ui.components.AppTopBar
import com.silverbp.android.ui.components.ExpressivePrimaryButton
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.paywall.GateReason
import com.silverbp.android.ui.paywall.LocalPaywallController
import com.silverbp.android.ui.theme.AppSpacing
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
 *
 * Visually mirrors the refreshed Today / UnifiedHistory card idiom: each member
 * is a rounded surface "timeline" tile with a leading colour avatar, the name +
 * owner tag, and trailing reorder / edit / archive controls.
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
    // "This member is actually me" merge-into-owner repair: the target plus the
    // number of records that would move (shown in the confirm dialog).
    var pendingMerge by remember { mutableStateOf<Member?>(null) }
    var pendingMergeCount by remember { mutableStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.member_manage_title),
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
            ExpressivePrimaryButton(
                text = stringResource(R.string.member_add),
                icon = Icons.Filled.Add,
                onClick = {
                    // SINGLE PREMIUM GATE POINT (Phase 3). Free allows only the
                    // owner; adding ANY further member needs Premium. With
                    // PREMIUM_ENFORCED=false isPremium() is always true, so this
                    // behaves exactly as before (editor opens). Editing an existing
                    // member is NOT gated — that path is in MemberRow.onEdit.
                    if (!entitlements.isPremium()) {
                        paywall.show(GateReason.AddMember)
                        return@ExpressivePrimaryButton
                    }
                    editing = null
                    showEditor = true
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = AppSpacing.screenH,
                vertical = AppSpacing.screenV,
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
        ) {
            item(key = "manage-hint") {
                Text(
                    stringResource(R.string.member_manage_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = AppSpacing.tight,
                        bottom = AppSpacing.tight,
                    ),
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
                    onMergeIntoOwner = {
                        scope.launch {
                            pendingMergeCount = repo.memberDataCount(member.id)
                            pendingMerge = member
                        }
                    },
                )
            }

            if (archived.isNotEmpty()) {
                item(key = "archived-header") {
                    Text(
                        stringResource(R.string.member_archived_section),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(
                            start = AppSpacing.tight,
                            top = AppSpacing.sectionGap,
                            bottom = AppSpacing.tight,
                        ),
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

    pendingMerge?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingMerge = null },
            title = { Text(stringResource(R.string.member_merge_confirm_title)) },
            text = { Text(stringResource(R.string.member_merge_confirm_body, pendingMergeCount)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingMerge = null
                    scope.launch { repo.mergeIntoOwner(target.id) }
                }) { Text(stringResource(R.string.member_merge_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingMerge = null }) {
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

/**
 * One active-member tile — a rounded surface card matching the refreshed
 * Today / UnifiedHistory "timeline row" look: a leading colour avatar, the
 * member name + owner tag, and the reorder / edit / archive controls trailing.
 * Generous min-height + 48dp icon buttons keep every target senior-friendly.
 */
@Composable
private fun MemberRow(
    member: Member,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onMergeIntoOwner: () -> Unit,
) {
    StandardCard(
        contentPadding = AppSpacing.itemGap,
        accent = MemberPalette.colorFor(member.colorIndex),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp)
                .padding(start = AppSpacing.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MemberAvatar(member)
            Spacer(Modifier.size(AppSpacing.screenH))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    displayNameOrFallback(member),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (member.isOwner) {
                    OwnerBadge()
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
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            // Owner can never be archived (single-owner invariant). Non-owner
            // rows also offer "this is me" — folds their data into the owner and
            // deletes them, to repair an extra member from an older buggy restore.
            if (!member.isOwner) {
                IconButton(onClick = onMergeIntoOwner) {
                    Icon(
                        Icons.Filled.MergeType,
                        contentDescription = stringResource(R.string.member_merge_into_owner_action),
                    )
                }
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

/** Owner pill — a tinted "本人" badge beneath the name, primary-coloured. */
@Composable
private fun OwnerBadge() {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            stringResource(R.string.member_owner_badge),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Archived-member tile — the same surface-card idiom as [MemberRow] but visually
 * recessed (muted avatar + name) with a single restore action.
 */
@Composable
private fun ArchivedMemberRow(member: Member, onUnarchive: () -> Unit) {
    StandardCard(
        contentPadding = AppSpacing.itemGap,
        accent = MemberPalette.colorFor(member.colorIndex).copy(alpha = 0.40f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(start = AppSpacing.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MemberAvatar(member, dimmed = true)
            Spacer(Modifier.size(AppSpacing.screenH))
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
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Round colour avatar with the member's initial. Mirrors the leading tinted
 * type-icon tile in the Today / UnifiedHistory rows (same 48dp footprint),
 * using the persisted [Member.colorIndex] swatch. [dimmed] softens the swatch
 * for the archived section so restored vs active members read differently.
 */
@Composable
private fun MemberAvatar(member: Member, dimmed: Boolean = false) {
    val name = displayNameOrFallback(member)
    val swatch = MemberPalette.colorFor(member.colorIndex)
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(
                if (dimmed) swatch.copy(alpha = 0.40f) else swatch,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1).uppercase(),
            color = MemberPalette.onColor,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Owner with an empty name shows the localized "Me"; a blank non-owner shows a neutral label. */
@Composable
private fun displayNameOrFallback(member: Member): String =
    member.displayName.ifBlank {
        stringResource(if (member.isOwner) R.string.member_me else R.string.member_unnamed)
    }
