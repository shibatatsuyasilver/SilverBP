package com.silverbp.android.ui.exercise.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.exercise.ActivityKind
import com.silverbp.android.exercise.ExerciseMath
import com.silverbp.android.exercise.ExerciseSession
import com.silverbp.android.ui.exercise.colorForKind
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun RecentSessionsCard(
    sessions: List<ExerciseSession>,
    onSessionClick: (ExerciseSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.exercise_recent_sessions),
                style = MaterialTheme.typography.titleMedium,
            )
            if (sessions.isEmpty()) {
                Text(
                    stringResource(R.string.exercise_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                sessions.forEach { s ->
                    SessionRow(
                        session = s,
                        onClick = { onSessionClick(s) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: ExerciseSession, onClick: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.TAIWAN).withZone(zone)
    val durationMs = session.activeDurationMillis
    val color = colorForKind(session.kind)

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (session.kind == ActivityKind.Walking)
                    Icons.AutoMirrored.Filled.DirectionsWalk else Icons.AutoMirrored.Filled.DirectionsRun,
                contentDescription = null,
                tint = Color.White,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                fmt.format(session.startedAt),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                ExerciseMath.formatDuration(durationMs),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            ExerciseMath.formatDistance(session.distanceMeters),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
