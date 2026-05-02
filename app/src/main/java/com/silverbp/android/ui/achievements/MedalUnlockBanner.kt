package com.silverbp.android.ui.achievements

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbp.android.R
import com.silverbp.android.achievements.AchievementStore
import com.silverbp.android.achievements.MedalKind
import com.silverbp.android.achievements.MedalNotifier
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.settings.UserSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Top-of-screen toast that animates in when [AchievementStore.unlockEvents]
 * fires, auto-dismisses after [DISMISS_MS], or jumps to MedalsScreen on tap.
 *
 * On first unlock for users who haven't been asked yet about
 * [Manifest.permission.POST_NOTIFICATIONS], shows a one-shot CTA inviting
 * them to enable notifications. After the choice (granted/denied) we set
 * [UserSettings.didOfferNotificationPrompt] = true so we never re-prompt.
 */
@Composable
fun MedalUnlockBannerHost(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    store: AchievementStore = ServiceLocator.achievementStore,
) {
    val context = LocalContext.current
    val settingsRepo = remember { ServiceLocator.userSettings }
    val settingsFlow = settingsRepo.flow
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = UserSettings())

    var visibleMedals by remember { mutableStateOf<List<MedalKind>>(emptyList()) }
    var offerPrompt by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val permLauncher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { _ ->
            coroutineScope.launch { settingsRepo.setDidOfferNotificationPrompt(true) }
        }
    } else {
        null
    }

    // Subscribe to unlock events; auto-dismiss after DISMISS_MS.
    LaunchedEffect(store) {
        store.unlockEvents.collect { medals ->
            if (medals.isEmpty()) return@collect
            visibleMedals = medals
            val current = settingsFlow.first()
            offerPrompt = !current.didOfferNotificationPrompt &&
                !MedalNotifier.hasPostPermission(context)
            delay(DISMISS_MS)
            visibleMedals = emptyList()
            offerPrompt = false
        }
    }

    AnimatedVisibility(
        visible = visibleMedals.isNotEmpty(),
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        visibleMedals = emptyList()
                        offerPrompt = false
                        onTap()
                    },
            ) {
                Column(Modifier.padding(12.dp)) {
                    val title = if (visibleMedals.size == 1) {
                        stringResource(R.string.medal_banner_one)
                    } else {
                        stringResource(R.string.medal_banner_many, visibleMedals.size)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        visibleMedals.take(3).forEach { m ->
                            MedalBadge(medal = m, unlocked = true, sizeDp = 36)
                            Spacer(Modifier.size(6.dp))
                        }
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (visibleMedals.size == 1) {
                                Text(
                                    stringResource(visibleMedals.first().displayNameRes),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Text(
                                    stringResource(R.string.medal_banner_tap_view),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    if (offerPrompt && permLauncher != null) {
                        Spacer(Modifier.size(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = {
                                coroutineScope.launch {
                                    settingsRepo.setDidOfferNotificationPrompt(true)
                                }
                                offerPrompt = false
                            }) {
                                Text(stringResource(R.string.medal_banner_notif_decline))
                            }
                            TextButton(onClick = {
                                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                offerPrompt = false
                            }) {
                                Text(stringResource(R.string.medal_banner_notif_accept))
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val DISMISS_MS = 2_500L
