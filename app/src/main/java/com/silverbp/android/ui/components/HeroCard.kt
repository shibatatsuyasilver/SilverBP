package com.silverbp.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.ForgeHeroFrom
import com.silverbp.android.ui.theme.ForgeHeroTo
import com.silverbp.android.ui.theme.HeroShape
import com.silverbp.android.ui.theme.SilverBpTheme

/**
 * The SilverBP "hero" gradient container — the prominent purple block that opens
 * Today (latest BP reading), carries the Coach's task card, and frames Confirm's
 * value display. Mirrors `.hero` in design/mockups/assets/app.css.
 *
 * It is intentionally a *flexible* surface: it owns the gradient background, the
 * rounded clip, the soft purple elevation, the inner padding, and a forced white
 * content colour — but the body is supplied by the caller via [content], so each
 * screen fills it with its own reading / task / value layout.
 *
 * Hero text is white. Use [HeroForeground] for the primary on-hero colour and
 * [HeroForegroundDim] for de-emphasised labels/units; both clear WCAG AA on the
 * [ForgeHeroFrom]/[ForgeHeroTo] gradient. [content] runs in a [ColumnScope] so
 * callers can lay rows out vertically with weight/alignment.
 *
 * Pure UI: no state, no ViewModel coupling. For tappable heroes the caller adds
 * `Modifier.clickable { … }` (with its own semantics) to [modifier].
 */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    shape: Shape = HeroShape,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            // Soft, purple-tinted elevation matching --shadow-hero in the mockup.
            // clip = false so the blurred shadow falls outside the rounded box.
            .shadow(
                elevation = 16.dp,
                shape = shape,
                clip = false,
                ambientColor = ForgeHeroTo,
                spotColor = ForgeHeroFrom,
            )
            .clip(shape)
            .background(Brush.linearGradient(listOf(ForgeHeroFrom, ForgeHeroTo)))
            .padding(AppSpacing.cardPadding),
    ) {
        // Everything inside the hero is white-on-gradient by default.
        CompositionLocalProvider(LocalContentColor provides HeroForeground) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                content()
            }
        }
    }
}

/** Primary on-hero colour — white, passes WCAG AA on both gradient stops. */
val HeroForeground: Color = Color.White

/**
 * De-emphasised on-hero colour for labels, units and separators. White @ 0.90
 * gives ~4.7:1 against the lighter gradient stop [ForgeHeroFrom] (#7350EE), so
 * it clears WCAG AA (4.5:1) for normal text on BOTH gradient stops.
 */
val HeroForegroundDim: Color = Color.White.copy(alpha = 0.90f)

/**
 * The dim label row at the top of a hero (e.g. "血壓 · 08:30"), optionally with a
 * trailing accent — typically a [HeroStatusPill]/category chip aligned to the end.
 * Matches `.hero .hlabel` / `.hrow` in the mockup.
 */
@Composable
fun HeroLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = HeroForegroundDim,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(Modifier.size(AppSpacing.itemGap))
            trailing()
        }
    }
}

/**
 * A translucent white status chip for the hero's label row — a coloured [dotColor]
 * dot plus a short [text] (e.g. the BP category). Mirrors `.statchip` in the mockup.
 * The dot keeps its category colour; the chip fill and text stay white-on-gradient.
 */
@Composable
fun HeroStatusPill(
    text: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.20f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.size(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = HeroForeground,
        )
    }
}

/**
 * The translucent pill at the foot of a BP hero showing the pulse (e.g.
 * "脈搏 72 bpm"), with a leading [icon]. Mirrors `.hero .pulse` in the mockup.
 * Defaults to a heart so callers usually pass only the text.
 */
@Composable
fun HeroPulsePill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Favorite,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = HeroForeground,
        )
        Spacer(Modifier.size(7.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = HeroForeground,
        )
    }
}

@Preview(name = "HeroCard · BP (dark)", showBackground = true, backgroundColor = 0xFF0E0F13)
@Composable
private fun HeroCardBpPreview() {
    SilverBpTheme {
        Box(modifier = Modifier.padding(AppSpacing.screenH)) {
            HeroCard {
                HeroLabel(
                    text = "血壓 · 08:30",
                    trailing = {
                        HeroStatusPill(
                            text = "正常",
                            dotColor = colorFor(com.silverbp.android.core.BpCategory.Normal),
                        )
                    },
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BpReadingValue(
                        systolic = 120,
                        diastolic = 80,
                        sbpColor = HeroForeground,
                        dbpColor = HeroForeground,
                        separatorColor = HeroForegroundDim,
                    )
                    Text(
                        text = "mmHg",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HeroForegroundDim,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                HeroPulsePill(text = "脈搏 72 bpm")
            }
        }
    }
}
