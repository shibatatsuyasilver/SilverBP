export const meta = {
  name: 'release-readiness-audit',
  description: 'Deep multi-agent audit of SilverBP for Play Store release: bugs, functional gaps, and store compliance',
  whenToUse: 'Before submitting SilverBP to the Play Store — find blocking bugs, missing/incomplete features, and policy gaps.',
  phases: [
    { title: 'Recon', detail: 'build + unit tests; release-config / manifest / Room-schema audit' },
    { title: 'Bug Hunt', detail: '15 parallel subsystem finders (crashes, logic bugs, functional gaps)' },
    { title: 'Verify', detail: 'adversarial per-finding verification against the cited code' },
    { title: 'Compliance', detail: 'Play 2026 policy, permissions/Data-Safety, i18n, accessibility, privacy' },
    { title: 'Synthesis', detail: 'prioritized release-readiness report written to notes/' },
  ],
}

// ---------- schemas ----------
const FINDINGS_SCHEMA = {
  type: 'object',
  properties: {
    subsystem: { type: 'string' },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          title: { type: 'string' },
          severity: { type: 'string', enum: ['P0', 'P1', 'P2'] },
          category: {
            type: 'string',
            enum: ['crash', 'data-loss', 'functional-gap', 'logic-bug', 'security', 'release-blocker', 'perf', 'ux'],
          },
          file: { type: 'string' },
          line: { type: 'string' },
          evidence: { type: 'string' },
          reasoning: { type: 'string' },
          fix: { type: 'string' },
        },
        required: ['title', 'severity', 'category', 'file', 'evidence', 'reasoning', 'fix'],
      },
    },
  },
  required: ['subsystem', 'findings'],
}

const VERDICT_SCHEMA = {
  type: 'object',
  properties: {
    isReal: { type: 'boolean' },
    confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
    adjustedSeverity: { type: 'string', enum: ['P0', 'P1', 'P2', 'not-a-bug'] },
    note: { type: 'string' },
  },
  required: ['isReal', 'confidence', 'adjustedSeverity', 'note'],
}

const BUILD_SCHEMA = {
  type: 'object',
  properties: {
    compiles: { type: 'boolean' },
    unitTestsPass: { type: 'boolean' },
    failedTests: { type: 'array', items: { type: 'string' } },
    lintErrors: { type: 'array', items: { type: 'string' } },
    notRun: { type: 'array', items: { type: 'string' } },
    summary: { type: 'string' },
    blockers: { type: 'array', items: { type: 'string' } },
  },
  required: ['compiles', 'unitTestsPass', 'summary'],
}

const RELEASE_AUDIT_SCHEMA = {
  type: 'object',
  properties: {
    issues: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          title: { type: 'string' },
          severity: { type: 'string', enum: ['P0', 'P1', 'P2'] },
          area: { type: 'string' },
          detail: { type: 'string' },
          fix: { type: 'string' },
        },
        required: ['title', 'severity', 'area', 'detail', 'fix'],
      },
    },
  },
  required: ['issues'],
}

const COMPLIANCE_SCHEMA = {
  type: 'object',
  properties: {
    dimension: { type: 'string' },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          title: { type: 'string' },
          severity: { type: 'string', enum: ['P0', 'P1', 'P2'] },
          detail: { type: 'string' },
          action: { type: 'string' },
          source: { type: 'string' },
        },
        required: ['title', 'severity', 'detail', 'action'],
      },
    },
  },
  required: ['dimension', 'findings'],
}

