package com.silverbp.android.ui.theme

import androidx.compose.ui.graphics.Color

// Brand
val Primary = Color(0xFF1F6FEB)
val PrimaryDark = Color(0xFF1453B8)
val Secondary = Color(0xFF34C759)

// FORGE dark brand palette (dark-first; default app theme)
val ForgeBackground = Color(0xFF0E0F13)
val ForgeSurface = Color(0xFF1A1C22)
val ForgeSurfaceVariant = Color(0xFF242730)
val ForgePrimary = Color(0xFF6C4CF1)            // purple
val ForgeOnPrimary = Color(0xFFFFFFFF)
val ForgeSecondary = Color(0xFFC2F24A)          // lime/chartreuse CTA accent
val ForgeOnSecondary = Color(0xFF10131A)
val ForgeOnSurface = Color(0xFFECEEF3)
val ForgeOnSurfaceVariant = Color(0xFFB6BAC6)
val ForgeOutline = Color(0xFF3A3E4A)

// FORGE light palette (light mode; brand purple accent preserved). Neutrals are
// faintly purple-tinted — derived from a Google Stitch Material-3 tonal_spot
// system seeded with ForgePrimary — so the light theme keeps a warm brand feel
// instead of a flat cool grey. The page (background) is one step deeper than the
// white card surface so StandardCard (containerColor = surface) lifts off it.
val ForgeLightBackground = Color(0xFFF3F0F8)            // page background
val ForgeLightSurface = Color(0xFFFFFFFF)              // cards
val ForgeLightSurfaceVariant = Color(0xFFE6E0ED)       // chips / inner fills / empty cells
val ForgeLightSurfaceContainer = Color(0xFFF7F2FA)
val ForgeLightSurfaceContainerHigh = Color(0xFFEBE6F1)
val ForgeLightPrimary = Color(0xFF6C4CF1)              // SAME brand purple
val ForgeLightOnPrimary = Color(0xFFFFFFFF)
val ForgeLightPrimaryContainer = Color(0xFFE7DEFF)
val ForgeLightOnPrimaryContainer = Color(0xFF21005D)
val ForgeLightSecondary = Color(0xFF186C4B)            // deep readable green (lime is unreadable on white)
val ForgeLightOnSecondary = Color(0xFFFFFFFF)
val ForgeLightSecondaryContainer = Color(0xFFA4F3CA)
val ForgeLightOnSecondaryContainer = Color(0xFF00513A)
val ForgeLightOnBackground = Color(0xFF211F27)         // near-black text
val ForgeLightOnSurface = Color(0xFF211F27)
val ForgeLightOnSurfaceVariant = Color(0xFF605E68)     // muted grey for secondary text
val ForgeLightOutline = Color(0xFF7C7984)
val ForgeLightOutlineVariant = Color(0xFFC4C0CC)
val ForgeLightError = Color(0xFFB3261E)
val ForgeLightOnError = Color(0xFFFFFFFF)

// BP categories (match iOS palette)
val CategoryNormal = Color(0xFF34C759)         // green
val CategoryElevated = Color(0xFFFFCC00)       // yellow
val CategoryStage1 = Color(0xFFFF9500)         // orange
val CategoryStage2 = Color(0xFFFF3B30)         // red
val CategoryCrisis = Color(0xFFAF52DE)         // purple
val CategoryHypotension = Color(0xFF007AFF)    // blue

// Trend lines
val SbpLine = Color(0xFFFF3B30)
val DbpLine = Color(0xFF007AFF)
val NormalZone = Color(0xFF34C759)

// Stable BP-value tint used by the Today large-reading card. Distinct from
// MaterialTheme.colorScheme.error so dynamic-color (Material You) on Pixel
// devices doesn't shift the SBP hue away from iOS's red.
val BpRedSbp = Color(0xFFE5484D)
