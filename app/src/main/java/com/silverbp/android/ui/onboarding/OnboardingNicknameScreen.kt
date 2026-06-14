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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.silverbp.android.BuildConfig
import com.silverbp.android.R
import com.silverbp.android.core.WeightReading
import com.silverbp.android.core.WeightSource
import com.silverbp.android.core.WeightUnit
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.legal.CURRENT_PRIVACY_POLICY_VERSION
import com.silverbp.android.settings.ExperienceLevel
import com.silverbp.android.settings.PrimaryGoal
import com.silverbp.android.settings.TrainingStyle
import com.silverbp.android.settings.UserSettingsRepository
import com.silverbp.android.settings.WeeklyAvailability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * First-launch flow:
 *   step 0  Welcome + medical disclaimer + privacy-policy consent (required).
 *   step 1  Notification permission request (Android 13+); skippable.
 *   step 2  Nickname capture.
 *   step 3  Your details — birth year / height / weight (all optional,
 *           skippable). Non-blank values are written to the owner member +
 *           a first weight reading when the step is left.
 *   step 4  Primary goal (goal profile, Phase 4).
 *   step 5  Experience level.
 *   step 6  Weekly availability (days/week).
 *   step 7  Training style — final step; persists the goal profile AND flips
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
    // Health-profile inputs (step 3). All optional; blank → nothing written.
    var birthYearText by rememberSaveable { mutableStateOf("") }
    var heightText by rememberSaveable { mutableStateOf("") }
    var weightText by rememberSaveable { mutableStateOf("") }
    // Weight entry unit; seeded from UserSettings.weightUnit in LaunchedEffect.
    var weightUnit by rememberSaveable { mutableStateOf(WeightUnit.Kg) }
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

    // Persist non-blank health-profile inputs when the "Your details" step is
    // left (via Next or Skip). Blank fields write nothing and never overwrite an
    // existing owner value with null — preserving anything already on the owner.
    // Fire-and-forget on the screen scope so navigation isn't blocked; invalid
    // (out-of-range) entries are treated as blank by the step's gating.
    fun persistProfile() {
        val parsedBirthYear = birthYearText.trim().toIntOrNull()
        val parsedHeight = heightText.trim().toIntOrNull()
        val parsedWeight = weightText.trim().toDoubleOrNull()
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
                    weightRepo.upsert(
                        WeightReading(
                            memberId = "",
                            weightKg = WeightReading.kgFrom(parsedWeight, weightUnit),
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
                    // In review mode the goal/profile questions are skipped — finish here.
                    onDone = { if (reviewMode) finish() else step = 3 },
                    onSkip = {
                        nickname = ""
                        if (reviewMode) finish() else step = 3
                    },
                )
                3 -> ProfileStep(
                    birthYearText = birthYearText,
                    onBirthYearChange = { birthYearText = it.filter(Char::isDigit).take(4) },
                    heightText = heightText,
                    onHeightChange = { heightText = it.filter(Char::isDigit).take(3) },
                    weightText = weightText,
                    onWeightChange = { weightText = it.filter { c -> c.isDigit() || c == '.' } },
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
 * "Your details" step (skippable): birth year / height / weight, all optional.
 * Mirrors the goal steps' scaffold (title + subtitle + card + Back) but renders
 * input fields instead of chips. Validation matches [com.silverbp.android.ui.member.MemberEditorSheet]:
 * birth year is a 4-digit year (blank ok), height is 50..300 cm (blank ok),
 * weight is any positive number (blank ok). Next and Skip both advance; the
 * caller persists the non-blank values. The primary button stays enabled even
 * when everything is blank — leaving the step is always allowed.
 */
@Composable
private fun ProfileStep(
    birthYearText: String,
    onBirthYearChange: (String) -> Unit,
    heightText: String,
    onHeightChange: (String) -> Unit,
    weightText: String,
    onWeightChange: (String) -> Unit,
    weightUnit: WeightUnit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    // Same blank-ok validity rules as MemberEditorSheet; an invalid (out-of-range)
    // entry shows an error and gates Next so we never persist a bad value.
    val birthYear = birthYearText.trim().toIntOrNull()
    val birthYearValid = birthYearText.isBlank() || (birthYear != null && birthYearText.trim().length == 4)
    val heightCm = heightText.trim().toIntOrNull()
    val heightValid = heightText.isBlank() || (heightCm != null && heightCm in 50..300)
    val weight = weightText.trim().toDoubleOrNull()
    val weightValid = weightText.isBlank() || (weight != null && weight > 0.0)
    val canAdvance = birthYearValid && heightValid && weightValid

    val unitLabel = stringResource(
        if (weightUnit == WeightUnit.Lb) R.string.weight_unit_lb else R.string.weight_unit_kg,
    )
    val birthYearCd = stringResource(R.string.onboarding_profile_birth_year_cd)
    val heightCd = stringResource(R.string.onboarding_profile_height_cd)
    val weightCd = stringResource(R.string.onboarding_profile_weight_cd, unitLabel)

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
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap)) {
                OutlinedTextField(
                    value = birthYearText,
                    onValueChange = onBirthYearChange,
                    label = { Text(stringResource(R.string.member_birth_year)) },
                    placeholder = { Text(stringResource(R.string.member_birth_year_placeholder)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = birthYearCd },
                    singleLine = true,
                    isError = !birthYearValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = if (!birthYearValid) {
                        { Text(stringResource(R.string.member_birth_year_invalid)) }
                    } else null,
                )
                OutlinedTextField(
                    value = heightText,
                    onValueChange = onHeightChange,
                    label = { Text(stringResource(R.string.member_height_label)) },
                    placeholder = { Text(stringResource(R.string.member_height_placeholder)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = heightCd },
                    singleLine = true,
                    isError = !heightValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = if (!heightValid) {
                        { Text(stringResource(R.string.member_height_invalid)) }
                    } else null,
                )
                OutlinedTextField(
                    value = weightText,
                    onValueChange = onWeightChange,
                    label = { Text(stringResource(R.string.onboarding_profile_weight, unitLabel)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = weightCd },
                    singleLine = true,
                    isError = !weightValid,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        OnboardingHeroButton(
            label = stringResource(R.string.onboarding_goal_next),
            onClick = onNext,
            enabled = canAdvance,
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
