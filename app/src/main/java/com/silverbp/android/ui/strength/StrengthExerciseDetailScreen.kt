package com.silverbp.android.ui.strength

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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

@OptIn(ExperimentalMaterial3Api::class)
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
            TopAppBar(
                title = { Text(item?.name.orEmpty()) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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

            Text(
                stringResource(R.string.strength_detail_muscle_groups),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ex.muscleGroups.forEach { group ->
                    AssistChip(onClick = {}, label = { Text(group) })
                }
            }

            if (ex.description.isNotBlank()) {
                Text(
                    stringResource(R.string.strength_detail_description),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(ex.description, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = { onStartWorkout(ex.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.PlayArrow, null)
                Text(stringResource(R.string.strength_start_workout))
            }

            OutlinedButton(
                onClick = vm::toggleFavorite,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    if (ex.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    null,
                )
                Text(
                    stringResource(
                        if (ex.isFavorite) R.string.strength_unsave else R.string.strength_save
                    ),
                )
            }
        }
    }
}

@Composable
private fun SavedTag() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = FavoriteTagColor,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Star, null, tint = Color.Black)
            Text(
                stringResource(R.string.strength_saved),
                style = MaterialTheme.typography.labelMedium,
                color = Color.Black,
            )
        }
    }
}