// ---------- subsystem bug-hunt dimensions ----------
const DIMENSIONS = [
  {
    key: 'db',
    title: 'Database / Room / migrations',
    paths: 'app/src/main/java/com/silverbp/android/core/db/**, app/schemas/**, app/src/main/java/com/silverbp/android/**/db?/* mappers',
    focus:
      'SilverBpDatabase.kt declares version=17. Verify app/schemas/com.silverbp.android.core.db.SilverBpDatabase/ has exactly one <n>.json for EVERY version 1..17 (a missing schema breaks RoomMigrationTest and risks a release-time crash). Check every MIGRATION_* is registered and SQL matches the entity diff; nullability/defaults; destructive-migration fallbacks; index/foreign-key correctness; DAO query correctness (wrong column, missing ORDER BY, Flow vs suspend misuse).',
  },
  {
    key: 'bp-recognition',
    title: 'BP / machine / nutrition OCR recognition + on-device ML',
    paths: 'app/src/main/java/com/silverbp/android/recognition/** (excluding recognition/chat)',
    focus:
      'Model bootstrap/download/catalog lifecycle, AICore vs Gemma-local vs Gemini-cloud fallback selection (RecognizerFactory, DeviceCapabilities), graceful degradation on non-Pixel / low-RAM / no-network devices, parser robustness (BpResponseParser, MachineResponseParser, NutritionResponseParser) against malformed model output, image preprocessing, memory (largeHeap, OOM on big bitmaps), and any path that can crash or hang the capture flow.',
  },
  {
    key: 'exercise-tracking',
    title: 'Exercise / GPS / foreground service / step counter',
    paths: 'app/src/main/java/com/silverbp/android/exercise/**, app/src/main/java/com/silverbp/android/ui/exercise/**',
    focus:
      'LocationTrackingService foreground-service lifecycle + notification (Android 14+ FGS-location requirements), permission gating (fine/coarse/background location, ACTIVITY_RECOGNITION, POST_NOTIFICATIONS), StepCounterReader sensor availability, session live-store + checkpoint recovery after process death, RouteProjection math, pause/resume/stop correctness, battery/wakelock, and config-change survival.',
  },
  {
    key: 'strength',
    title: 'Strength training',
    paths: 'app/src/main/java/com/silverbp/android/strength/**, app/src/main/java/com/silverbp/android/ui/strength/**',
    focus:
      'Exercise library seed/repository, set/rep/weight logging, live workout store, session persistence across process death, summary math, and any empty/error state that is unhandled or a referenced feature that is not implemented.',
  },
  {
    key: 'nutrition',
    title: 'Nutrition / barcode / OpenFoodFacts',
    paths: 'app/src/main/java/com/silverbp/android/nutrition/**, app/src/main/java/com/silverbp/android/ui/nutrition/**',
    focus:
      'OpenFoodFactsClient network error/timeout/empty handling, barcode scan lifecycle + camera permission, nutrition DB lookups, draft holder state, confirm flow, and Health Connect nutrition write correctness.',
  },
  {
    key: 'coach',
    title: 'Coach engine + WorkManager + reminders + notifications',
    paths: 'app/src/main/java/com/silverbp/android/coach/**, app/src/main/java/com/silverbp/android/ui/coach/**',
    focus:
      'CoachEngine plan/task generation, all WorkManager workers (DailyReminder, MedicationReminder, WeeklyReport, BpAnomalyWatcher, Sleep/NutritionBackfill) for scheduling correctness, exact-alarm/notification-permission gating, doze/idle reliability, notification channels, MedicationActionReceiver (PendingIntent mutability flags, synchronous DB write), deep-link routing, and BP-anomaly gating logic.',
  },
  {
    key: 'sync',
    title: 'LAN sync (Noise / pairing / HLC / merge / transport)',
    paths: 'sync/src/main/java/com/silverbp/android/sync/**',
    focus:
      'Noise XK handshake + transport framing, pairing (SAS, QR), HLC clock correctness + monotonicity, OpLog/Merger CRDT conflict resolution (last-writer-wins correctness, lost updates, duplicate ops), NSD/Bonjour discovery lifecycle, codec round-trip, partial-message/timeout/disconnect handling, and any way two devices can diverge or corrupt data.',
  },
  {
    key: 'backup',
    title: 'Backup + recovery code + Drive auto-backup',
    paths: 'app/src/main/java/com/silverbp/android/backup/**',
    focus:
      'BackupCodec/Container/Crypto round-trip + version compatibility, Argon2id recovery-code KDF, restore-on-wrong-code handling, SilverBpBackupAgent interaction with SQLCipher exclusion, Drive auto-backup (GoogleAuthClient token refresh, GoogleDriveBackupClient appDataFolder, AutoBackupWorker scheduling/retry/error), and any path that can silently lose or corrupt a backup.',
  },
  {
    key: 'security',
    title: 'Security (SQLCipher / Keystore / biometric lock)',
    paths: 'app/src/main/java/com/silverbp/android/security/**, app/src/main/java/com/silverbp/android/ui/lock/**',
    focus:
      'DbCipherMigration (plaintext→encrypted) correctness + failure recovery, DbKeyStore/KeystoreStringCipher Keystore key handling (key invalidation after biometric enrollment change, StrongBox fallback), LockManager lifecycle (lock-on-background, timeout), biometric prompt error/cancel handling, and any way at-rest encryption can be bypassed or the DB key lost (= permanent data loss).',
  },
  {
    key: 'health-connect',
    title: 'Health Connect bridges + permissions',
    paths: 'app/src/main/java/com/silverbp/android/health/**',
    focus:
      'HealthConnectBridge/BpBridge/NutritionBridge availability checks (HC app not installed / old version), permission request + denial handling, READ_HEALTH_DATA_IN_BACKGROUND worker behavior (BpSyncWorker), PermissionsRationaleActivity intent-filter correctness, write de-duplication, and graceful behavior when HC is unavailable.',
  },
  {
    key: 'chat',
    title: 'Chat assistant + chat recognizers',
    paths: 'app/src/main/java/com/silverbp/android/chat/**, app/src/main/java/com/silverbp/android/ui/chat/**, app/src/main/java/com/silverbp/android/recognition/chat/**',
    focus:
      'Chat session/transcript persistence, records-context builder (PII leakage into prompts?), AICore/Gemma/Gemini chat recognizer fallback, voice (RECORD_AUDIO / SpeechRecognizer) lifecycle + permission, streaming/cancel, title generation, IME inset handling (the adjustNothing+imePadding note), and unhandled error/empty states.',
  },
  {
    key: 'achievements',
    title: 'Achievements / medals / step sync',
    paths: 'app/src/main/java/com/silverbp/android/achievements/**, app/src/main/java/com/silverbp/android/ui/achievements/**',
    focus:
      'AchievementEvaluator rule correctness, StepSyncWorker + StepBaselineStore (double-counting, baseline reset, day boundary/timezone), MedalNotifier channel + permission, store consistency, and medal unlock edge cases.',
  },
  {
    key: 'ui-nav-state',
    title: 'Navigation / Compose state / lifecycle',
    paths: 'app/src/main/java/com/silverbp/android/ui/nav/**, app/src/main/java/com/silverbp/android/MainActivity.kt, app/src/main/java/com/silverbp/android/ui/SilverBpApp.kt',
    focus:
      'AppNavHost routes + arguments (type safety, missing args → crash), DeepLink parsing, back-stack correctness, ViewModel scoping + state survival across config change / process death, recomposition perf, edge-to-edge + IME insets, and any navigation dead-end or unreachable destination.',
  },
  {
    key: 'settings-onboarding-di',
    title: 'Settings / onboarding / DI / app init',
    paths: 'app/src/main/java/com/silverbp/android/settings/**, app/src/main/java/com/silverbp/android/ui/onboarding/**, app/src/main/java/com/silverbp/android/ui/settings/**, app/src/main/java/com/silverbp/android/di/ServiceLocator.kt, app/src/main/java/com/silverbp/android/SilverBpApplication.kt, app/src/main/java/com/silverbp/android/ui/capture/**',
    focus:
      'ServiceLocator init order + leaks (Application context vs Activity), first-launch Google sign-in gate (memory says login is required — verify it is actually enforced and not bypassable), onboarding goal-profile flow completeness, settings persistence (DataStore), theme switching, and capture-flow holder lifecycle.',
  },
  {
    key: 'insights-reporting',
    title: 'Insights / analytics / reporting / history / today',
    paths: 'app/src/main/java/com/silverbp/android/analytics/**, app/src/main/java/com/silverbp/android/reporting/**, app/src/main/java/com/silverbp/android/sharing/**, app/src/main/java/com/silverbp/android/ui/insights/**, app/src/main/java/com/silverbp/android/ui/report/**, app/src/main/java/com/silverbp/android/ui/history/**, app/src/main/java/com/silverbp/android/ui/today/**',
    focus:
      'StatsEngine math (averages, windows, empty-data divide-by-zero), HypertensionGuideline/GuidelineClassifier thresholds, PdfReportRenderer (OOM on large datasets, layout overflow), SharePdf FileProvider URI correctness, chart rendering with zero/one data point, and date/timezone handling in history grouping.',
  },
]

