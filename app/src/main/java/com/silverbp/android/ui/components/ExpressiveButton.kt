package com.silverbp.android.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.silverbp.android.ui.theme.AppMotion
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.SilverBpTheme

/** Older-adult-friendly CTA height; the pill's rest radius is [MinHeight] / 2. */
private val MinHeight: Dp = 56.dp

/** Squarer corner the pill morphs toward on press (= AppShapes.medium / --r-md, 20dp). */
private val PressedRadius: Dp = 20.dp

/**
 * Material 3 Expressive CTA buttons — the app's primary call-to-action surface.
 *
 * Mirrors `.btn.primary` / `.btn.lime` in design/mockups/assets/app.css: a
 * 56dp-tall pill, [labelLarge] text, optional 20dp leading icon. The signature
 * Expressive signal is the PRESS SHAPE-MORPH — on press the corner animates from
 * a full [PillShape] (50%) toward [MaterialTheme.shapes.large] (28dp) and the
 * whole button scales to ~0.96, both driven by [AppMotion.springSnappy] so the
 * feedback is lively but senior-friendly (no large overshoot).
 *
 * Two flavours share one implementation:
 * - [ExpressivePrimaryButton] — `colorScheme.primary` / `onPrimary` (brand purple).
 * - [ExpressiveSecondaryButton] — `colorScheme.secondary` / `onSecondary`
 *   (lime-on-dark, deep-green-on-light; both already wired into the scheme).
 *
 * Pure UI: no state, no ViewModel coupling.
 */
@Composable
fun ExpressivePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
) {
    ExpressiveButton(
        text = text,
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier,
        icon = icon,
        enabled = enabled,
        fillWidth = fillWidth,
    )
}

@Composable
fun ExpressiveSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
) {
    ExpressiveButton(
        text = text,
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
        modifier = modifier,
        icon = icon,
        enabled = enabled,
        fillWidth = fillWidth,
    )
}

/**
 * Shared body for the two Expressive CTA flavours — handles the press-driven
 * shape-morph + scale so the public composables only differ by their colour roles.
 */
@Composable
private fun ExpressiveButton(
    text: String,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier,
    icon: ImageVector?,
    enabled: Boolean,
    fillWidth: Boolean,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // PRESS SHAPE-MORPH — the key Expressive signal. At rest the button is a full
    // pill: at the 56dp [MinHeight] the 50% corner resolves to a 28dp radius
    // (= MinHeight / 2 = shapes.large). On press the corner snaps toward the squarer
    // [PressedRadius] (20dp = shapes.medium / --r-md), making the squircle morph
    // clearly visible — while the whole button scales to 0.96, mirroring the mockup's
    // `.btn:active { transform: scale(0.96); border-radius: var(--r-md); }`.
    // Both run on AppMotion.springSnappy(): lively, but no overshoot (senior-safe).
    val cornerRadius by animateDpAsState(
        targetValue = if (pressed) PressedRadius else MinHeight / 2,
        animationSpec = AppMotion.springSnappy(),
        label = "ExpressiveButtonCorner",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = AppMotion.springSnappy(),
        label = "ExpressiveButtonScale",
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(cornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        contentPadding = PaddingValues(horizontal = 26.dp),
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = MinHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Preview(name = "ExpressiveButton — Dark", showBackground = true, backgroundColor = 0xFF0E0F13)
@Composable
private fun ExpressiveButtonPreview() {
    SilverBpTheme {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ExpressivePrimaryButton(
                text = "記錄血壓",
                onClick = {},
                icon = Icons.AutoMirrored.Filled.ArrowForward,
            )
            ExpressiveSecondaryButton(
                text = "開始",
                onClick = {},
                icon = Icons.Filled.Bolt,
            )
        }
    }
}
