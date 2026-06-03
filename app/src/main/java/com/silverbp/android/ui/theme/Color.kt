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