function finderPrompt(d) {
  return [
    `You are auditing the SilverBP Android app (Kotlin/Compose/Room) for a FIRST Play Store release (versionCode 1).`,
    `Repo root: /Users/tatsuyashiba/Documents/SilverBP (your working directory).`,
    ``,
    `SUBSYSTEM: ${d.title}`,
    `Read these files with Read/Grep/Glob: ${d.paths}`,
    ``,
    `What to find — material, release-relevant issues only:`,
    `- Crashes / NPEs / uncaught exceptions on real device paths`,
    `- Data loss or corruption`,
    `- Logic bugs that produce wrong results`,
    `- FUNCTIONAL GAPS: features referenced in UI/code but not implemented, missing error/empty/loading states, unhandled permission denials, missing offline handling, dead buttons, TODO-shaped gaps`,
    `- Security issues`,
    `- Release blockers specific to this subsystem`,
    ``,
    `Focus guidance: ${d.focus}`,
    ``,
    `Rules:`,
    `- Read the actual code before claiming anything. Cite real file paths and line numbers/snippets as evidence.`,
    `- Report at most ~10 of the MOST material issues. Skip style nitpicks and pure refactors.`,
    `- Severity: P0 = blocks release (crash, data loss, security, broken core flow); P1 = should fix before release (wrong results, bad UX on common path, missing key error handling); P2 = post-launch.`,
    `- For each, give a concrete one-paragraph fix.`,
    `If the subsystem is genuinely solid, return an empty findings array — do not invent issues.`,
  ].join('\n')
}

