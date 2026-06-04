package com.silverbp.android.ui.strength

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.strength.BodyPart
import com.silverbp.android.strength.ExerciseCatalogItem
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    vm: ExerciseLibraryViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.strength_library_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                placeholder = { Text(stringResource(R.string.strength_library_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(AppSpacing.cardCorner),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH)
                    .padding(top = AppSpacing.itemGap),
            )

            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenH),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.tight),
            ) {
                FilterChip(
                    selected = state.bodyPart == null && !state.savedOnly,
                    onClick = {
                        vm.setSavedOnly(false)
                        vm.setBodyPart(null)
                    },
                    label = { Text(stringResource(R.string.strength_filter_all)) },
                )
                BodyPart.entries.forEach { part ->
                    FilterChip(
                        selected = state.bodyPart == part && !state.savedOnly,
                        onClick = {
                            vm.setSavedOnly(false)
                            vm.setBodyPart(if (state.bodyPart == part) null else part)
                        },
                        label = { Text(part.labelZh) },
                    )
                }
                FilterChip(
                    selected = state.savedOnly,
                    onClick = { vm.setSavedOnly(!state.savedOnly) },
                    leadingIcon = { Icon(Icons.Filled.Star, null) },
                    label = { Text(stringResource(R.string.strength_saved)) },
                )
            }

            if (state.items.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(AppSpacing.screenH),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.strength_library_empty)) }
            } else {
                StandardCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = AppSpacing.screenH)
                        .padding(top = AppSpacing.tight, bottom = AppSpacing.screenV),
                    contentPadding = 0.dp,
                    verticalArrangement = Arrangement.Top,
                ) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        itemsIndexed(state.items, key = { _, it -> it.id }) { index, item ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = AppSpacing.cardPadding),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            ExerciseRow(
                                item = item,
                                onClick = { onOpenDetail(item.id) },
                                onToggleFavorite = { vm.toggleFavorite(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseRow(
    item: ExerciseCatalogItem,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = AppSpacing.cardPadding, top = 12.dp, bottom = 12.dp, end = AppSpacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                bodyPartIcon(item.bodyPart),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.size(AppSpacing.itemGap))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                item.muscleGroups.joinToString("、"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onToggleFavorite) {
            if (item.isFavorite) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = stringResource(R.string.strength_favorite_a11y),
                    tint = FavoriteTagColor,
                )
            } else {
                Icon(
                    Icons.Outlined.StarBorder,
                    contentDescription = stringResource(R.string.strength_favorite_a11y),
                )
            }
        }
    }
}

/** Leading icon for an exercise row, chosen by its primary body region. */
private fun bodyPartIcon(part: BodyPart): ImageVector = when (part) {
    BodyPart.UpperBody -> Icons.Filled.FitnessCenter
    BodyPart.LowerBody -> Icons.AutoMirrored.Filled.DirectionsRun
    BodyPart.Core -> Icons.Filled.SelfImprovement
    BodyPart.FullBody -> Icons.Filled.Accessibility
}
