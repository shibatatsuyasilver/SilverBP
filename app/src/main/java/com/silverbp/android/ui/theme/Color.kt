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
val ForgeOutlineVariant = Color(0xFF2A2D36)

// Expressive container/tertiary roles the dark scheme was missing — so vivid
// blocks (chips, selected states, badges, banners) stop falling back to muted
// M3 defaults. Principle: big blocks use *container* roles; saturated
// primary/secondary stay for CTAs / FAB / selected / hero.
val ForgePrimaryContainer = Color(0xFF3A2E78)
val ForgeOnPrimaryContainer = Color(0xFFE7DEFF)
val ForgeSecondaryContainer = Color(0xFF2E3B12)     // dim lime fill
val ForgeOnSecondaryContainer = Color(0xFFDDF5A8)   // bright lime-on (AA)
val ForgeTertiary = Color(0xFFF5C84B)               // gold = tertiary (premium)
val ForgeOnTertiary = Color(0xFF2A1F00)
val ForgeTertiaryContainer = Color(0xFF5A4600)
val ForgeOnTertiaryContainer = Color(0xFFFFE08A)

// Surface tonal ramp (M3 surfaceContainer* roles), low -> high elevation.
val ForgeSurfaceContainerLowest = Color(0xFF0B0C10)
val ForgeSurfaceContainerLow = Color(0xFF1A1C22)
val ForgeSurfaceContainer = Color(0xFF1F2128)
val ForgeSurfaceContainerHigh = Color(0xFF25272F)
val ForgeSurfaceContainerHighest = Color(0xFF2A2D36)

// Vivid error (distinct from the BpRedSbp value tint). Passes AA on dark surfaces.
val ForgeError = Color(0xFFFF5247)
val ForgeOnError = Color(0xFFFFFFFF)
val ForgeErrorContainer = Color(0xFF5C1410)
val ForgeOnErrorContainer = Color(0xFFFFD9D5)

// Premium accent — a warm gold for the subscription crown (Today entry + the
// PremiumScreen hero). Distinct from the lime CTA and the yellow "elevated" BP dot
// so the crown reads as "premium", not a warning. Legible on the dark canvas.
val PremiumGold = Color(0xFFF5C84B)

// Hero / latest-reading gradient (Today, Coach task, Confirm). White text passes
// WCAG AA on both stops (lighter stop darkened #7C5CFF -> #7350EE so small labels
// on the gradient clear 4.5:1). Light theme uses the same vivid pair.
val ForgeHeroFrom = Color(0xFF7350EE)
val ForgeHeroTo = Color(0xFF4A2EC0)
val ForgeLightHeroFrom = Color(0xFF7350EE)
val ForgeLightHeroTo = Color(0xFF5B3FE0)

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
val ForgeLightErrorContainer = Color(0xFFFFDAD6)
val ForgeLightOnErrorContainer = Color(0xFF410002)
val ForgeLightTertiary = Color(0xFF8A6D00)             // readable gold on white
val ForgeLightOnTertiary = Color(0xFFFFFFFF)
val ForgeLightTertiaryContainer = Color(0xFFFFE08A)
val ForgeLightOnTertiaryContainer = Color(0xFF2A1F00)
val ForgeLightSurfaceContainerLowest = Color(0xFFFFFFFF)
val ForgeLightSurfaceContainerLow = Color(0xFFF7F2FA)
val ForgeLightSurfaceContainerHighest = Color(0xFFE6E0EE)

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

// Canonical per-metric ICON ACCENT — the fixed brand tint for a metric's icon
// tile, the SAME on every screen, regardless of the reading. This is NOT the
// reading's category status colour (that varies); it identifies the metric, so
// a glucose drop is always teal, a BP heart always red, a weight scale always
// indigo. Apply via MetricAccent everywhere a metric icon tile is drawn
// (Today cards, history rows, capture/confirm, etc.). Mirrors --accent-* in
// design/mockups/assets/tokens.css.
object MetricAccent {
    val Bp = Color(0xFFFF453A)        // 血壓 — heart
    val Glucose = Color(0xFF15B5B0)   // 血糖 — water drop
    val Weight = Color(0xFF5C6BC0)    // 體重 — scale
}
