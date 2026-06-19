package com.silverbp.android.ui.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.silverbp.android.R
import com.silverbp.android.core.db.DietCheckEntity
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.ui.components.AppTopBar
import com.silverbp.android.ui.components.ExpressivePrimaryButton
import com.silverbp.android.ui.components.ExpressiveSecondaryButton
import com.silverbp.android.ui.components.SegmentedControl
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.exercise.colorForModule
import com.silverbp.android.ui.theme.AppSpacing
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachLogDietScreen(onClose: () -> Unit) {
    val repo = remember { ServiceLocator.coachRepository }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val dayStart = remember { todayDayStartMillis() }

    // 飲食 module identity tint (NOT a MetricAccent) — drives the section icon
    // tiles and the card accent stripe so the screen reads as the Diet module.
    val dietColor = colorForModule(ModuleKey.Diet)

    val savedMessage = stringResource(R.string.coach_log_saved)

    var sodium by remember { mutableStateOf("mid") }
    var veg by remember { mutableIntStateOf(3) }

    LaunchedEffect(dayStart) {
        repo.dietForDay(dayStart)?.let {
            sodium = it.sodiumLevelRaw
            veg = it.vegServings
        }
    }

    // SegmentedControl indexes map 1:1 to the persisted sodium raw values, so the
    // logging callback is unchanged — only the picker affordance is restyled.
    val sodiumValues = listOf("low", "mid", "high")
    val sodiumLabels = listOf(
        stringResource(R.string.coach_log_diet_sodium_low),
        stringResource(R.string.coach_log_diet_sodium_mid),
        stringResource(R.string.coach_log_diet_sodium_high),
    )
    val sodiumIndex = sodiumValues.indexOf(sodium).coerceAtLeast(0)

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.coach_log_diet_title),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
        ) {
            StandardCard(accent = dietColor) {
                SectionHeader(
                    icon = Icons.Filled.Restaurant,
                    title = stringResource(R.string.coach_log_diet_sodium),
                    tint = dietColor,
                )
                SegmentedControl(
                    options = sodiumLabels,
                    selectedIndex = sodiumIndex,
                    onSelect = { sodium = sodiumValues[it] },
                )
            }

            StandardCard(accent = dietColor) {
                SectionHeader(
                    icon = Icons.Filled.Spa,
                    title = stringResource(R.string.coach_log_diet_veg),
                    tint = dietColor,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExpressiveSecondaryButton(
                        text = "−",
                        onClick = { if (veg > 0) veg -= 1 },
                        enabled = veg > 0,
                    )
                    Text(
                        veg.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    ExpressiveSecondaryButton(
                        text = "+",
                        onClick = { if (veg < 12) veg += 1 },
                    )
                }
            }

            ExpressivePrimaryButton(
                text = stringResource(R.string.coach_log_save),
                onClick = {
                    scope.launch {
                        repo.upsertDiet(
                            DietCheckEntity(
                                dayStart = dayStart,
                                sodiumLevelRaw = sodium,
                                vegServings = veg,
                                sourceRaw = "manual",
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                        snackbar.showSnackbar(message = savedMessage)
                        onClose()
                    }
                },
                icon = Icons.Filled.Check,
                fillWidth = true,
            )
        }
    }
}

/**
 * Section heading shared by the diet cards: a small tinted icon tile next to a
 * semibold title, mirroring the Today/UnifiedHistory card idiom. The [tint] is
 * the 飲食 module identity colour (not a MetricAccent). UI-only.
 */
@Composable
private fun SectionHeader(icon: ImageVector, title: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint,
            )
        }
        Spacer(Modifier.size(AppSpacing.itemGap))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

internal fun todayDayStartMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
