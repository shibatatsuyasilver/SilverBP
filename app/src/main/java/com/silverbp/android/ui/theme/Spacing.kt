package com.silverbp.android.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Shared spacing / shape scale for the Stitch-aligned UI polish.
 *
 * Screens reference these named tokens instead of scattering magic dp values,
 * so padding, gaps, and corner radii stay consistent across the app. Values
 * mirror the Google Stitch mockups (generous spacing + rounded cards tuned for
 * older-adult legibility). UI-only — no behavioural meaning.
 */
object AppSpacing {
    /** Horizontal padding from the screen edge to content. */
    val screenH = 16.dp

    /** Vertical padding at the top/bottom of a screen's scroll content. */
    val screenV = 16.dp

    /** Gap between stacked section cards / major blocks. */
    val sectionGap = 16.dp

    /** Gap between rows/items inside a card. */
    val itemGap = 8.dp

    /** Tight gap (icon↔label, dot↔text). */
    val tight = 4.dp

    /** Inner padding of a [com.silverbp.android.ui.components.StandardCard]. */
    val cardPadding = 20.dp

    /** Corner radius of a standard rounded card. */
    val cardCorner = 20.dp

    /** Corner radius of a prominent "hero" card (e.g. Today's latest reading). */
    val heroCorner = 24.dp
}
