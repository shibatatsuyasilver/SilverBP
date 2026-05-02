package com.silverbp.android.ui.achievements

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.silverbp.android.achievements.MedalKind

/**
 * Circular medal visual: tier-coloured radial gradient + tier rim + icon.
 * When [unlocked] is false the whole badge is desaturated by drawing a
 * neutral gradient and dimming the icon.
 */
@Composable
fun MedalBadge(
    medal: MedalKind,
    unlocked: Boolean,
    modifier: Modifier = Modifier,
    sizeDp: Int = 56,
) {
    val tierColor = medal.tier.color
    val onSurface = MaterialTheme.colorScheme.onSurface
    val brush = if (unlocked) {
        Brush.radialGradient(
            colors = listOf(tierColor.copy(alpha = 0.95f), tierColor.copy(alpha = 0.55f)),
        )
    } else {
        Brush.radialGradient(
            colors = listOf(
                onSurface.copy(alpha = 0.18f),
                onSurface.copy(alpha = 0.08f),
            ),
        )
    }
    val rim = if (unlocked) tierColor.copy(alpha = 0.9f) else Color.Transparent
    val iconTint = if (unlocked) Color.White else onSurface.copy(alpha = 0.6f)

    Box(
        modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(brush)
            .border(width = 2.dp, color = rim, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = medal.icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier
                .size((sizeDp * 0.55f).dp)
                .alpha(if (unlocked) 1f else 0.7f),
        )
    }
}
