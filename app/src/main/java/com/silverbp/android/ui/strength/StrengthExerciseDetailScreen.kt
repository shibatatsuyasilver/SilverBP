package com.silverbp.android.ui.strength

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.silverbp.android.R
import com.silverbp.android.ui.components.AppTopBar
import com.silverbp.android.ui.components.ExpressiveAssistChip
import com.silverbp.android.ui.components.ExpressivePrimaryButton
import com.silverbp.android.ui.components.ExpressiveSecondaryButton
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.PillShape

@Composable
fun StrengthExerciseDetailScreen(
    exerciseId: String,
    onBack: () -> Unit,
    onStartWorkout: (String) -> Unit,
) {
    val vm: StrengthExerciseDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer { StrengthExerciseDetailViewModel(exerciseId) }
        }
    )
    val item by vm.item.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            AppTopBar(
                title = item?.name.orEmpty(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        val ex = item
        if (ex == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.strength_library_empty)) }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ex.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (ex.isFavorite) SavedTag()
            }

            StandardCard(title = stringResource(R.string.strength_detail_muscle_groups)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                    ex.muscleGroups.forEach { group ->
                        ExpressiveAssistChip(label = group, onClick = {})
                    }
                }
            }

            if (ex.description.isNotBlank()) {
                StandardCard(title = stringResource(R.string.strength_detail_description)) {
                    Text(
                        ex.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ExpressivePrimaryButton(
                text = stringResource(R.string.strength_start_workout),
                onClick = { onStartWorkout(ex.id) },
                icon = Icons.Filled.PlayArrow,
                fillWidth = true,
            )

            ExpressiveSecondaryButton(
                text = stringResource(
                    if (ex.isFavorite) R.string.strength_unsave else R.string.strength_save
                ),
                onClick = vm::toggleFavorite,
                icon = if (ex.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                fillWidth = true,
            )
        }
    }
}

@Composable
private fun SavedTag() {
    Row(
        modifier = Modifier
            .background(FavoriteTagColor, PillShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.tight),
    ) {
        Icon(Icons.Filled.Star, null, tint = Color.Black)
        Text(
            stringResource(R.string.strength_saved),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.Black,
        )
    }
}
