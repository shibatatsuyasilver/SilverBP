package com.silverbp.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.CategoryNormal

/**
 * An Apple Health-style "log it" toggle: a tappable circle that is an outlined
 * ring when unchecked and a filled green circle with a white check when checked.
 *
 * The hit area is a full [AppSpacing.touchTarget] (48.dp) for older-adult
 * reachability while the visible glyph stays a compact 30.dp. Used by the Today
 * medication card and the daily medication-log screen so "mark as taken" looks
 * and behaves identically wherever a dose is logged.
 *
 * [contentDescription] should name the dose and convey the action/state (e.g.
 * "Amlodipine, mark as taken"). Pure UI: no internal state — the caller owns
 * [checked] and reacts to [onCheckedChange].
 */
@Composable
fun CheckCircle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(AppSpacing.touchTarget)
            .clip(CircleShape)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(CategoryNormal),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        }
    }
}
