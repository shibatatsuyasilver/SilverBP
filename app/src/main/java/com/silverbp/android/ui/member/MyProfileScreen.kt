package com.silverbp.android.ui.member

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.silverbp.android.core.Member
import com.silverbp.android.di.ServiceLocator

/**
 * Single-user shortcut to one's own profile (個人資料). A thin host that loads the
 * OWNER member from [com.silverbp.android.core.member.MemberRepository] and opens
 * the existing [MemberEditorSheet] in edit mode for that member, reusing the
 * sheet's own owner-aware save path (which upserts the owner — owner editing is
 * FREE and never behind the AddMember paywall).
 *
 * Distinct from [MemberManagementScreen]: this never lists members and never
 * touches the add-member premium gate. Opened from the Settings 個人資料 card.
 * The sheet's dismiss (drag-down or save) ends the route via [onClose].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyProfileScreen(onClose: () -> Unit) {
    val repo = remember { ServiceLocator.memberRepository }
    // Owner row loaded once; the editor seeds its local state from this member.
    var owner by remember { mutableStateOf<Member?>(null) }
    LaunchedEffect(Unit) {
        owner = repo.owner()
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Render the sheet only once the owner is loaded so the editor seeds from a
    // real member (edit mode) rather than briefly appearing in add mode.
    owner?.let { member ->
        MemberEditorSheet(
            member = member,
            sheetState = sheetState,
            onDismiss = onClose,
        )
    }
}
