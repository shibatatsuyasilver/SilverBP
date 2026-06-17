# SilverBP — QA Fix Plan

A remediation backlog for the 37-item QA report. Every item was verified against the
codebase by two independent read-only passes (an auditor + an adversarial cross-check).

**Status of verification: 36 / 37 confirmed real (high confidence). 1 (P0-4) is real but
its impact was overstated** — see its note. Several sync items are already self-documented
in code comments as "MVP / Phase-1 non-goal / lands in Phase 2".

Legend: `[ ]` to do · ✅ verified-real · 🟡 real defect, impact overstated.
Line numbers are the verified locations (some differ slightly from the original report).

---

## 1. Sync data-loss core — highest risk (silent multi-device corruption)

### [ ] P0-1 ✅ Local HLC is never persisted; stale peer copies overwrite newer local edits
- **Problem:** `BpReading.toEntity()` never sets `hlcUpdatedAt`, so it defaults to `"0"`.
  Sync mints a fresh HLC transiently in `hlcFor()` but never writes it back. On inbound
  merge the local row still reads `"0"`, which `resolveLocalHlc()` treats as "no local
  trace" → the LWW gate accepts any incoming record and overwrites local data.
- **Files:** `core/db/Mappers.kt:20`, `core/db/Entities.kt:36`,
  `sync/RoomSyncAdapters.kt:340` (mint) & `:612` (gate), `sync/LwwTables.kt:63`.
- **Fix:** Stamp `hlcUpdatedAt` from the shared HLC clock on **every local insert/update**
  (in the repositories / DAO upsert path), instead of leaving `"0"`. When `hlcFor()` does
  mint, persist it back to the row. Add a regression test next to
  `LwwGateIntegrationTest.pre_sync_zero_hlc_loses_to_real_incoming`.

### [ ] P0-2 ✅ Local deletes write no tombstone → records resurrected on sync/merge
- **Problem:** `BpRepository.delete()` and the Weight/Glucose/Exercise/Nutrition repos call
  `dao.delete()` directly. Tombstones are only created in the sync mappers' `apply()` when
  receiving an inbound tombstone. The intended design is documented but unbuilt, so a
  peer's still-present row with a newer HLC restores a locally-deleted record.
- **Files:** `core/BpRepository.kt:96` (+ Weight/Glucose/Exercise/Nutrition repos),
  `core/db/SyncEntities.kt:8` (design note), `sync/RoomSyncAdapters.kt:616` (`tombstoneFor`).
- **Fix:** On every local delete, within one transaction, delete the origin row **and**
  insert a tombstone (current HLC) — reuse the tombstone-write logic the mappers already
  use on inbound. Cover all five domains (BP, glucose, weight, food, exercise).

### [ ] P0-5 ✅ Merge can create multiple owners; `getOwner LIMIT 1` then picks arbitrarily
- **Problem:** `MemberSyncMapper.apply()` calls `memberDao.upsert()` directly, bypassing
  `MemberRepository.upsert()`'s demote-on-owner logic; Merge mode doesn't clear the member
  table. Importing an `isOwner=true` row with a different id leaves two owners; `getOwner`
  returns an arbitrary one. (Code comment marks this a "Phase 1 non-goal".)
- **Files:** `sync/MemberSyncMapper.kt:122/139`, `core/.../MemberRepository.kt:76`,
  `core/db/MemberDao.kt` (`getOwner … LIMIT 1`).
- **Fix:** Route member apply through the demote-on-owner path (or post-merge reconcile to
  exactly one owner: keep local owner, import the other as non-owner). Make `getOwner`
  deterministic (`ORDER BY createdAt`).

### [ ] P1-16 ✅ Dose dedupe key ignores scheduleId/minute → same-hour reminders merged
- **Problem:** `MedicationDoseEntity` stores only `scheduledHour`; `findByContent(dayStart,
  medId, hour)` collides two reminders in the same hour (e.g. 10:00 & 10:30). Local code
  uses a deterministic `med-$dayStart-$scheduleId` id, but sync drops both minute & scheduleId.
- **Files:** `sync/Phase2Mappers.kt:442`, `core/db/CoachEntities.kt:85`, `core/db/CoachDao.kt:188`.
- **Fix:** Add `minute` (and/or `scheduleId`) to the dose entity, the sync payload, and the
  dedupe key; carry `scheduleId` through sync so the deterministic id survives. Room migration to add the column.

### [ ] P1-18 ✅ `recordsSince(peerLastHlcSeen)` ignores the watermark; trailing tables starve
- **Problem:** The `peerLastHlcSeen` param is never referenced; `recordsSince()` emits a
  fixed 16-table order capped only by `limit`, so a data-heavy early table (e.g. GPS
  `route_point`, emitted last) can be starved for many rounds. (Comment: deferred to Phase 2.)
