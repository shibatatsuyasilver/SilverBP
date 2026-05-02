package com.silverbp.android.achievements

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.silverbp.android.R

/** Coarse grouping of medals — drives [MedalsScreen] section layout. */
enum class MedalCategory(val raw: String, val titleRes: Int) {
    DailySteps("daily", R.string.medal_section_daily),
    Cumulative("cumulative", R.string.medal_section_cumulative),
    Streak("streak", R.string.medal_section_streak),
    Session("session", R.string.medal_section_session),
}

/** Ascending difficulty rank, used to pick badge colors and rim. */
enum class MedalTier(val color: Color) {
    Bronze(Color(0xFFCD7F32)),
    Silver(Color(0xFFB8B8B8)),
    Gold(Color(0xFFE6B422)),
    Platinum(Color(0xFF7FB1C9)),
    Diamond(Color(0xFF59C7E1)),
}

/**
 * One concrete medal. [kindRaw] is the stable id used as DB primary key
 * (`"<category>.<threshold>"`) and must never change between releases.
 *
 * [threshold] is a long because cumulative-step medals reach into millions
 * (5,000,000 fits in Int, but keeping all categories on one type avoids
 * accidental Int-overflow surprises in arithmetic).
 */
enum class MedalKind(
    val category: MedalCategory,
    val threshold: Long,
    val tier: MedalTier,
    val displayNameRes: Int,
    val icon: ImageVector,
) {
    DailySteps5k(
        MedalCategory.DailySteps, 5_000L, MedalTier.Bronze,
        R.string.medal_daily_5k, Icons.AutoMirrored.Filled.DirectionsWalk,
    ),
    DailySteps8k(
        MedalCategory.DailySteps, 8_000L, MedalTier.Silver,
        R.string.medal_daily_8k, Icons.AutoMirrored.Filled.DirectionsWalk,
    ),
    DailySteps10k(
        MedalCategory.DailySteps, 10_000L, MedalTier.Gold,
        R.string.medal_daily_10k, Icons.AutoMirrored.Filled.DirectionsWalk,
    ),
    DailySteps15k(
        MedalCategory.DailySteps, 15_000L, MedalTier.Platinum,
        R.string.medal_daily_15k, Icons.AutoMirrored.Filled.DirectionsRun,
    ),
    DailySteps20k(
        MedalCategory.DailySteps, 20_000L, MedalTier.Diamond,
        R.string.medal_daily_20k, Icons.Filled.EmojiEvents,
    ),
    Cumulative100k(
        MedalCategory.Cumulative, 100_000L, MedalTier.Bronze,
        R.string.medal_cumulative_100k, Icons.AutoMirrored.Filled.DirectionsWalk,
    ),
    Cumulative500k(
        MedalCategory.Cumulative, 500_000L, MedalTier.Silver,
        R.string.medal_cumulative_500k, Icons.AutoMirrored.Filled.DirectionsWalk,
    ),
    Cumulative1M(
        MedalCategory.Cumulative, 1_000_000L, MedalTier.Gold,
        R.string.medal_cumulative_1m, Icons.Filled.Star,
    ),
    Cumulative5M(
        MedalCategory.Cumulative, 5_000_000L, MedalTier.Diamond,
        R.string.medal_cumulative_5m, Icons.Filled.WorkspacePremium,
    ),
    Streak7(
        MedalCategory.Streak, 7L, MedalTier.Bronze,
        R.string.medal_streak_7, Icons.Filled.LocalFireDepartment,
    ),
    Streak30(
        MedalCategory.Streak, 30L, MedalTier.Silver,
        R.string.medal_streak_30, Icons.Filled.LocalFireDepartment,
    ),
    Streak100(
        MedalCategory.Streak, 100L, MedalTier.Gold,
        R.string.medal_streak_100, Icons.Filled.LocalFireDepartment,
    ),
    Streak365(
        MedalCategory.Streak, 365L, MedalTier.Diamond,
        R.string.medal_streak_365, Icons.Filled.CalendarMonth,
    ),
    Sessions1(
        MedalCategory.Session, 1L, MedalTier.Bronze,
        R.string.medal_sessions_1, Icons.AutoMirrored.Filled.DirectionsWalk,
    ),
    Sessions10(
        MedalCategory.Session, 10L, MedalTier.Silver,
        R.string.medal_sessions_10, Icons.Filled.MilitaryTech,
    ),
    Sessions50(
        MedalCategory.Session, 50L, MedalTier.Gold,
        R.string.medal_sessions_50, Icons.Filled.MilitaryTech,
    ),
    Sessions100(
        MedalCategory.Session, 100L, MedalTier.Diamond,
        R.string.medal_sessions_100, Icons.Filled.EmojiEvents,
    );

    /** Stable DB id, e.g. `"daily.10000"`. */
    val kindRaw: String get() = "${category.raw}.$threshold"

    companion object {
        private val byRaw: Map<String, MedalKind> by lazy {
            entries.associateBy { it.kindRaw }
        }

        fun fromRaw(raw: String): MedalKind? = byRaw[raw]

        /** Medals in a category, sorted ascending by threshold. */
        fun byCategory(c: MedalCategory): List<MedalKind> =
            entries.filter { it.category == c }.sortedBy { it.threshold }
    }
}