function verifyPrompt(f, d) {
  return [
    `You are an adversarial verifier. A prior audit agent claims the following issue in the SilverBP Android app.`,
    `Repo root: /Users/tatsuyashiba/Documents/SilverBP (your working directory). OPEN THE CITED FILE and read the surrounding code before judging.`,
    ``,
    `Subsystem: ${d.title}`,
    `Claim title: ${f.title}`,
    `Severity claimed: ${f.severity}  Category: ${f.category}`,
    `File: ${f.file}  Line: ${f.line || '(unspecified)'}`,
    `Evidence given: ${f.evidence}`,
    `Reasoning given: ${f.reasoning}`,
    `Proposed fix: ${f.fix}`,
    ``,
    `Your job: try to REFUTE this. Read the real code. Decide:`,
    `- isReal: is this an actual problem on a real device path (not hypothetical, not already handled elsewhere)?`,
    `- adjustedSeverity: re-rate P0/P1/P2, or 'not-a-bug' if it is wrong/already-handled/non-issue.`,
    `- note: one or two sentences citing what you saw in the code that confirms or refutes it.`,
    `Default to skepticism: if after reading the code you cannot confirm a real impact, mark isReal=false / not-a-bug.`,
  ].join('\n')
}

const BUILD_PROMPT = [
  `You are verifying build + test health of the SilverBP Android app before a Play Store release.`,
  `Working directory: /Users/tatsuyashiba/Documents/SilverBP. You are the ONLY agent running Gradle — no other process will touch the build dir.`,
  ``,
  `Run, with --console=plain, and capture results:`,
  `1. ./gradlew :app:testDebugUnitTest :sync:test   (JVM unit tests — 40 test files)`,
  `2. ./gradlew :app:lintDebug                       (Android Lint; report error-severity issues)`,
  ``,
  `Notes:`,
  `- Do NOT run connectedAndroidTest / bundleRelease (no device, and release build requires MAPS_API_KEY + keystore). Record those as notRun with the reason.`,
  `- testDebugUnitTest compiling implies the app module compiles; if it fails to compile, report compiles=false with the error.`,
  `- List each failing test fully-qualified, and each lint error-severity finding (id + file).`,
  `- Builds can take several minutes; be patient and let them finish.`,
  `Return the structured result. summary = 2-3 sentences on overall health.`,
].join('\n')

