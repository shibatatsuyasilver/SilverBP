package com.silverbp.android.ui.achievements

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.achievements.AchievementEvaluator
import com.silverbp.android.achievements.AchievementStats
import com.silverbp.android.achievements.MedalCategory
import com.silverbp.android.achievements.MedalKind
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedalsScreen(
    onBack: () -> Unit,
    vm: MedalsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.medal_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            if (!state.hasHealthConnect) {
                StandardCard(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(AppSpacing.itemGap))
                        Text(
                            stringResource(R.string.medal_screen_hc_notice),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            MedalCategory.entries.forEach { category ->
                CategorySection(
                    category = category,
                    stats = state.stats,
                    unlocked = state.unlocked,
                )
            }
            Spacer(Modifier.size(AppSpacing.itemGap))
        }
    }
}

/**
 * One titled section card holding a category's medals as a clean two-column grid
 * of badge tiles. The section header carries a "N / total" earned count chip so
 * progress reads at a glance. Mirrors the Today/UnifiedHistory card idiom
 * (StandardCard surface, rounded tiles, tinted fills, generous spacing).
 */
@Composable
private fun CategorySection(
    category: MedalCategory,
    stats: AchievementStats,
    unlocked: Map<MedalKind, Long>,
) {
    val medals = remember(category) { MedalKind.byCategory(category) }
    val earned = medals.count { unlocked[it] != null }
    val numberFormat = remember { NumberFormat.getIntegerInstance(Locale.getDefault()) }

    StandardCard(
        title = stringResource(category.titleRes),
        titleTrailing = {
            EarnedCountChip(earned = earned, total = medals.size, numberFormat = numberFormat)
        },
        verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        // Fixed two-column grid built from Rows so it nests safely inside the
        // outer verticalScroll (a LazyVerticalGrid would conflict). Each row's
        // two cells share width evenly; a trailing odd cell gets a Spacer twin.
        medals.chunked(2).forEach { rowMedals ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
            ) {
                rowMedals.forEach { medal ->
                    MedalGridTile(
                        medal = medal,
                        stats = stats,
                        unlockedAtMillis = unlocked[medal],
                        numberFormat = numberFormat,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowMedals.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Small pill showing how many of a section's medals are earned (e.g. "2 / 5"). */
@Composable
private fun EarnedCountChip(
    earned: Int,
    total: Int,
    numberFormat: NumberFormat,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            stringResource(
                R.string.medal_progress_label,
                numberFormat.format(earned),
                numberFormat.format(total),
            ),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One badge tile in the section grid: a rounded surface-variant cell with the
 * circular [MedalBadge] centred at top, the medal name below, then either the
 * unlock date (unlocked) or a progress bar + "current / target" label (locked).
 *
 * Locked vs unlocked is distinguished by opacity (the badge itself desaturates)
 * AND a lock glyph overlaid on the badge — never colour alone — so it reads for
 * colour-blind / low-vision users.
 */
@Composable
private fun MedalGridTile(
    medal: MedalKind,
    stats: AchievementStats,
    unlockedAtMillis: Long?,
    numberFormat: NumberFormat,
    modifier: Modifier = Modifier,
) {
    val isUnlocked = unlockedAtMillis != null
    val dateFormat = remember {
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
    }
    val progress = AchievementEvaluator.progress(medal, stats)

    Column(
        modifier = modifier
            .heightIn(min = 188.dp)
            .clip(RoundedCornerShape(AppSpacing.cardCorner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(AppSpacing.itemGap + AppSpacing.tight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
    ) {
        // Badge with a lock-glyph overlay when not yet earned.
        Box(contentAlignment = Alignment.Center) {
            MedalBadge(medal = medal, unlocked = isUnlocked, sizeDp = 64)
            if (!isUnlocked) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            stringResource(medal.displayNameRes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.weight(1f))

        if (isUnlocked) {
            Text(
                stringResource(
                    R.string.medal_unlocked_on,
                    dateFormat.format(Instant.ofEpochMilli(unlockedAtMillis!!)),
                ),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            val (cur, target) = currentAndTarget(medal, stats)
            Text(
                stringResource(
                    R.string.medal_progress_label,
                    numberFormat.format(cur),
                    numberFormat.format(target),
                ),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(AppSpacing.tight))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape),
            )
        }
    }
}

private fun currentAndTarget(medal: MedalKind, stats: AchievementStats): Pair<Long, Long> {
    val current: Long = when (medal.category) {
        MedalCategory.DailySteps -> stats.todaySteps.toLong()
        MedalCategory.Cumulative -> stats.lifetimeSteps
        MedalCategory.Streak -> stats.currentStreakDays.toLong()
        MedalCategory.Session -> stats.sessionCount.toLong()
    }
    return current to medal.threshold
}
