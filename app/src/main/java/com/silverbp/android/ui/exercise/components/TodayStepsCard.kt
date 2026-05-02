package com.silverbp.android.ui.exercise.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import java.text.NumberFormat
import java.util.Locale

/**
 * Lightweight today-progress card placed above [com.silverbp.android.ui
 * .exercise.components.MedalShowcaseCard]. Shows the current step count
 * and a progress bar toward the user's daily goal — gives the daily-step
 * medals a visible target on the home screen.
 */
@Composable
fun TodayStepsCard(
    todaySteps: Int,
    dailyGoal: Int,
    modifier: Modifier = Modifier,
) {
    val numberFormat = remember { NumberFormat.getIntegerInstance(Locale.getDefault()) }
    val progress = if (dailyGoal <= 0) 0f
        else (todaySteps.toFloat() / dailyGoal.toFloat()).coerceIn(0f, 1f)

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.exercise_today_steps),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    numberFormat.format(todaySteps),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(
                        R.string.medal_today_target,
                        numberFormat.format(dailyGoal),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