const RELEASE_PROMPT = [
  `You are auditing release configuration & manifest for the SilverBP Android app's FIRST Play Store submission.`,
  `Working directory: /Users/tatsuyashiba/Documents/SilverBP. Read (do not build):`,
  `- app/build.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, settings.gradle.kts, gradle.properties`,
  `- app/src/main/AndroidManifest.xml, app/proguard-rules.pro`,
  `- app/src/main/res/xml/* (data_extraction_rules, backup_rules, locales_config, file_paths)`,
  `- app/schemas/com.silverbp.android.core.db.SilverBpDatabase/ (list it) vs the version in core/db/SilverBpDatabase.kt`,
  `- RELEASE.md, docs/privacy.html`,
  ``,
  `Check for release blockers and gaps:`,
  `- versionCode/versionName, minSdk(33)/targetSdk(36)/compileSdk — Play target-API compliance for 2026.`,
  `- Exported components correctness; android:exported on every activity/receiver/service/provider with an intent-filter.`,
  `- Room schema completeness: every DB version 1..N must have a committed <n>.json (a missing one is a blocker). Report any gap explicitly.`,
  `- ProGuard/R8 keep rules for kotlinx.serialization, Room, reflection, model libs, CBOR/Noise sync types — missing keep rules → release-only crashes.`,
  `- Permissions declared vs actually used; dangerous/policy-sensitive perms (background location, health, RECORD_AUDIO).`,
  `- Backup rules vs SQLCipher exclusion correctness; cleartext traffic; native lib requirements.`,
  `- Maps API key handling, FileProvider paths.`,
  `Report each as an issue with severity (P0 blocks submission). Be concrete with file + fix.`,
].join('\n')

