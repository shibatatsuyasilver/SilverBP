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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import com.silverbp.android.settings.ExperienceLevel
import com.silverbp.android.settings.PrimaryGoal
import com.silverbp.android.settings.TrainingStyle
import com.silverbp.android.settings.UserSettingsRepository
import com.silverbp.android.settings.WeeklyAvailability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * First-launch flow:
 *   step 0  Welcome + medical disclaimer + privacy-policy consent (required).
 *   step 1  Notification permission request (Android 13+); skippable.
 *   step 2  Nickname capture.
 *   step 3  Primary goal (goal profile, Phase 4).
 *   step 4  Experience level.
 *   step 5  Weekly availability (days/week).
 *   step 6  Training style — final step; persists the goal profile AND flips
 *           [didOnboard] + [acceptedPolicyVersion] so the AppNavHost gate
 *           releases the user to HOME.
 *
 * The goal profile feeds [com.silverbp.android.coach.CoachEngine] plan
 * generation. If the user revisits via Settings → "Review consent" we skip
 * straight to the nickname step (already-onboarded edit) and finish there
 * without re-asking the goal questions.
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
    // Goal-profile selections (Phase 4).
    var primaryGoal by rememberSaveable { mutableStateOf<PrimaryGoal?>(null) }
    var experience by rememberSaveable { mutableStateOf<ExperienceLevel?>(null) }
    var availabilityDays by rememberSaveable { mutableIntStateOf(0) }
    var trainingStyle by rememberSaveable { mutableStateOf<TrainingStyle?>(null) }
    // True when re-entered via "Review consent": finish at the nickname step
    // rather than re-asking the goal questions.
    var reviewMode by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val s = runCatching { repo.flow.first() }.getOrNull() ?: return@LaunchedEffect
        if (s.userNickname.isNotEmpty() && nickname.isEmpty()) nickname = s.userNickname
        // If the user already accepted the current policy version (e.g. they
        // entered onboarding via "Review consent" but only need to update
        // their nickname) skip straight to the nickname step and finish there.
        if (s.acceptedPolicyVersion >= CURRENT_PRIVACY_POLICY_VERSION && step == 0) {
            reviewMode = true
            step = 2
        }
    }

    fun finish() {
        if (saving) return
        saving = true
        scope.launch {
            runCatching {
                repo.setUserNickname(nickname)
                primaryGoal?.let { repo.setPrimaryGoal(it) }
                experience?.let { repo.setExperienceLevel(it) }
                if (availabilityDays > 0) repo.setWeeklyAvailabilityDays(availabilityDays)
                trainingStyle?.let { repo.setTrainingStyle(it) }
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
                2 -> NicknameStep(
                    nickname = nickname,
                    onNicknameChange = { nickname = it },
                    saving = saving,
                    // In review mode the goal questions are skipped — finish here.
                    onDone = { if (reviewMode) finish() else step = 3 },
                    onSkip = {
                        nickname = ""
                        if (reviewMode) finish() else step = 3
                    },
                )
                3 -> PrimaryGoalStep(
                    selected = primaryGoal,
                    onSelect = { primaryGoal = it },
                    onBack = { step = 2 },
                    onNext = { step = 4 },
                )
                4 -> ExperienceStep(
                    selected = experience,
                    onSelect = { experience = it },
                    onBack = { step = 3 },
                    onNext = { step = 5 },
                )
                5 -> AvailabilityStep(
                    selected = availabilityDays,
                    onSelect = { availabilityDays = it },
                    onBack = { step = 4 },
                    onNext = { step = 6 },
                )
                else -> TrainingStyleStep(
                    selected = trainingStyle,
                    saving = saving,
                    onSelect = { trainingStyle = it },
                    onBack = { step = 5 },
                    onDone = { finish() },
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

/**
 * Shared layout for the four goal-profile steps: title + subtitle, a wrapping
 * row of single-select chips, and Back / Next (or Done on the last step).
 * [nextEnabled] gates the primary button until a choice is made.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalSelectionStep(
    title: String,
    subtitle: String,
    nextEnabled: Boolean,
    nextLabel: String,
    onBack: () -> Unit,
    onNext: () -> Unit,
    saving: Boolean = false,
    chips: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.size(8.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips()
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
            enabled = nextEnabled && !saving,
        ) {
            Text(nextLabel)
        }
        TextButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving,
        ) {
            Text(stringResource(R.string.onboarding_goal_back))
        }
    }
}

@Composable
private fun PrimaryGoalStep(
    selected: PrimaryGoal?,
    onSelect: (PrimaryGoal) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    GoalSelectionStep(
        title = stringResource(R.string.onboarding_primary_goal_title),
        subtitle = stringResource(R.string.onboarding_primary_goal_subtitle),
        nextEnabled = selected != null,
        nextLabel = stringResource(R.string.onboarding_goal_next),
        onBack = onBack,
        onNext = onNext,
    ) {
        PrimaryGoal.entries.forEach { goal ->
            FilterChip(
                selected = selected == goal,
                onClick = { onSelect(goal) },
                label = { Text(goal.label) },
            )
        }
    }
}

@Composable
private fun ExperienceStep(
    selected: ExperienceLevel?,
    onSelect: (ExperienceLevel) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    GoalSelectionStep(
        title = stringResource(R.string.onboarding_experience_title),
        subtitle = stringResource(R.string.onboarding_experience_subtitle),
        nextEnabled = selected != null,
        nextLabel = stringResource(R.string.onboarding_goal_next),
        onBack = onBack,
        onNext = onNext,
    ) {
        ExperienceLevel.entries.forEach { level ->
            FilterChip(
                selected = selected == level,
                onClick = { onSelect(level) },
                label = { Text(level.label) },
            )
        }
    }
}

@Composable
private fun AvailabilityStep(
    selected: Int,
    onSelect: (Int) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    GoalSelectionStep(
        title = stringResource(R.string.onboarding_availability_title),
        subtitle = stringResource(R.string.onboarding_availability_subtitle),
        nextEnabled = selected > 0,
        nextLabel = stringResource(R.string.onboarding_goal_next),
        onBack = onBack,
        onNext = onNext,
    ) {
        WeeklyAvailability.OPTIONS.forEach { days ->
            FilterChip(
                selected = selected == days,
                onClick = { onSelect(days) },
                label = { Text(stringResource(R.string.onboarding_availability_days, days)) },
            )
        }
    }
}

@Composable
private fun TrainingStyleStep(
    selected: TrainingStyle?,
    saving: Boolean,
    onSelect: (TrainingStyle) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    GoalSelectionStep(
        title = stringResource(R.string.onboarding_training_style_title),
        subtitle = stringResource(R.string.onboarding_training_style_subtitle),
        nextEnabled = selected != null,
        nextLabel = stringResource(R.string.onboarding_done),
        onBack = onBack,
        onNext = onDone,
        saving = saving,
    ) {
        TrainingStyle.entries.forEach { style ->
            FilterChip(
                selected = selected == style,
                onClick = { onSelect(style) },
                label = { Text(style.label) },
            )
        }
    }
}
