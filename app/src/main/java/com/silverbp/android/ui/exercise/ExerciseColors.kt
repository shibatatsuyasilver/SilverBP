package com.silverbp.android.ui.exercise

import androidx.compose.ui.graphics.Color
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.ui.coach.ModuleKey

// Per-coach-module ring tints — these identify a lifestyle module (not a metric),
// so they intentionally do NOT come from MetricAccent. Mirror the --mod-* tokens
// in design/mockups/assets/tokens.css.
private val ModExerciseColor = Color(0xFF6C4CF1)    // 運動 — brand purple
private val ModDietColor = Color(0xFF2FB873)        // 飲食 — green
private val ModSleepColor = Color(0xFF3B82F6)       // 睡眠 — blue
private val ModMedicationColor = Color(0xFFEC4899)  // 服藥 — pink

fun colorForModule(key: ModuleKey): Color = when (key) {
    ModuleKey.Exercise -> ModExerciseColor
    ModuleKey.Diet -> ModDietColor
    ModuleKey.Sleep -> ModSleepColor
    ModuleKey.Medication -> ModMedicationColor
}

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