- **Files:** `sync/RoomSyncAdapters.kt:145/152`, `protocol/SyncSession.kt:69`.
- **Fix:** Filter by `peerLastHlcSeen` (only rows with `hlcUpdatedAt > watermark`) and/or
  round-robin across tables so no table is starved.

---

## 2. "v20 weight feature half-wired"

### [ ] P0-3 ✅ Replace restore never clears `weight_log` → old weight rows survive
- **Problem:** `clearSyncTables()` deletes 24 tables + tombstone but omits `weight_log`
  (the 25th sync table, tag 25); it also has **no FK CASCADE**, so it is fully orphaned and
  old weight rows remain after a Replace restore.
- **Files:** `backup/BackupManager.kt:336` (`clearSyncTables`).
- **Fix:** Add `db.execSQL("DELETE FROM weight_log")`; update the "清空 24 個 sync 表" comment to 25.

### [ ] P0-4 🟡 Backup metadata writes `schemaVersion = 19` while DB is v20
- **Problem:** Real mismatch — `SilverBpDatabase.kt:45` is `version = 20`, but
  `ServiceLocator.kt:605` hardcodes `schemaVersion = 19` (with a comment to keep them in
  lock-step). **Impact correction:** import does **not** gate on the header version, so v20
  weight data still exports/imports fine. This is a **metadata-accuracy bug, not data loss**.
- **Files:** `di/ServiceLocator.kt:605`.
- **Fix:** Set `schemaVersion = 20` (one-line). No data migration needed.

### [ ] P1-24 ✅ Today weight card hardcodes `count = 0`; history screen unreachable
- **Problem:** `count = 0` is hardcoded; "查看全部" only renders when `count > 1`, so the
  (registered & wired) weight-history route can never be opened from the UI.
- **Files:** `ui/today/TodayScreen.kt:536` (card) & `:601` (button gate),
  `ui/nav/AppNavHost.kt:271/581` (route+callback already exist).
- **Fix:** Feed the real weight count (observe `weightRepository`) into `WeightMiniCard`.

### [ ] P1-28 ✅ Weight edit screen has no delete action (BP/Glucose do)
- **Problem:** `ConfirmWeightScreen` TopAppBar has only Cancel + Save; BP/Glucose screens
  show a conditional delete `IconButton` when editing.
- **Files:** `ui/confirm/ConfirmWeightScreen.kt:182`; pattern in
  `ConfirmReadingScreen.kt:114` & `ConfirmGlucoseScreen.kt:125`.
