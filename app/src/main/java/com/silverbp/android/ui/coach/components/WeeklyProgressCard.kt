package com.silverbp.android.ui.coach.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.silverbp.android.R
import com.silverbp.android.ui.coach.WeeklyProgressUi
import com.silverbp.android.ui.components.StandardCard

@Composable
fun WeeklyProgressCard(
    state: WeeklyProgressUi,
    modifier: Modifier = Modifier,
) {
    StandardCard(
        modifier = modifier,
        title = stringResource(R.string.coach_weekly_progress_title),
    ) {
        // PR3 swaps this for a Vico chart driven by CoachRepository.
        Text(
            state.placeholderText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