// ---------- compliance / store-readiness dimensions ----------
const COMPLIANCE = [
  {
    key: 'play-policy',
    title: 'Google Play 2026 policy compliance',
    prompt: [
      `Research CURRENT (2025-2026) Google Play Store submission requirements using WebSearch/WebFetch, then map them to the SilverBP app.`,
      `SilverBP facts: new app, first submission; Android; minSdk 33 / targetSdk 36; collects blood-pressure & health data; uses background/foreground LOCATION for workout GPS; RECORD_AUDIO for chat voice; CAMERA for OCR; Health Connect read/write; on-device + cloud (Gemini) AI; encrypted Google Drive backup; Google sign-in required at first launch.`,
      `Investigate and report concrete required actions for:`,
      `- Target API level requirement for new apps in 2026 (is 36 sufficient/required?).`,
      `- Data Safety form expectations for health/BP data + AI processing + cloud backup.`,
      `- Background location access: the in-app declaration + Play Console permissions declaration form + video requirement.`,
      `- Foreground service (location type) Play policy declaration.`,
      `- Health apps policy / Health Connect declaration requirements & restricted-permission review.`,
      `- HEALTH / medical-disclaimer & "not a medical device" requirements for a BP app.`,
      `- New personal developer accounts: 12-tester / 14-day closed-testing requirement before production — confirm whether it applies.`,
      `- Account-deletion requirement (Google sign-in → must offer in-app + web data deletion).`,
      `- Sensitive/AI content, ads ID, and privacy-policy URL requirements.`,
      `For each, give severity (P0 = will block/reject submission) and the exact action. Cite source URLs in 'source'.`,
    ].join('\n'),
  },
  {
    key: 'permissions-datasafety',
    title: 'Permissions ↔ Data Safety mapping',
    prompt: [
      `Working directory: /Users/tatsuyashiba/Documents/SilverBP. Read app/src/main/AndroidManifest.xml and grep the codebase for where each dangerous permission is actually used.`,
      `For EACH manifest permission (CAMERA, INTERNET, location fine/coarse/background-via-FGS, ACTIVITY_RECOGNITION, RECORD_AUDIO, POST_NOTIFICATIONS, all health.* perms, wifi/multicast/network-state), report: is it actually used? where? is its runtime request + denial handled? does it map to a Data Safety disclosure?`,
      `Flag: any permission declared but unused (remove before release), any dangerous permission requested without a runtime rationale/denial path, and any data type collected that must be disclosed in Data Safety (health/BP, location, audio, photos, identifiers). Severity per item; concrete action each.`,
    ].join('\n'),
  },
  {
    key: 'localization',
    title: 'Localization (en + zh-TW) parity & hardcoded strings',
    prompt: [
      `Working directory: /Users/tatsuyashiba/Documents/SilverBP. The app ships en (values/strings.xml, 768 lines) + zh-TW (values-zh-rTW/strings.xml, 768 lines).`,
      `Check: (1) every string name in one locale exists in the other (report missing/orphan keys); (2) grep Compose UI (ui/**) for hardcoded user-facing literals — Text("..."), contentDescription="...", placeholder/label literals — that should be stringResource; (3) any %s/%d/plural format mismatches between locales; (4) notification/worker/PDF user-facing text that is hardcoded.`,
      `Report each gap with file + the missing/hardcoded string. Severity: P1 for visible-to-user untranslated strings, P2 for minor.`,
    ].join('\n'),
  },
  {
    key: 'accessibility',
    title: 'Accessibility for elderly users',
    prompt: [
      `Working directory: /Users/tatsuyashiba/Documents/SilverBP. SilverBP's primary users are ELDERLY (per RELEASE.md staged-rollout note). Audit Compose UI (ui/**) and theme (ui/theme/**) for accessibility.`,
      `Check: contentDescription on icons/images/icon-buttons (TalkBack), touch-target sizes (>=48dp), font-scaling support (sp usage, layouts that break at large font scale), color contrast of BP-category colors + the dark FORGE theme (purple/lime) against WCAG, tap areas, and any text too small for elderly users.`,
      `Report concrete issues with file + element. Severity P1 for things that block elderly usability, P2 otherwise.`,
    ].join('\n'),
  },
  {
    key: 'privacy-data',
    title: 'Privacy policy vs actual data practices',
    prompt: [
      `Working directory: /Users/tatsuyashiba/Documents/SilverBP. Read docs/privacy.html and app/src/main/java/com/silverbp/android/legal/PrivacyPolicy.kt, then compare against what the app ACTUALLY does (grep for: Gemini cloud calls, OpenFoodFacts network, Google Drive backup, Health Connect, Google sign-in, on-device ML, LAN sync).`,
      `Check the privacy policy discloses: BP/health data handling, cloud AI processing (Gemini) of user images/data, third-party services (Google, OpenFoodFacts), data stored/transmitted, retention, deletion rights, and children's data. Flag anything the app does that the policy omits, and any policy claim the code contradicts.`,
      `Report each gap with severity (P0 if it would fail Data Safety / Play review) and the exact text/section to add or fix.`,
    ].join('\n'),
  },
]

