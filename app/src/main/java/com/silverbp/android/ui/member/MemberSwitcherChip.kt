package com.silverbp.android.ui.member

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbp.android.R
import com.silverbp.android.core.Member
import com.silverbp.android.di.ServiceLocator
import kotlinx.coroutines.launch

/**
 * TopAppBar chip that shows the current member's avatar + name and opens a sheet
 * to switch between active members or jump to member management (roadmap §3-6).
 *
 * Hidden entirely when only one active member exists, so single-user installs are
 * unaffected (mirrors the enableCoach "default unchanged" philosophy). Reads its
 * own state straight off [ServiceLocator] — it's a self-contained app-bar widget,
 * not tied to any screen ViewModel.
 *
 * @param onManageMembers navigates to [com.silverbp.android.ui.nav.Routes.MEMBER_MANAGE].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberSwitcherChip(onManageMembers: () -> Unit) {
    val repo = remember { ServiceLocator.memberRepository }
    val store = remember { ServiceLocator.currentMemberStore }
    val scope = rememberCoroutineScope()

    val members by repo.observeActive().collectAsStateWithLifecycle(initialValue = emptyList())
    val currentId by store.flow.collectAsStateWithLifecycle(initialValue = "")

    // 只有一位成員時完全隱藏 — 現有單人使用者無感.
    if (members.size <= 1) return

    val current = members.firstOrNull { it.id.toString() == currentId } ?: members.first()
    var showSheet by remember { mutableStateOf(false) }

    Surface(
        onClick = { showSheet = true },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MemberAvatar(current, size = 26.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                displayNameOrFallback(current),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
        ) {
            Text(
                stringResource(R.string.member_switcher_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            members.forEach { member ->
                MemberRow(
                    member = member,
                    selected = member.id.toString() == current.id.toString(),
                    onClick = {
                        scope.launch { store.setCurrent(member.id.toString()) }
                        showSheet = false
                    },
                )
            }
            ManageMembersRow(onClick = {
                showSheet = false
                onManageMembers()
            })
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun MemberRow(member: Member, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberAvatar(member, size = 36.dp)
        Spacer(Modifier.width(16.dp))
        Text(
            displayNameOrFallback(member),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ManageMembersRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Group,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            stringResource(R.string.member_manage_entry),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Coloured circle with the member's first character. Local to the chip; the
 *  management screen has its own copy at a different size. */
@Composable
private fun MemberAvatar(member: Member, size: Dp) {
    val name = displayNameOrFallback(member)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MemberPalette.colorFor(member.colorIndex)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1).uppercase(),
            color = MemberPalette.onColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Owner with an empty name shows the localized "Me"; others show their name. */
@Composable
private fun displayNameOrFallback(member: Member): String =
    member.displayName.ifBlank { stringResource(R.string.member_me) }
