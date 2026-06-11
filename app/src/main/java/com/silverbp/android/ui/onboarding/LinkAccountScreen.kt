package com.silverbp.android.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.ui.backup.BackupViewModel
import kotlinx.coroutines.launch

/**
 * First-launch soft gate: prompts for a Google sign-in so data can be backed up
 * to Drive. Shown by [com.silverbp.android.ui.nav.AppNavHost] after onboarding is
 * complete but before HOME is released, whenever `googleAccountEmail` is blank
 * and the user hasn't already skipped. 「稍後再說」 sets `skippedGoogleLink` and
 * proceeds to HOME so users without Play Services / network / a Google account
 * are never dead-ended; auto-backup stays off until they link from Backup.
 *
 * Reuses [BackupViewModel]'s account-linking mechanism verbatim — the same one
 * BackupScreen drives: [BackupViewModel.startGoogleConnect] either resolves
 * silently or parks a consent [android.content.IntentSender] on
 * [BackupViewModel.pendingConsentIntent], which we launch through
 * `StartIntentSenderForResult` and hand back via
 * [BackupViewModel.completeGoogleConsent]. The ViewModel persists
 * googleAccountEmail/googleAccountId; we watch it and fire [onLinked] once set.
 */
@Composable
fun LinkAccountScreen(
    onLinked: () -> Unit,
    vm: BackupViewModel = viewModel(),
) {
    val userSettings by ServiceLocator.userSettings.flow.collectAsStateWithLifecycle(initialValue = null)
    val pendingConsent by vm.pendingConsentIntent.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var signingIn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }

    val accountEmail = userSettings?.googleAccountEmail.orEmpty()

    // Linked → release the gate. Once googleAccountEmail is set the AppNavHost
    // gate never re-fires, so this callback runs at most once per first launch.
    LaunchedEffect(accountEmail) {
        if (accountEmail.isNotBlank()) onLinked()
    }

    // Same consent-launcher wiring as BackupScreen: VM parks the sender, the
    // effect kicks it off, and the launcher callback hands the Intent back.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> vm.completeGoogleConsent(result.data) }
    LaunchedEffect(pendingConsent) {
        pendingConsent?.let { sender ->
            consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    // A cancel/failure publishes here; drop back to the error/retry state so the
    // user is never dead-ended.
    LaunchedEffect(Unit) {
        vm.autoBackupErrors.collect {
            signingIn = false
            error = true
        }
    }

    fun signIn() {
        error = false
        signingIn = true
        vm.startGoogleConnect()
    }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.login_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.login_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error) {
                Text(
                    stringResource(R.string.login_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.weight(1f))

            if (signingIn && !error) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                Text(
                    stringResource(R.string.login_signing_in),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Button(
                    onClick = { signIn() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (error) R.string.login_retry else R.string.login_button
                        )
                    )
                }
                // Soft-gate escape hatch: a user without Play Services / network /
                // a Google account would otherwise be permanently stuck here. Skip
                // persists the flag (so the AppNavHost gate never re-fires) and
                // releases HOME; auto-backup stays off until they link from Backup.
                TextButton(
                    onClick = {
                        scope.launch { ServiceLocator.userSettings.setSkippedGoogleLink(true) }
                        onLinked()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.login_skip))
                }
            }
            Spacer(Modifier.size(4.dp))
            Text(
                stringResource(R.string.login_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