function synthPrompt(build, releaseAudit, confirmed, complianceFindings) {
  return [
    `You are the lead release engineer for SilverBP. Synthesize a prioritized Play Store release-readiness report from the audit data below.`,
    `Working directory: /Users/tatsuyashiba/Documents/SilverBP.`,
    ``,
    `=== BUILD/TEST HEALTH ===`,
    JSON.stringify(build, null, 2),
    ``,
    `=== RELEASE CONFIG / MANIFEST / SCHEMA AUDIT ===`,
    JSON.stringify(releaseAudit, null, 2),
    ``,
    `=== CONFIRMED CODE FINDINGS (verified real; each has a verdict) ===`,
    JSON.stringify(confirmed, null, 2),
    ``,
    `=== COMPLIANCE / STORE-READINESS FINDINGS ===`,
    JSON.stringify(complianceFindings, null, 2),
    ``,
    `Write a Markdown report to notes/release-readiness-audit.md with these sections:`,
    `1. Executive summary — is the app ready to submit? top 3-5 risks.`,
    `2. P0 — Release blockers (MUST fix before submitting): merged list from code + config + compliance, each with file/area + the fix. Deduplicate overlapping items.`,
    `3. P1 — Should fix before release.`,
    `4. P2 — Post-launch backlog.`,
    `5. Build & test status (compiles? tests pass? what could not be run).`,
    `6. Play Store submission checklist — actionable steps (Data Safety, declarations, account deletion, closed-testing 12/14, store listing, signing/Maps key) drawn from the compliance findings.`,
    `Use a severity-sorted table for P0/P1. Be specific and cite files. Do not pad.`,
    ``,
    `After writing the file, return a concise plain-text summary for the user (in 繁體中文): the readiness verdict, the count of P0/P1/P2, and the single most important blocker. Mention the report path.`,
  ].join('\n')
}

// ================= orchestration =================
phase('Recon')
log('Recon: building + running unit tests, and auditing release config — both run while the bug-hunt fans out.')
// Kick off the two recon agents but DO NOT await yet — they run concurrently with Bug Hunt + Compliance.
const buildPromise = agent(BUILD_PROMPT, { schema: BUILD_SCHEMA, phase: 'Recon', label: 'build+unit-tests', model: 'opus' })
const releasePromise = agent(RELEASE_PROMPT, { schema: RELEASE_AUDIT_SCHEMA, phase: 'Recon', label: 'release-config-audit', model: 'opus' })

phase('Bug Hunt')
log(`Bug Hunt: ${DIMENSIONS.length} subsystem finders, each verified adversarially as it returns.`)
// Pipeline: each subsystem is found, then its findings are verified — no barrier between subsystems.
const bugPipeline = pipeline(
  DIMENSIONS,
  (d) => agent(finderPrompt(d), { schema: FINDINGS_SCHEMA, phase: 'Bug Hunt', label: `find:${d.key}`, model: 'opus' }),
  (review, d) => {
    if (!review || !Array.isArray(review.findings) || review.findings.length === 0) return []
    return parallel(
      review.findings.map((f) => () =>
        agent(verifyPrompt(f, d), { schema: VERDICT_SCHEMA, phase: 'Verify', label: `verify:${d.key}`, model: 'opus' })
          .then((v) => ({ ...f, subsystem: d.title, verdict: v }))
      )
    )
  }
)

// Compliance runs concurrently with the whole bug-hunt pipeline.
const compliancePromise = parallel(
  COMPLIANCE.map((c) => () => agent(c.prompt, { schema: COMPLIANCE_SCHEMA, phase: 'Compliance', label: `comp:${c.key}`, model: 'opus' }))
)

// Single barrier: wait for everything that feeds synthesis.
const [verifiedNested, complianceRaw, build, releaseAudit] = await Promise.all([
  bugPipeline,
  compliancePromise,
  buildPromise,
  releasePromise,
])

const allFindings = (verifiedNested || []).filter(Boolean).flat().filter(Boolean)
const confirmed = allFindings.filter(
  (f) => f.verdict && f.verdict.isReal && f.verdict.adjustedSeverity !== 'not-a-bug'
)
const rejected = allFindings.filter(
  (f) => !(f.verdict && f.verdict.isReal) || (f.verdict && f.verdict.adjustedSeverity === 'not-a-bug')
)
const complianceFindings = (complianceRaw || []).filter(Boolean)
log(`Candidates: ${allFindings.length} → confirmed: ${confirmed.length}, rejected: ${rejected.length}. Compliance dims: ${complianceFindings.length}.`)

phase('Synthesis')
const summary = await agent(synthPrompt(build, releaseAudit, confirmed, complianceFindings), {
  phase: 'Synthesis',
  label: 'synthesize-report',
  model: 'opus',
})

return { build, releaseAudit, confirmed, rejected, complianceFindings, summary }
