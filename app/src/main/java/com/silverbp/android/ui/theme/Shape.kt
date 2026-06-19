package com.silverbp.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive shape scale — single source of truth for corner radii so
 * the scattered 14/16/20/24 values converge. Mirrors design/mockups/assets/tokens.css.
 *
 * Mapping: chips / small fills -> small; standard cards -> medium; prominent
 * cards and bottom sheets -> large; hero / marketing surfaces -> extraLarge.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Fully-rounded pill — buttons, chips, segmented controls, badges. */
val PillShape = RoundedCornerShape(percent = 50)

/** Hero / latest-reading gradient card. */
val HeroShape = RoundedCornerShape(28.dp)