- **Fix:** Add a delete `IconButton` (edit mode) + confirm dialog + `weightRepository.delete`,
  mirroring the BP/Glucose screens. (Combine with P0-2's tombstone-on-delete.)

### [ ] P1-17 ✅ Exercise sync/backup drops `activeDurationMillis` + machine OCR fields
- **Problem:** The frozen iOS-byte-compatible wire layout (tags 1..12) omits
  `activeDurationMillis`; on a new device it falls back to wall-clock. HR, estimate flags,
  distance unit, floors, and `rawMetricsJson` stay device-local (only `caloriesKcal` syncs).
- **Files:** `sync/Phase2Mappers.kt:146`.
- **Fix:** Carry the missing fields. **Caution:** the 1..12 wire is frozen for iOS parity —
  add them as new tags (≥13) in a backward-compatible extension, or at minimum include them
  in the (non-wire) backup payload. Needs a small protocol design note before coding.

---

## 3. Health Connect

### [ ] P0-6 ✅ Main HC grant omits glucose permission; callback only checks READ_STEPS
- **Problem:** `hcCorePerms` excludes `glucoseBridge.permissions`; the grant callback only
  validates `READ_STEPS`, so `enableHealthConnect` flips true while glucose never syncs
  (`GlucoseSyncWorker` silently `return success` when the write perm is missing).
- **Files:** `ui/settings/SettingsScreen.kt:131`, `ui/settings/SettingsViewModel.kt:96`,
  `.../GlucoseSyncWorker.kt:46`.
- **Fix:** Include glucose permissions in the requested set; validate the **full** requested
  set in the callback (not just steps); only enqueue `GlucoseSyncWorker` once glucose is granted.

### [ ] P1-22 ✅ Exercise → Health Connect write is one-shot best-effort, no retry worker
- **Problem:** `ExerciseRepository.upsert()` calls `healthConnect.write()` once; on failure
  it just returns null. Unlike BP/Weight/Glucose there's no `findUnmirrored()` query and no
  `ExerciseSyncWorker`, so failed writes never retry.
- **Files:** `exercise/ExerciseRepository.kt:65`.
- **Fix:** Add `ExerciseDao.findUnmirrored()` + `ExerciseSyncWorker`, enqueued on cold start,
  mirroring the existing `BpSyncWorker`/`WeightSyncWorker` pattern.

### [ ] P2-33 ✅ HC rationale strings omit the weight read/write reason
- **Problem:** Manifest declares READ/WRITE_WEIGHT and the bridge uses both, but
  `hc_rationale_read_label`/`hc_rationale_write_label` (en + zh-rTW) list everything except weight.
- **Files:** `res/values/strings.xml:810/812`, `res/values-zh-rTW/strings.xml:810/812`.
- **Fix:** Add weight to both rationale strings in both locales.

---

## 4. Capture / recognition robustness

### [ ] P0-10 ✅ Capture screens never unbind CameraX on exit (camera stays held)
- **Problem:** BP/Glucose/Weight capture screens bind to the **Activity** lifecycle and
  never unbind on disposal; the camera stays occupied until the next `unbindAll()`.
  `BarcodeScanScreen` already does this correctly.
- **Files:** `ui/capture/CaptureScreen.kt:107`, `GlucoseCaptureScreen.kt:117`,
  `WeightCaptureScreen.kt:118`; reference `BarcodeScanScreen.kt:94`.
- **Fix:** Add `DisposableEffect(Unit){ onDispose { cameraProvider.unbindAll() } }` to all three.

### [ ] P0-11 ✅ Full-resolution decode on the main executor → ANR/OOM
- **Problem:** Glucose/Weight/Machine capture call `decodeFileWithExif()` on
  `getMainExecutor`; the function uses `BitmapFactory.decodeFile` with no `inSampleSize`.
  BP capture already decodes on a background executor.
- **Files:** `recognition/ImagePreprocess.kt:62` (decode); callers
  `GlucoseCaptureScreen.kt:365`, `WeightCaptureScreen.kt:366`, `MachineCaptureScreen.kt:345`.
- **Fix:** Decode off the main thread (background executor, like BP) and add `inSampleSize`
  downsampling in `decodeFileWithExif` for all callers.

### [ ] P0-13 ✅ Resume download: HTTP 200 still appended to old `.part` → corrupt model
- **Problem:** When a `.part` exists, a `Range` header is sent, but `check(isSuccessful)`
  accepts `200 OK` (full file from byte 0) and the file is opened in **append** mode, so the
  whole file is appended onto the partial → corruption.
- **Files:** `recognition/ModelDownloader.kt:58` (Range), `:60` (check), `:64` (append).
- **Fix:** If a Range was sent, require `response.code == 206`; on `200`, truncate (open with
  `append=false`) and restart from 0, recomputing total length.

### [ ] P1-19 ✅ AICore readiness is treated as always-ready → blank manual confirm
- **Problem:** `ready = !backendIsLocal || phase is Ready` short-circuits to `true` for
  AICore regardless of load phase, so the shutter enables before the model is warmed; if
  `isLoaded()` is false the flow drops to a blank manual draft.
- **Files:** `recognition/RecognitionReadiness.kt:11/20`; consumers in `CaptureScreen`/`CaptureFlowViewModel`.
- **Fix:** Make readiness reflect the actual AICore load phase (don't short-circuit on
  `!backendIsLocal`); gate the shutter on `isLoaded()`/`phase == Ready`.

---

## 5. Privacy / security disclosure

### [ ] P0-7 ✅ `app_lock_enabled` restored without an auth-capability check → lockout
- **Problem:** The flag is backed up; on cold start `SilverBpApp` shows `LockScreen`
  unconditionally and `LockScreen` calls `BiometricPrompt.authenticate()` with no
  `canDeviceAuthenticate()` guard and no escape. Restored to a device with no screen lock,
  the user is locked out (and the DB key may be gated on it).
- **Files:** `ui/SilverBpApp.kt:67`, `ui/.../LockScreen.kt`, check exists at
  `ui/settings/SettingsViewModel.kt:280`.
- **Fix:** Before showing the lock at cold start, run `canDeviceAuthenticate()`; if the
  device can't authenticate, auto-disable app lock (and decrypt) or present an escape path.

### [ ] P0-8 ✅ System Auto Backup ships the health DB/DataStore, contradicting the policy
- **Problem:** `allowBackup=true` + `data_extraction_rules.xml` cloud-backup include
  `silverbp.db*` and the DataStore. The privacy policy says health data leaves the device
  **only** via the user-initiated Drive backup.
- **Files:** `AndroidManifest.xml:69`, `res/xml/data_extraction_rules.xml:18`, `docs/privacy.html:88`.
- **Fix (recommended):** Exclude the health DB/DataStore from system cloud-backup (the app
  already has its own encrypted Drive backup), so the disclosure stays true — **or** update
  the privacy policy to disclose system Auto Backup explicitly.

### [ ] P1-20 ✅ Recognizer raw output is logged → health values reach logcat
- **Problem:** `GeminiCloudRecognizer` logs the full JSON (systolic/diastolic/pulse) at
  INFO. (Nuance: Gemma/AICore only log `.take(200/500)` truncated output.)
- **Files:** `recognition/GeminiCloudRecognizer.kt:122/126` (+ Gemma/AICore service logs).
- **Fix:** Guard health-value logs behind `BuildConfig.DEBUG` or redact the values.

### [ ] P2-34 ✅ Store copy claims "AES-256 by default" but encryption is opt-in/off
- **Problem:** `appLockEnabled` defaults false; the DB is plain SQLite unless the user opts
  in (SQLCipher). The store listing claims default AES-256; `privacy.html` correctly says off-by-default.
- **Files:** `settings/UserSettings.kt:155`, `core/db/SilverBpDatabase.kt:117`, `RELEASE.md` store copy.
- **Fix:** Correct the store/marketing copy to "optional at-rest encryption" to match
  `privacy.html` (or change the default — product decision).

### [ ] P2-32 ✅ No in-app Terms entry though `docs/terms.html` exists
- **Problem:** Paywall and Settings→About have no Terms link; there's no `TERMS_POLICY_URL`;
  `docs/terms.html` is never referenced.
- **Files:** `ui/paywall/PaywallSheet.kt:217`, `ui/settings/SettingsScreen.kt` About,
  `app/build.gradle.kts` (`PRIVACY_POLICY_URL` only).
- **Fix:** Add `TERMS_POLICY_URL` `buildConfigField` + a Terms button in Paywall and About,
  mirroring the existing Privacy Policy link.

---

## 6. Release / build gating

### [ ] P2-30 ✅ `PREMIUM_ENFORCED=false` not overridden in release → everything unlocked
- **Problem:** `defaultConfig` sets it false with no `release` override; `EntitlementManager`
  returns `isPremium()=true` when not enforced. `RELEASE.md` flags this as a pre-charge TODO.
- **Files:** `app/build.gradle.kts:50/54` (defaultConfig) & release buildType (~`:72`),
  `.../EntitlementManager.kt:175`.
- **Fix:** Add `buildConfigField("boolean","PREMIUM_ENFORCED","true")` to the `release` buildType
  before charging users.

### [ ] P2-31 ✅ Release signing has no keystore gate → debug-signed release possible
- **Problem:** `if (hasReleaseSigning) { … }` silently falls back to debug signing when any
  keystore prop is missing; unlike `MAPS_API_KEY` (which throws `GradleException`) there is no guard.
- **Files:** `app/build.gradle.kts:80`; compare validation at `:214`.
- **Fix:** Fail `assembleRelease`/`bundleRelease` with a `GradleException` when `!hasReleaseSigning`.

### [ ] P2-29 ✅ `uses-feature camera required=true` blocks camera-less devices
- **Problem:** The app supports full manual entry everywhere, but the manifest blocks install
  on camera-less devices.
- **Files:** `AndroidManifest.xml:12`.
- **Fix:** Set `android:required="false"`.

### [ ] P2-35 ✅ ProGuard keep rule uses the wrong package
- **Problem:** `-keep class com.silverbp.sync.** { *; }` is missing the `.android` segment;
  the real package is `com.silverbp.android.sync.**`, so the rule matches nothing.
- **Files:** `app/proguard-rules.pro:46`.
- **Fix:** Correct to `com.silverbp.android.sync.**` (and re-audit nearby rules).

### [ ] P2-37 ✅ Codec throws on unknown record type → old apps can't skip new records
- **Problem:** `SyncRecordCodec.decode()` → `SyncEntityType.fromTag()` calls `error()` on an
  unknown tag. Field-level `skipValue` exists, but not type-level, and decode callers have no try/catch.
- **Files:** `sync/transport/SyncRecordCodec.kt:85`, `sync/engine/SyncRecord.kt:54`.
- **Fix:** Make `fromTag` nullable and have `decode` skip/return-null for unknown types so a
  newer record is ignored rather than crashing the whole sync/restore.

### [ ] P2-36 ✅ Migration test + exported schema stop at v13/v14; no v20 coverage
- **Problem:** `RoomMigrationTest` validates only to v13; `app/schemas/.../14.json` is missing
  (jumps v13→v15); there is no 19→20 test, so post-v14 schema drift is easy to miss.
- **Files:** `androidTest/.../RoomMigrationTest.kt:25`, `app/schemas/...` (missing `14.json`).
- **Fix:** Regenerate/commit the missing exported schema(s) and extend the migration test to
  validate through v20 (add a 19→20 case).

---

## 7. UI / state robustness

### [ ] P0-9 ✅ Exercise "measure BP first" CTAs go nowhere
- **Problem:** Pre-workout `onMeasure = { pendingCardio = null }` only closes the dialog;
  post-workout `onMeasureBp = {}` defaults empty and is never wired in `AppNavHost`.
- **Files:** `ui/exercise/ExerciseHomeScreen.kt:302/309`, `ExerciseSummaryScreen.kt:53/56`,
  `ui/nav/AppNavHost.kt:287`.
- **Fix:** Wire both CTAs to navigate to the BP measurement route (`Routes.CAPTURE`).

### [ ] P0-12 ✅ Nutrition save has no guard → rapid taps create duplicate UUIDs
- **Problem:** The save button has no `isSaving`/disabled state; each tap builds a new
  `FoodLog(UUID.randomUUID())` and upsert always inserts.
- **Files:** `ui/nutrition/NutritionConfirmScreen.kt:133`, `NutritionConfirmViewModel.kt:122`.
- **Fix:** Add an `isSaving` flag that disables the button during the async save (and/or a
  stable draft id), preventing re-entry.

### [ ] P1-14 ✅ Weight confirm draft held in `remember` → lost on process death
- **Problem:** `var draft by remember { mutableStateOf(...) }` with no `SavedStateHandle`/
  `rememberSaveable` (unlike `ConfirmGlucoseViewModel`'s M5 mirroring).
- **Files:** `ui/confirm/ConfirmWeightScreen.kt:111`.
- **Fix:** Mirror the draft into `rememberSaveable`/`SavedStateHandle` like `ConfirmGlucoseViewModel`.

### [ ] P1-15 ✅ BP confirm VM has no `SavedStateHandle` → blank record after recycle
- **Problem:** `ConfirmReadingViewModel` lacks `SavedStateHandle`; after process death
  `CaptureSessionHolder.take()` returns null and it falls back to a blank `BpReadingDraft`.
- **Files:** `ui/confirm/ConfirmReadingViewModel.kt:42`; pattern in `ConfirmGlucoseViewModel.kt:60/133`.
- **Fix:** Add `SavedStateHandle` and restore the mirrored draft before consuming the holder
  (mirror the glucose M5 pattern).

### [ ] P1-25 ✅ Manual nutrition can save blank; edit with missing id → blank fallback
- **Problem:** Save has no field validation; editing an id not in the DB falls back to
  `FoodLog()` (`findById() ?: FoodLog()`) and can be saved blank.
- **Files:** `ui/nutrition/NutritionConfirmScreen.kt:373`, `NutritionConfirmViewModel.kt:52`.
- **Fix:** Require non-empty content before enabling save; on a missing edit id, surface an
  error / dismiss instead of creating a blank record.

### [ ] P1-26 ✅ Member add allows a blank name; non-owner can display "Me"
- **Problem:** New members save with a blank `displayName`; `displayNameOrFallback()` shows
  the localized "Me" for **any** blank name with no `isOwner` check.
- **Files:** `ui/member/MemberEditorSheet.kt:101` (note: `ui/member/`, not `members/`),
  `ui/member/MemberManagementScreen.kt:435`, `ui/member/MemberSwitcherChip.kt:207`.
- **Fix:** Validate non-empty `displayName` on save; gate the "Me" fallback on `isOwner`.

### [ ] P1-27 ✅ Disabling Coach while on the Coach route doesn't redirect
- **Problem:** `visibleTabs` drops Coach when `enableCoach=false`, but the only effect is
  `LaunchedEffect(Unit)`; nothing watches `enableCoach`, so the user is stuck on a route with
  no matching bottom tab.
- **Files:** `ui/nav/AppNavHost.kt:462`.
- **Fix:** Add a `LaunchedEffect(enableCoach)` that redirects to a default tab (e.g. Today)
  when Coach is disabled while on the Coach route.

---

## Notes

- **Verification limitation:** static read-only analysis only; no build/test was run (no JRE
  available). Runtime-only behavior (e.g. exact ANR thresholds for P0-11) is inferred.
- **Suggested order:** sections are listed by root cause and roughly by risk. Section 1
  (silent sync data loss) and the P0 crashes/lockouts should land first.
