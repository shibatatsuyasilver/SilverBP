package com.silverbp.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.chat.ChatRepository
import com.silverbp.android.chat.ChatSessionSummary
import com.silverbp.android.ui.theme.AppSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Wraps [content] in a [ModalNavigationDrawer] showing a list of chat
 * sessions. The drawer overlays the existing Scaffold + bottom NavigationBar
 * with Material's standard scrim — no nesting issues.
 */
@Composable
fun ChatSessionsDrawer(
    sessions: List<ChatSessionSummary>,
    activeSessionId: String?,
    drawerState: DrawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
    onNewSession: () -> Unit,
    onSelectSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    var renameTarget by remember { mutableStateOf<ChatSessionSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSessionSummary?>(null) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxHeight()) {
                DrawerHeader(onNewSession = onNewSession)
                HorizontalDivider()
                if (sessions.isEmpty()) {
                    EmptySessionsHint()
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            horizontal = AppSpacing.screenH,
                            vertical = AppSpacing.screenV,
                        ),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
                    ) {
                        items(sessions, key = { it.id }) { s ->
                            SessionRow(
                                session = s,
                                isActive = s.id == activeSessionId,
                                onSelect = { onSelectSession(s.id) },
                                onRequestRename = { renameTarget = s },
                                onRequestDelete = { deleteTarget = s },
                            )
                        }
                    }
                }
            }
        },
        content = content,
    )

    renameTarget?.let { target ->
        RenameSessionDialog(
            current = target,
            onDismiss = { renameTarget = null },
            onConfirm = { newTitle ->
                onRenameSession(target.id, newTitle)
                renameTarget = null
            },
        )
    }
    deleteTarget?.let { target ->
        DeleteSessionDialog(
            current = target,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                onDeleteSession(target.id)
                deleteTarget = null
            },
        )
    }
}

@Composable
private fun DrawerHeader(onNewSession: () -> Unit) {
    Column(
        Modifier.padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        Text(
            stringResource(R.string.chat_drawer_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        // Clear primary "new chat" action — a filled primary-tinted row mirroring
        // the refined card family's leading-icon-tile idiom.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppSpacing.cardCorner))
                .clickable(onClick = onNewSession),
            shape = RoundedCornerShape(AppSpacing.cardCorner),
            color = MaterialTheme.colorScheme.primary,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = AppSpacing.cardPadding, vertical = AppSpacing.itemGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(AppSpacing.itemGap))
                Text(
                    stringResource(R.string.chat_new_session),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun EmptySessionsHint() {
    Box(
        Modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.chat_drawer_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionRow(
    session: ChatSessionSummary,
    isActive: Boolean,
    onSelect: () -> Unit,
    onRequestRename: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val defaultTitle = stringResource(R.string.chat_default_session_title)
    val displayTitle = session.title
        .ifBlank { defaultTitle }
        .let { if (it in ChatRepository.DEFAULT_TITLES) defaultTitle else it }
    val snippet = session.lastSnippet?.let { firstLine(it).take(36) }
    val subtitle = buildString {
        append(formatRelative(session.updatedAt, context = context))
        if (!snippet.isNullOrBlank()) {
            append(" · ")
            append(snippet)
        }
    }
    // Clean surface list-row card matching the refined card family
    // (Today / UnifiedHistory TimelineRecordRow): a leading tinted chat-icon tile,
    // the title + subtitle, and a trailing options menu. The active session is
    // lifted onto a primary-tinted container so it reads as selected.
    val tileTint = MaterialTheme.colorScheme.primary
    val containerColor =
        if (isActive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface
    val onContainer =
        if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppSpacing.cardCorner),
        color = containerColor,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 74.dp)
                .clickable(onClick = onSelect)
                .padding(
                    start = AppSpacing.cardPadding,
                    end = AppSpacing.itemGap,
                    top = AppSpacing.itemGap,
                    bottom = AppSpacing.itemGap,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // LEADING: tinted chat-icon tile.
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tileTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    tint = tileTint,
                    modifier = Modifier.size(26.dp),
                )
            }

            Spacer(Modifier.size(AppSpacing.screenH))

            // MIDDLE: title over the relative-time + snippet subtitle.
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.tight / 2),
            ) {
                Text(
                    displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // TRAILING: options menu (rename / delete).
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.chat_options_a11y),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_rename)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRequestRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRequestDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameSessionDialog(
    current: ChatSessionSummary,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val defaultTitle = stringResource(R.string.chat_default_session_title)
    var input by remember(current.id) { mutableStateOf(current.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_rename_dialog_title)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(40) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(defaultTitle) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(input.trim().ifBlank { defaultTitle }) },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun DeleteSessionDialog(
    current: ChatSessionSummary,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val defaultTitle = stringResource(R.string.chat_default_session_title)
    val title = current.title
        .ifBlank { defaultTitle }
        .let { if (it in ChatRepository.DEFAULT_TITLES) defaultTitle else it }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_delete_dialog_title)) },
        text = { Text(stringResource(R.string.chat_delete_dialog_message, title)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// Pattern is locale-neutral; Locale.getDefault() honours per-app language overrides.
private val TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.getDefault())

/** Drawer subtitle: "just now" / "N min ago" / "N hr ago" / fall back to MM-dd HH:mm. */
private fun formatRelative(
    t: Instant,
    context: android.content.Context,
    now: Instant = Instant.now(),
): String {
    val mins = ChronoUnit.MINUTES.between(t, now).coerceAtLeast(0)
    return when {
        mins < 1 -> context.getString(R.string.chat_time_just_now)
        mins < 60 -> context.getString(R.string.chat_time_minutes_ago, mins)
        mins < 60 * 24 -> context.getString(R.string.chat_time_hours_ago, mins / 60)
        else -> TIME_FMT.withZone(ZoneId.systemDefault()).format(t)
    }
}

/** First non-blank line of a message, used as the snippet preview. */
private fun firstLine(s: String): String =
    s.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
