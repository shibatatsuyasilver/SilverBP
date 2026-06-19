package com.silverbp.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.silverbp.android.ui.theme.AppMotion
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.PillShape
import com.silverbp.android.ui.theme.SilverBpTheme

/**
 * SilverBP M3 Expressive chips — the small pill affordances used for range/type
 * filters on Insights and the tappable suggestion chips under the chat composer.
 *
 * Two variants, both fully-rounded pills with a 38.dp visible height but a 48.dp
 * interactive/touch target (via [minimumInteractiveComponentSize]) for the
 * older-adult audience, with a subtle scale-down press feedback driven by
 * [AppMotion.springSnappy] (chips/toggles):
 *
 * - [ExpressiveFilterChip] — a toggle. Selected = secondaryContainer fill with
 *   SemiBold onSecondaryContainer text and no border; unselected =
 *   surfaceContainerHigh fill with a 1px outlineVariant hairline border. An
 *   optional [leadingDotColor] draws a 9.dp colour dot (e.g. a BP-category tint
 *   on a type chip).
 * - [ExpressiveAssistChip] — a non-toggling action chip (chat suggestions):
 *   surfaceContainerHigh fill + 1px outlineVariant border, onSurface text.
 *
 * Mirrors `.chip` / `.chip.sel` / `.chip.assist` in
 * design/mockups/assets/app.css. Pure UI — no state ownership.
 */
@Composable
fun ExpressiveFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingDotColor: Color? = null,
) {
    val background =
        if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurface

    ChipSurface(
        onClick = onClick,
        background = background,
        // .chip.sel { box-shadow:none } — the selected fill carries the chip on
        // its own; only the unselected/idle state gets the hairline border.
        showBorder = !selected,
        // Toggle chip: expose on/off so TalkBack reads "<label>, button, selected".
        selected = selected,
        modifier = modifier,
    ) {
        if (leadingDotColor != null) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(leadingDotColor)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
        )
    }
}

@Composable
fun ExpressiveAssistChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChipSurface(
        onClick = onClick,
        background = MaterialTheme.colorScheme.surfaceContainerHigh,
        showBorder = true,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Shared pill scaffold for both chip variants: a fully-rounded, 38.dp-tall
 * clickable surface (with a 48.dp interactive touch target) plus an optional
 * hairline border and the snappy press scale-down
 * (`.chip:active { transform: scale(0.95) }`).
 */
@Composable
private fun ChipSurface(
    onClick: () -> Unit,
    background: Color,
    showBorder: Boolean,
    modifier: Modifier = Modifier,
    // null = non-toggling action chip (assist); non-null = toggle chip whose
    // on/off state is exposed to accessibility services.
    selected: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = AppMotion.springSnappy(),
        label = "chipPressScale",
    )

    val border = Modifier.takeIf { showBorder }?.border(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        shape = PillShape,
    ) ?: Modifier

    Row(
        modifier = modifier
            // Expand the touch/clickable region to the 48.dp interactive minimum
            // (older-adult audience / Material) while the visible pill stays 38.dp.
            .minimumInteractiveComponentSize()
            .scale(scale)
            .defaultMinSize(minHeight = 38.dp)
            .clip(PillShape)
            .background(background, PillShape)
            .then(border)
            // Press feedback is the [scale] morph above (driven by this
            // interactionSource), matching MetricCard — no ripple overlay.
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                role = Role.Button,
            )
            // Announce the toggle on/off state for screen-reader users.
            .semantics { if (selected != null) this.selected = selected }
            .padding(horizontal = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = { content() },
    )
}

@Preview(name = "ExpressiveChip — dark", showBackground = true)
@Composable
private fun ExpressiveChipPreview() {
    SilverBpTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(AppSpacing.screenH),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                ExpressiveFilterChip(label = "7 天", selected = true, onClick = {})
                ExpressiveFilterChip(label = "30 天", selected = false, onClick = {})
                ExpressiveFilterChip(label = "90 天", selected = false, onClick = {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                ExpressiveFilterChip(
                    label = "偏高",
                    selected = true,
                    onClick = {},
                    leadingDotColor = MaterialTheme.colorScheme.tertiary,
                )
                ExpressiveFilterChip(
                    label = "正常",
                    selected = false,
                    onClick = {},
                    leadingDotColor = MaterialTheme.colorScheme.secondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                ExpressiveAssistChip(label = "我的血壓正常嗎?", onClick = {})
                ExpressiveAssistChip(label = "怎麼降鈉?", onClick = {})
            }
        }
    }
}
