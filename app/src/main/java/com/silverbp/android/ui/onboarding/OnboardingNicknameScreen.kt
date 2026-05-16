package com.silverbp.android.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.silverbp.android.BuildConfig
import com.silverbp.android.R
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.legal.CURRENT_PRIVACY_POLICY_VERSION
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * First-launch flow:
 *   step 0  Welcome + medical disclaimer + privacy-policy consent (required).
 *   step 1  Notification permission request (Android 13+); skippable.
 *   step 2  Nickname capture (existing behavior).
 *
 * On step 2 completion the screen flips both [didOnboard] and
 * [acceptedPolicyVersion] so the AppNavHost gate releases the user to HOME.
 * If the user revisits this screen via Settings → "Review consent" we land
 * on step 0 again with the existing nickname pre-filled in step 2.
 */
@Composable
fun OnboardingNicknameScreen(
    onCompleted: () -> Unit,
) {
    val repo = remember { ServiceLocator.userSettings }
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var nickname by rememberSaveable { mutableStateOf("") }
    var consentChecked by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val s = runCatching { repo.flow.first() }.getOrNull() ?: return@LaunchedEffect
        if (s.userNickname.isNotEmpty() && nickname.isEmpty()) nickname = s.userNickname
        // If the user already accepted the current policy version (e.g. they
        // entered onboarding via "Review consent" but only need to update
        // their nickname) skip the consent step so they don't have to re-tick.
        if (s.acceptedPolicyVersion >= CURRENT_PRIVACY_POLICY_VERSION && step == 0) {
            step = 2
        }
    }

    fun finish(rawValue: String) {
        if (saving) return
        saving = true
        scope.launch {
            runCatching {
                repo.setUserNickname(rawValue)
                repo.setAcceptedPolicyVersion(CURRENT_PRIVACY_POLICY_VERSION)
                repo.setDidOnboard(true)
            }
            onCompleted()
        }
    }

    Scaffold { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            when (step) {
                0 -> ConsentStep(
                    consentChecked = consentChecked,
                    onConsentChange = { consentChecked = it },
                    onContinue = { step = 1 },
                )
                1 -> NotificationsStep(
                    onContinue = { step = 2 },
                )
                else -> NicknameStep(
                    nickname = nickname,
                    onNicknameChange = { nickname = it },
                    saving = saving,
                    onDone = { finish(nickname) },
                    onSkip = { finish("") },
                )
            }
        }
    }
}

@Composable
private fun ConsentStep(
    consentChecked: Boolean,
    onConsentChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.not_medical_device),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(
            onClick = {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_privacy_link))
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = consentChecked, onCheckedChange = onConsentChange)
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.onboarding_accept),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            onClick = onContinue,
            enabled = consentChecked,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_continue))
        }
    }
}

@Composable
private fun NotificationsStep(
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    // Skip the screen entirely on pre-Android-13 devices: the runtime
    // permission doesn't exist there, channels alone are sufficient.
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onContinue()
        else if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            onContinue()
        }
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ -> onContinue() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.onboarding_notifications_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.onboarding_notifications_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onContinue()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_notifications_grant))
        }
        TextButton(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_notifications_skip))
        }
    }
}

@Composable
private fun NicknameStep(
    nickname: String,
    onNicknameChange: (String) -> Unit,
    saving: Boolean,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.onboarding_nickname_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.onboarding_nickname_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = nickname,
            onValueChange = {
                onNicknameChange(it.take(UserSettingsRepository.MAX_NICKNAME_LEN))
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.onboarding_nickname_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            enabled = !saving,
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving,
        ) {
            Text(stringResource(R.string.onboarding_done))
        }
        TextButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving,
        ) {
            Text(
                text = stringResource(R.string.onboarding_skip),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.onboarding_nickname_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth(),
        )
    }
}
