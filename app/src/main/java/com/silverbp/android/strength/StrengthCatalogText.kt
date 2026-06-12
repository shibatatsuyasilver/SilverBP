package com.silverbp.android.strength

import android.content.Context
import com.silverbp.android.R
import com.silverbp.android.di.ServiceLocator

/**
 * Localized display text for the seeded strength catalog, keyed off the stable
 * exercise [ExerciseCatalogItem.id] and the canonical (Chinese) muscle-group
 * terms stored in the DB.
 *
 * The DB stores the seed's original strings verbatim (stable, synced bytes);
 * display always resolves through here so exercise names, descriptions, and
 * muscle chips follow the current app language. Items / muscle terms not in
 * these maps (e.g. a future user-added move) fall back to their stored text.
 */
object StrengthCatalogText {

    /** id -> (name, description) string resources. */
    private val nameDescById: Map<String, Pair<Int, Int>> = mapOf(
        "st_barbell_bench_press" to (R.string.st_barbell_bench_press_name to R.string.st_barbell_bench_press_desc),
        "st_dumbbell_bench_press" to (R.string.st_dumbbell_bench_press_name to R.string.st_dumbbell_bench_press_desc),
        "st_incline_dumbbell_press" to (R.string.st_incline_dumbbell_press_name to R.string.st_incline_dumbbell_press_desc),
        "st_dumbbell_shoulder_press" to (R.string.st_dumbbell_shoulder_press_name to R.string.st_dumbbell_shoulder_press_desc),
        "st_lateral_raise" to (R.string.st_lateral_raise_name to R.string.st_lateral_raise_desc),
        "st_lat_pulldown" to (R.string.st_lat_pulldown_name to R.string.st_lat_pulldown_desc),
        "st_seated_row" to (R.string.st_seated_row_name to R.string.st_seated_row_desc),
        "st_bent_over_row" to (R.string.st_bent_over_row_name to R.string.st_bent_over_row_desc),
        "st_face_pull" to (R.string.st_face_pull_name to R.string.st_face_pull_desc),
        "st_biceps_curl" to (R.string.st_biceps_curl_name to R.string.st_biceps_curl_desc),
        "st_hammer_curl" to (R.string.st_hammer_curl_name to R.string.st_hammer_curl_desc),
        "st_triceps_pushdown" to (R.string.st_triceps_pushdown_name to R.string.st_triceps_pushdown_desc),
        "st_overhead_triceps_ext" to (R.string.st_overhead_triceps_ext_name to R.string.st_overhead_triceps_ext_desc),
        "st_chest_fly" to (R.string.st_chest_fly_name to R.string.st_chest_fly_desc),
        "st_barbell_squat" to (R.string.st_barbell_squat_name to R.string.st_barbell_squat_desc),
        "st_goblet_squat" to (R.string.st_goblet_squat_name to R.string.st_goblet_squat_desc),
        "st_deadlift" to (R.string.st_deadlift_name to R.string.st_deadlift_desc),
        "st_romanian_deadlift" to (R.string.st_romanian_deadlift_name to R.string.st_romanian_deadlift_desc),
        "st_leg_press" to (R.string.st_leg_press_name to R.string.st_leg_press_desc),
        "st_walking_lunge" to (R.string.st_walking_lunge_name to R.string.st_walking_lunge_desc),
        "st_bulgarian_split_squat" to (R.string.st_bulgarian_split_squat_name to R.string.st_bulgarian_split_squat_desc),
        "st_leg_curl" to (R.string.st_leg_curl_name to R.string.st_leg_curl_desc),
        "st_leg_extension" to (R.string.st_leg_extension_name to R.string.st_leg_extension_desc),
        "st_calf_raise" to (R.string.st_calf_raise_name to R.string.st_calf_raise_desc),
        "st_glute_bridge" to (R.string.st_glute_bridge_name to R.string.st_glute_bridge_desc),
        "st_hip_thrust" to (R.string.st_hip_thrust_name to R.string.st_hip_thrust_desc),
        "st_plank" to (R.string.st_plank_name to R.string.st_plank_desc),
        "st_side_plank" to (R.string.st_side_plank_name to R.string.st_side_plank_desc),
        "st_crunch" to (R.string.st_crunch_name to R.string.st_crunch_desc),
        "st_russian_twist" to (R.string.st_russian_twist_name to R.string.st_russian_twist_desc),
        "st_dead_bug" to (R.string.st_dead_bug_name to R.string.st_dead_bug_desc),
        "st_hanging_leg_raise" to (R.string.st_hanging_leg_raise_name to R.string.st_hanging_leg_raise_desc),
        "st_pull_up" to (R.string.st_pull_up_name to R.string.st_pull_up_desc),
        "st_push_up" to (R.string.st_push_up_name to R.string.st_push_up_desc),
        "st_burpee" to (R.string.st_burpee_name to R.string.st_burpee_desc),
        "st_kettlebell_swing" to (R.string.st_kettlebell_swing_name to R.string.st_kettlebell_swing_desc),
    )

    /** Canonical (Chinese) muscle term -> string resource. */
    private val muscleResByTerm: Map<String, Int> = mapOf(
        "胸部" to R.string.muscle_chest,
        "三頭肌" to R.string.muscle_triceps,
        "肩膀" to R.string.muscle_shoulders,
        "上胸" to R.string.muscle_upper_chest,
        "三角肌中束" to R.string.muscle_lateral_delts,
        "背闊肌" to R.string.muscle_lats,
        "二頭肌" to R.string.muscle_biceps,
        "背部" to R.string.muscle_back,
        "後三角肌" to R.string.muscle_rear_delts,
        "上背" to R.string.muscle_upper_back,
        "前臂" to R.string.muscle_forearms,
        "股四頭肌" to R.string.muscle_quads,
        "臀部" to R.string.muscle_glutes,
        "核心" to R.string.muscle_core,
        "腿後肌" to R.string.muscle_hamstrings,
        "下背" to R.string.muscle_lower_back,
        "小腿" to R.string.muscle_calves,
        "腹部" to R.string.muscle_abs,
        "腹斜肌" to R.string.muscle_obliques,
        "腹直肌" to R.string.muscle_rectus_abdominis,
        "下腹" to R.string.muscle_lower_abs,
        "髖屈肌" to R.string.muscle_hip_flexors,
        "心肺" to R.string.muscle_cardio,
    )

    fun localizedName(id: String, fallback: String, context: Context = ServiceLocator.context): String =
        nameDescById[id]?.let { context.getString(it.first) } ?: fallback

    fun localizedDescription(id: String, fallback: String, context: Context = ServiceLocator.context): String =
        nameDescById[id]?.let { context.getString(it.second) } ?: fallback

    fun localizedMuscle(term: String, context: Context = ServiceLocator.context): String =
        muscleResByTerm[term]?.let { context.getString(it) } ?: term
}

/**
 * Display-layer localization for a catalog item: resolves name / description /
 * muscle chips to the current app language off the stable id + canonical terms.
 * Applied where items surface for display (repositories / UI), keeping the
 * DB↔domain mapper a pure, Context-free identity. Unknown ids/terms keep their
 * stored text.
 */
fun ExerciseCatalogItem.localized(context: Context = ServiceLocator.context): ExerciseCatalogItem = copy(
    name = StrengthCatalogText.localizedName(id, name, context),
    muscleGroups = muscleGroups.map { StrengthCatalogText.localizedMuscle(it, context) },
    description = StrengthCatalogText.localizedDescription(id, description, context),
)
