package com.silverbp.android.ui.exercise

import androidx.compose.ui.graphics.Color
import com.silverbp.android.exercise.ActivityKind

private val WalkingColor = Color(0xFF2E7D32)  // green 800
private val RunningColor = Color(0xFFD32F2F)  // red 700

fun colorForKind(kind: ActivityKind): Color = when (kind) {
    ActivityKind.Walking -> WalkingColor
    ActivityKind.Running -> RunningColor
}
