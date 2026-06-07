package com.silverbp.android.ui.exercise

import androidx.compose.ui.graphics.Color
import com.silverbp.android.exercise.ActivityKind

private val WalkingColor = Color(0xFF2E7D32)  // green 800
private val RunningColor = Color(0xFFD32F2F)  // red 700
private val BriskWalkingColor = Color(0xFF00897B)  // teal 600
private val CyclingColor = Color(0xFF0288D1)  // light blue 700
private val TreadmillColor = Color(0xFFEF6C00)  // orange 800
private val IndoorBikeColor = Color(0xFF5E35B1)  // deep purple 600
private val EllipticalColor = Color(0xFF00838F)  // cyan 800
private val RowerColor = Color(0xFF3949AB)  // indigo 600
private val StairClimberColor = Color(0xFFAD1457)  // pink 800

fun colorForKind(kind: ActivityKind): Color = when (kind) {
    ActivityKind.Walking -> WalkingColor
    ActivityKind.Running -> RunningColor
    ActivityKind.BriskWalking -> BriskWalkingColor
    ActivityKind.Cycling -> CyclingColor
    ActivityKind.Treadmill -> TreadmillColor
    ActivityKind.IndoorBike -> IndoorBikeColor
    ActivityKind.Elliptical -> EllipticalColor
    ActivityKind.Rower -> RowerColor
    ActivityKind.StairClimber -> StairClimberColor
}
