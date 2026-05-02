package com.silverbp.android.ui.achievements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.achievements.AchievementEvaluator
import com.silverbp.android.achievements.AchievementStats
import com.silverbp.android.achievements.MedalCategory
import com.silverbp.android.achievements.MedalKind
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.hasHealthConnect) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.medal_screen_hc_notice),
                            style = MaterialTheme.typography.bodySmall,
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
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun CategorySection(
    category: MedalCategory,
    stats: AchievementStats,
    unlocked: Map<MedalKind, Long>,
) {
    val medals = remember(category) { MedalKind.byCategory(category) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(category.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            medals.forEach { medal ->
                MedalRow(
                    medal = medal,
                    stats = stats,
                    unlockedAtMillis = unlocked[medal],
                )
            }
        }
    }
}

@Composable
private fun MedalRow(
    medal: MedalKind,
    stats: AchievementStats,
    unlockedAtMillis: Long?,
) {
    val isUnlocked = unlockedAtMillis != null
    val numberFormat = remember { NumberFormat.getIntegerInstance(Locale.getDefault()) }
    val dateFormat = remember {
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
    }
    val progress = AchievementEvaluator.progress(medal, stats)

    Row(verticalAlignment = Alignment.CenterVertically) {
        MedalBadge(medal = medal, unlocked = isUnlocked, sizeDp = 52)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(medal.displayNameRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (isUnlocked) {
                Text(
                    stringResource(
                        R.string.medal_unlocked_on,
                        dateFormat.format(Instant.ofEpochMilli(unlockedAtMillis!!)),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                val (cur, target) = currentAndTarget(medal, stats)
                Text(
                    stringResource(
                        R.string.medal_progress_label,
                        numberFormat.format(cur),
                        numberFormat.format(target),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.size(4.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
