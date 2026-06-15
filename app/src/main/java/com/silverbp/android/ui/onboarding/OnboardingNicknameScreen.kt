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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.silverbp.android.BuildConfig
import com.silverbp.android.R
import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightSource
import com.silverbp.android.core.WeightUnit
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.ui.components.HeightPickerField
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.components.WeightPickerField
import com.silverbp.android.ui.components.YearPickerField
import com.silverbp.android.ui.theme.AppSpacing
import java.time.Instant
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
    val memberRepo = remember { ServiceLocator.memberRepository }
    val weightRepo = remember { ServiceLocator.weightRepository }
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var nickname by rememberSaveable { mutableStateOf("") }
    var consentChecked by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    // Health-profile inputs (step 3). All optional; null → nothing written. Picked
    // from range-constrained wheels, so out-of-range values can't be entered.
    var birthYear by rememberSaveable { mutableStateOf<Int?>(null) }
    var heightCm by rememberSaveable { mutableStateOf<Int?>(null) }
    // Canonical kg (unit-independent); the wheel handles kg/lb display itself.
    var weightKg by rememberSaveable { mutableStateOf<Double?>(null) }
    // Weight entry unit; seeded from UserSettings.weightUnit in LaunchedEffect.
    var weightUnit by rememberSaveable { mutableStateOf(WeightUnit.Kg) }
    // Id of the starting weight reading written from this step, so re-entering the
    // step (Back→Next) updates the same row instead of inserting a duplicate.
    var profileWeightId by rememberSaveable { mutableStateOf<String?>(null) }
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
        weightUnit = WeightUnit.fromRaw(s.weightUnit)
        // If the user already accepted the current policy version (e.g. they
        // entered onboarding via "Review consent" but only need to update
        // their nickname) skip straight to the nickname step and finish there.
        if (s.acceptedPolicyVersion >= CURRENT_PRIVACY_POLICY_VERSION && step == 0) {
            reviewMode = true
            step = 2
        }
    }

    // Persist non-blank health-profile inputs when the "Your details" step is left
    // (via Next or Skip). Blank fields write nothing and never overwrite an existing
    // owner value with null. Fire-and-forget on the screen scope so navigation isn't
    // blocked.
    fun persistProfile() {
        val parsedBirthYear = birthYear
        val parsedHeight = heightCm
        val parsedWeight = weightKg
        if (parsedBirthYear == null && parsedHeight == null && parsedWeight == null) return
        scope.launch {
            runCatching {
                if (parsedBirthYear != null || parsedHeight != null) {
                    val owner = memberRepo.owner()
                    memberRepo.upsert(
                        owner.copy(
                            // Keep the owner's current value when a field is blank.
                            birthYear = parsedBirthYear ?: owner.birthYear,
                            heightCm = parsedHeight ?: owner.heightCm,
                            updatedAt = Instant.now(),
                        ),
                    )
                }
                if (parsedWeight != null && parsedWeight > 0.0) {
                    // Reuse the prior reading's id on re-entry so Back→Next updates
                    // the same starting weight rather than inserting a duplicate.
                    val id = profileWeightId?.let(java.util.UUID::fromString) ?: java.util.UUID.randomUUID()
                    profileWeightId = id.toString()
                    weightRepo.upsert(
                        WeightReading(
                            id = id,
                            memberId = "",
                            // Already canonical kg — the wheel stored it unit-free.
                            valueKg = parsedWeight,
                            displayUnit = weightUnit,
                            timestamp = Instant.now(),
                            source = WeightSource.Manual,
                        ),
                    )
                }
            }
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
                3 -> ProfileStep(
                    birthYear = birthYear,
                    onBirthYearChange = { birthYear = it },
                    heightCm = heightCm,
                    onHeightChange = { heightCm = it },
                    weightKg = weightKg,
                    onWeightChange = { weightKg = it },
                    weightUnit = weightUnit,
                    onBack = { step = 2 },
                    // Both Next and Skip persist whatever was filled, then advance.
                    onNext = {
                        persistProfile()
                        step = 4
                    },
                    onSkip = {
                        persistProfile()
                        step = 4
                    },
                )
                4 -> PrimaryGoalStep(
                    selected = primaryGoal,
                    onSelect = { primaryGoal = it },
                    onBack = { step = 3 },
                    onNext = { step = 5 },
                )
                5 -> ExperienceStep(
                    selected = experience,
                    onSelect = { experience = it },
                    onBack = { step = 4 },
                    onNext = { step = 6 },
                )
                6 -> AvailabilityStep(
                    selected = availabilityDays,
                    onSelect = { availabilityDays = it },
                    onBack = { step = 5 },
                    onNext = { step = 7 },
                )
                else -> TrainingStyleStep(
                    selected = trainingStyle,
                    saving = saving,
                    onSelect = { trainingStyle = it },
                    onBack = { step = 6 },
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
            .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV * 2),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
    ) {
        Spacer(Modifier.size(AppSpacing.itemGap))
        Text(
            stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StandardCard {
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
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = consentChecked, onCheckedChange = onConsentChange)
            Spacer(Modifier.size(AppSpacing.itemGap))
            Text(
                stringResource(R.string.onboarding_accept),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OnboardingHeroButton(
            label = stringResource(R.string.onboarding_continue),
            onClick = onContinue,
            enabled = consentChecked,
        )
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
            .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV * 2),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
    ) {
        Spacer(Modifier.size(AppSpacing.itemGap))
        Text(
            stringResource(R.string.onboarding_notifications_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.onboarding_notifications_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        OnboardingHeroButton(
            label = stringResource(R.string.onboarding_notifications_grant),
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onContinue()
                }
            },
            enabled = true,
        )
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
            .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV * 2),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
    ) {
        Spacer(Modifier.size(AppSpacing.itemGap))
        Text(
            stringResource(R.string.onboarding_nickname_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.onboarding_nickname_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StandardCard {
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
        }

        Spacer(Modifier.weight(1f))

        OnboardingHeroButton(
            label = stringResource(R.string.onboarding_done),
            onClick = onDone,
            enabled = !saving,
        )
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
        Spacer(Modifier.size(AppSpacing.tight))
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
 * "Your details" step (skippable): birth year / height / weight, all optional and
 * picked from range-constrained wheels (so out-of-range values can't be entered).
 * Next and Skip both advance; the caller persists the non-blank values. The primary
 * button stays enabled even when everything is blank — leaving the step is always
 * allowed.
 */
@Composable
private fun ProfileStep(
    birthYear: Int?,
    onBirthYearChange: (Int?) -> Unit,
    heightCm: Int?,
    onHeightChange: (Int?) -> Unit,
    weightKg: Double?,
    onWeightChange: (Double?) -> Unit,
    weightUnit: WeightUnit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val unitLabel = stringResource(
        if (weightUnit == WeightUnit.Lb) R.string.weight_unit_lb else R.string.weight_unit_kg,
    )

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV * 2),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
    ) {
        Spacer(Modifier.size(AppSpacing.itemGap))
        Text(
            stringResource(R.string.onboarding_profile_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.onboarding_profile_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StandardCard {
            // All three are wheel pickers (tap the value to open). Each is optional —
            // the dialog's Clear restores "not set" so a skippable step stays skippable.
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                YearPickerField(
                    value = birthYear,
                    onChange = onBirthYearChange,
                    label = stringResource(R.string.member_birth_year),
                    modifier = Modifier.fillMaxWidth(),
                )
                HeightPickerField(
                    value = heightCm,
                    onChange = onHeightChange,
                    label = stringResource(R.string.weight_height_label),
                    modifier = Modifier.fillMaxWidth(),
                )
                WeightPickerField(
                    valueKg = weightKg,
                    unit = weightUnit,
                    onChange = onWeightChange,
                    label = stringResource(R.string.onboarding_profile_weight, unitLabel),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        OnboardingHeroButton(
            label = stringResource(R.string.onboarding_goal_next),
            onClick = onNext,
            // Leaving the step is always allowed — every field is optional.
            enabled = true,
        )
        TextButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.onboarding_profile_skip),
                textAlign = TextAlign.Center,
            )
        }
        TextButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_goal_back))
        }
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
            .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV * 2),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap),
    ) {
        Spacer(Modifier.size(AppSpacing.itemGap))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StandardCard {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                chips()
            }
        }
        Spacer(Modifier.weight(1f))
        OnboardingHeroButton(
            label = nextLabel,
            onClick = onNext,
            enabled = nextEnabled && !saving,
        )
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
                label = { Text(stringResource(goal.labelRes)) },
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
                label = { Text(stringResource(level.labelRes)) },
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
                label = { Text(stringResource(style.labelRes)) },
            )
        }
    }
}

/**
 * Full-width lime "hero" primary action used to advance each onboarding step
 * (Continue / Done / Next). Pure styling wrapper around [Button] — preserves the
 * caller's onClick/enabled wiring exactly.
 */
@Composable
private fun OnboardingHeroButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}
