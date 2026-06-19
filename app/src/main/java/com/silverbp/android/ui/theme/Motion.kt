package com.silverbp.android.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * Named spring specs for the app's own animations (press shape-morph, size/clip
 * changes). MaterialExpressiveTheme already gives stock components spring defaults
 * via MotionScheme.expressive(); these cover bespoke animations in ui/components.
 *
 * Senior-friendly: bounce is kept subtle and short — large overshoots read as
 * "broken". [springBouncy] is reserved for hero / FAB accents only.
 */
object AppMotion {
    /** Default, near-critically-damped — most state and layout changes. */
    fun <T> springDefault(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)

    /** Gentle overshoot — hero / FAB emphasis only. */
    fun <T> springBouncy(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)

    /** Snappy — chips, toggles, small selections. */
    fun <T> springSnappy(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessHigh)
}
