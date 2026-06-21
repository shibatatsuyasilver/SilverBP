package com.silverbp.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.MetricAccent
import com.silverbp.android.ui.theme.SilverBpTheme

/* =====================================================================
   ListRow — Material 3 Expressive settings / list rows.

   Mirrors design/mockups/06-settings.html and the .lrow / .switch / .radio
   rules in design/mockups/assets/app.css. The optional leading icon sits in a
   40.dp rounded(12) tile filled with a 20%-tint of its accent (the canonical
   MetricAccent-style "icon tile = one fixed colour" pattern); pass the accent
   via [iconTint]. Rows are >= 48.dp (older-adult tap target) and the whole
   row gets a ripple on tap. Group them with [SettingsGroup], which is a
   [StandardCard] with a titleMedium header and thin outlineVariant dividers.
   ===================================================================== */

/** Leading-icon tile size + corner from .lrow .lico (40px, radius 12). */
private val IconTileSize = 40.dp
private val IconTileCorner = 12.dp
private val IconGlyphSize = 22.dp

/** Inner row padding from .lrow (12 v / 14 h) — keeps the >= 48.dp target. */
private val RowPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)

/** Tinted leading icon tile shared by the nav / switch rows. */
@Composable
private fun ListRowIcon(icon: ImageVector, iconTint: Color?) {
    val tint = iconTint ?: MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(IconTileSize)
            .background(tint.copy(alpha = 0.20f), RoundedCornerShape(IconTileCorner)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(IconGlyphSize),
        )
    }
}

/** Title (bodyLarge SemiBold) + optional subtitle (labelMedium onSurfaceVariant). */
@Composable
private fun RowScopeText(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A tappable navigation row: optional tinted icon tile, title + optional
 * subtitle, trailing chevron. Use inside [SettingsGroup].
 */
@Composable
fun NavListRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AppSpacing.touchTarget)
            .clickable(onClick = onClick)
            .padding(RowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) ListRowIcon(icon, iconTint)
        RowScopeText(title = title, subtitle = subtitle, modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconGlyphSize),
        )
    }
}

/**
 * A row whose whole surface toggles a [Switch]: optional tinted icon tile,
 * title + optional subtitle, trailing switch. Tapping anywhere flips it.
 */
@Composable
fun SwitchListRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AppSpacing.touchTarget)
            // One merged a11y node: TalkBack reads "title, switch, on/off".
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(RowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) ListRowIcon(icon, iconTint)
        RowScopeText(title = title, subtitle = subtitle, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            // Row owns the toggle semantics/click; switch is presentational.
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

/**
 * A single-choice row: title + a leading-end [RadioButton]. Tapping anywhere
 * selects it. No icon tile / subtitle (matches the 外觀 group in the mockup).
 */
@Composable
fun RadioListRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AppSpacing.touchTarget)
            // One merged a11y node: TalkBack reads "title, radio button, selected".
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(RowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        RadioButton(
            selected = selected,
            // Row owns the selection semantics/click; button is presentational.
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

/**
 * A [StandardCard] group of list rows with a titleMedium header. Rows passed in
 * [content] are stacked; insert [SettingsDivider] between rows for the thin
 * outlineVariant separators (.ldiv in the mockup).
 */
@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    StandardCard(
        modifier = modifier,
        title = title,
        contentPadding = 6.dp,
        // Line the heading up with the row labels, not the card edge: rows add a
        // 14.dp inner inset (RowPadding) on top of the 6.dp content padding, so
        // 6 + 14 = 20.dp lands the title flush with them (and matches a plain
        // StandardCard title's inset).
        titleStartPadding = 14.dp,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        content = content,
    )
}

/** Thin inset divider between rows inside a [SettingsGroup] (.ldiv). */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Preview(name = "ListRow · dark", showBackground = true, backgroundColor = 0xFF0E0F13)
@Composable
private fun ListRowPreview() {
    SilverBpTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(AppSpacing.screenH),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            SettingsGroup(title = "外觀") {
                RadioListRow(title = "跟隨系統", selected = false, onClick = {})
                RadioListRow(title = "淺色", selected = false, onClick = {})
                RadioListRow(title = "深色", selected = true, onClick = {})
            }

            SettingsGroup(title = "整合與安全") {
                SwitchListRow(
                    title = "Health Connect",
                    checked = true,
                    onCheckedChange = {},
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    iconTint = MetricAccent.Glucose,
                )
                SettingsDivider()
                NavListRow(
                    title = "管理成員",
                    subtitle = "新增、編輯或移除家庭成員。",
                    onClick = {},
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    iconTint = MetricAccent.Weight,
                )
            }
        }
    }
}
