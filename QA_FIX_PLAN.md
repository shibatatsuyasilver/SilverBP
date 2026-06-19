# SilverBP — QA Fix Plan

Original remediation backlog for the QA report. The original body below is preserved for
traceability, but its checkbox state is stale against current HEAD.

**Current-head re-audit:** 2026-06-19, static read-only review with 10 completed subagent
passes plus a main pass. Covered build/release, manifest/security, Room/sync, backup,
recognition, Health Connect/exercise, UI/navigation, tests/CI, and domain/medical safety.

Legend in the legacy body: `[ ]` to do · ✅ verified-real · 🟡 real defect, impact
overstated. The authoritative current status is the re-audit section immediately below.

---

## 0. 2026-06-19 Current-Head Re-Audit

### Scope and outcome

- **Subagent coverage:** 10 completed read-only subagent passes were incorporated:
  build/release, manifest/security, Room/local data, LAN sync/pairing, backup/crypto,
  capture/AI recognition, Health Connect/exercise, Compose UI/navigation, tests/CI,
  and domain/medical safety.
- **Original plan count mismatch:** this file says "37-item QA report", but current
  headings contain **35** `P0/P1/P2` items.
- **Bottom line:** many original QA defects are fixed in code, but the project is **not**
  fully clean. Several original items are only partially fixed, and the re-audit found
  new release-blocking or data-safety issues.
- **Verification limitation:** this was primarily static review. One subagent reported
  `./gradlew :app:compileDebugKotlin` could not run because no Java Runtime was available
  locally; another read the existing lint report instead of running fresh lint.

### Original QA items: current status

| Status | Items |
| --- | --- |
| Fixed for the original described defect | `P0-3`, `P0-4`, `P0-5`, `P0-7`, `P0-10`, `P0-11`, `P0-12`, `P0-13`, `P1-14`, `P1-15`, `P1-16`, `P1-17`, `P1-19`, `P1-22`, `P1-24`, `P1-25`, `P1-26`, `P1-27`, `P1-28`, `P2-29`, `P2-30`, `P2-31`, `P2-32`, `P2-33`, `P2-34`, `P2-35` |
| Partially fixed / residual risk remains | `P0-1`, `P0-2`, `P0-6`, `P0-8`, `P0-9`, `P1-18`, `P1-20`, `P2-36`, `P2-37` |

Detailed residuals:

- `P0-1`: main BP/glucose/weight/member writes now stamp HLC, but Health Connect retry
  can still rewrite glucose/weight with `hlcUpdatedAt = "0"`; medication/schedule and
  several coach/strength paths still bypass HLC; `HlcClock.lastSeen` is not persisted
  across process restarts.
- `P0-2`: BP/glucose/weight/food/exercise deletes now write tombstones, but
  medication/schedule and some strength/coach synced deletes still hard-delete without
  tombstones.
- `P0-6`: initial Health Connect grant includes glucose/weight and checks the requested
  set, but cold-start revoke reconciliation still uses steps-only logic and background
  read is treated as a master-toggle hard requirement.
- `P0-8`: future system backup is blocked, but `SilverBpBackupAgent.onRestoreFile()` still
  delegates to `super`, so legacy Android Auto Backup sets may restore health DB/DataStore.
- `P0-9`: cardio pre/post-workout BP CTAs are wired; strength pre/post-workout BP CTAs are
  still no-op.
- `P1-18`: `recordsSince(peerLastHlcSeen)` now filters/sorts, but product pairing does not
  persist or reuse peer watermarks, and skipped orphan child records still advance the
  watermark.
- `P1-20`: Gemini final JSON is debug-gated, but Gemma/AICore and some ViewModel paths still
  log raw health/model output or health values.
- `P2-36`: migration registration/test coverage reaches v21, but exported schema
  `app/schemas/com.silverbp.android.core.db.SilverBpDatabase/14.json` is still missing.
- `P2-37`: record-level `decodeOrNull()` skips unknown types, but backup import still
  compares decoded known records with header `recordCount`, so newer backups containing an
  unknown record fail restore.

### New blocking / high-risk findings

#### 1. Health Connect retry can reset glucose/weight HLC to `"0"`
- **Evidence:** `GlucoseSyncWorker.kt:67`, `WeightSyncWorker.kt:96`,
  `GlucoseMappers.kt:10`, `WeightMappers.kt:9`.
- **Impact:** after a retry writes `hcRecordId`, the worker rebuilds the entity through a
  domain mapper that does not carry `hlcUpdatedAt`. LWW then treats the row as having no
  local version, so stale peers can overwrite or resurrect data.
- **Solution:** do not use domain round-trip for device-local HC id updates. Add DAO methods
  such as `UPDATE glucose_reading SET hcRecordId = :hcId WHERE id = :id` and the weight
  equivalent, or load the existing entity and copy only `hcRecordId` while preserving
  `hlcUpdatedAt`. Add regression tests for both workers.

#### 2. Medication is not member-isolated
- **Evidence:** `MedicationEditScreen.kt:147`, `MedicationManageScreen.kt:69`,
  `CoachLogMedicationScreen.kt:84`.
- **Impact:** new medications may not set `memberId`, and management/log screens use
  all-member observation. Family members can see, edit, delete, or log each other's
  medicines.
- **Solution:** create/edit medication through a repository that always sets/preserves the
  current member id. Change management/log queries to `observeForMember(currentMemberId)`.
  Ensure schedules are joined only to that member's medications.

#### 3. Medication/schedule and other synced mutation paths bypass HLC/tombstones
- **Evidence:** `MedicationEditScreen.kt:158`, `MedicationManageScreen.kt:181`,
  `MemberRepository.kt:103`, `CoachRepository.kt:83`, `StrengthWorkoutRepository.kt:34`,
  `ExerciseLibraryRepository.kt:40`, `BpWorkoutAssociationRepository.kt:29`.
- **Impact:** local edits may never pass incremental sync, and local deletes can be
  resurrected by a peer.
- **Solution:** centralize writes in repositories/services. Inject `LocalSyncWriter` or
  `LocalSyncMutationDao`, stamp every local upsert/update with `nextHlc()`, and write
  tombstones for every synced delete in the same transaction. Add mapper/LWW tests for
  medication, schedule, member archive/unarchive/sort, coach task, and strength logs.

#### 4. HLC high-water and peer watermarks are not durable in production pairing
- **Evidence:** `Hlc.kt:63`, `SyncCoordinator.kt:35`, `PairingService.kt:176`,
  `PairingViewModel.kt:259`, `SyncDao.kt:51`.
- **Impact:** process restart or clock skew can generate lower HLCs than already observed.
  Pairing sessions always start from `Hlc.ZERO` and do not update `sync_device.lastHlcSeen`,
  so sync is effectively full/redundant and tombstone GC cannot be trusted.
- **Solution:** persist `HlcClock.lastSeen` in encrypted prefs or Room after `next()` and
  `observe()`. Seed startup from persisted high-water plus DB/tombstone max. After SAS
  confirmation, upsert `SyncDeviceEntity`, read `lastHlcSeen` for the session, and update
  it after successful apply.

#### 5. Skipped orphan child records still advance sync watermark
- **Evidence:** `SyncSession.kt:78`, `Phase2Mappers.kt:216`, `Phase2Mappers.kt:592`,
  `StrengthSyncMappers.kt:191`.
- **Impact:** child records such as route points, coach tasks, or set logs can be skipped
  when their parent has not arrived; the session still observes their HLC and updates the
  watermark, so the peer may never resend them.
- **Solution:** make `SyncRecordSink.apply()` return `Applied`, `Stale`, or `RetryLater`.
  Do not advance watermarks for retryable child-orphan skips. Alternatively, persist
  orphans in a pending table and apply after parents arrive.

#### 6. Domain validation is too high in the UI
- **Evidence:** repository entry points `BpRepository.kt:50`, `GlucoseRepository.kt:65`,
  `WeightRepository.kt:65`, `NutritionRepository.kt:46`; nutrition UI numeric input around
  `NutritionConfirmScreen.kt:551`.
- **Impact:** sync import, Health Connect import, tests, or future callers can bypass UI
  draft validators and write medically impossible or negative data into reports, coach, and
  Health Connect mirrors.
- **Solution:** move validation into domain/repository layers. Require BP/glucose/weight
  finite values within accepted physiological ranges, and require nutrition calories/sodium
  to be finite and non-negative. Reject or quarantine invalid inbound sync/import rows and
  surface an import warning.

#### 7. Health data and model output still reach logs
- **Evidence:** `GemmaBpService.kt:204`, `GemmaBpService.kt:215`,
  `AICoreBpService.kt:173`, `AICoreBpService.kt:184`,
  `GeminiCloudRecognizer.kt:119`, plus flow/ViewModel health-value logs reported in audit.
- **Impact:** BP, glucose, weight, or raw OCR/model text can enter logcat and bug reports.
- **Solution:** create a small redacted logging helper. In release builds, never log raw
  recognizer text, numeric health values, photos, prompts, response bodies, QR URLs, or SAS
  codes. In debug builds, prefer metadata such as backend, latency, output length, and parse
  status.

#### 8. Cloud AI privacy disclosure is narrower than actual data flow
- **Evidence:** privacy copy describes BP monitor photos, while code can send nutrition,
  glucose, weight, machine photos, chat text/images, and record context to Google Gemini.
- **Impact:** consent is incomplete for sensitive health data sent to a third-party AI API.
- **Solution:** update `docs/privacy.html`, onboarding, and Settings disclosure to enumerate
  all cloud AI routes and data categories. Make cloud chat record context an explicit opt-in
  or clearly disclose when enabled.

#### 9. Backup restore can commit partial data after replace
- **Evidence:** `BackupManager.kt:261`, `BackupManager.kt:274`.
- **Impact:** replace restore clears tables, then catches generic per-record exceptions and
  commits the transaction with skipped known records. A mapper/FK bug can leave the user with
  a partially restored database.
- **Solution:** in Replace mode, known record apply failures should throw and roll back the
  transaction. Only unknown future record types should be skippable. Consider a dry-run
  validation pass before destructive clear.

#### 10. Backup forward-compat and malicious-input hardening are incomplete
- **Evidence:** `BackupCodec.kt:328`, `BackupManager.kt:238`, `BackupCodec.kt:189`,
  `BackupCrypto.kt:89`.
- **Impact:** unknown record types break older restores because `recordCount` compares only
  decoded known records. Also, untrusted backup headers can feed unbounded Argon2 parameters
  before payload authentication, risking OOM or long stalls.
- **Solution:** return raw block count, known records, and skipped unknown count from
  `decodePayload()`. Validate header format/content/aead, salt/nonce/wrap lengths, KDF
  bounds, and maximum payload size before KDF/decrypt.

#### 11. Google Drive backup lifecycle can mislead users or delete too broadly
- **Evidence:** login string promises automatic backup; `AutoBackupWorker.kt:35` fails
  without a recovery code; `GoogleDriveBackupClient.kt:87` lists all appDataFolder files;
  `BackupViewModel.kt:267` deletes all listed files.
- **Impact:** first-launch Google linking may not create a usable backup, and disconnect or
  retention pruning may delete unrelated future appDataFolder files.
- **Solution:** either change login copy to "link now, configure backup later" or force a
  recovery-code setup, default frequency, scheduler enqueue, and first snapshot after
  consent. Tag uploaded backup files with fixed MIME/name prefix and `appProperties`, filter
  `listBackups()` accordingly, and only delete files positively identified as SilverBP
  backups.

#### 12. Health Connect/exercise lifecycle defects remain
- **Evidence:** `StrengthLibrarySection.kt:85`, `WorkoutSummaryScreen.kt:51`,
  `PermissionGate.kt:86`, `LocationTrackingService.kt:193`, `StepCounterReader.kt:51`,
  `SilverBpApplication.kt:145`, `SettingsViewModel.kt:37`.
- **Impact:** strength BP safety CTAs are no-op; denied `ACTIVITY_RECOGNITION` may still lead
  to protected step-sensor access; HC revoke reconciliation can stay enabled on steps-only;
  background read denial blocks unrelated foreground write features.
- **Solution:** thread `onMeasureBp` through strength start and summary flows; check
  `ACTIVITY_RECOGNITION` before registering step sensors and catch `SecurityException`;
  implement shared `reconcileHealthConnect()` over the real core permission set; make
  background reads optional and gate only background workers.

#### 13. Release/Play and operator docs are out of sync with code
- **Evidence:** `RELEASE.md` C-8/C-9 omits weight read/write and data-sync FGS; signing
  appendix still says missing `KEYSTORE_*` falls back to debug signing; Health declaration
  still treats glucose as future-only.
- **Impact:** Play Console app-content declarations may be incomplete, and operators can add
  the wrong Maps SHA-1 or misunderstand release signing behavior.
- **Solution:** update `RELEASE.md` with every manifest health permission, `FOREGROUND_SERVICE_DATA_SYNC`,
  and current upload key path. State that release artifact tasks fail fast when signing or
  Maps key values are missing.

#### 14. Lint/CI are not release-clean
- **Evidence:** existing lint report still matches source patterns in
  `LocationTrackingService.kt:207`, `PairingScreen.kt:518`, `MainActivity.kt:36`,
  `ChatScreen.kt:136`, `NutritionScreen.kt:115`, and `ExerciseNotification.kt:407`.
  No CI workflow was found.
- **Impact:** `lintDebug`/`lint` may fail, and regressions are not blocked automatically.
- **Solution:** fix notification permission checks, `ExperimentalGetImage` opt-in,
  Compose flow allocation/snackbar string issues, and string format mismatch. Add CI to run
  at least `./gradlew :app:testDebugUnitTest :sync:test :app:lintDebug`; add emulator or
  scheduled migration validation for instrumentation-only tests.

#### 15. API tokens and large model downloads need stronger safeguards
- **Evidence:** Gemini API key is encrypted only when DB/app-lock encryption is active;
  HF token is passed through WorkManager `inputData`; `ModelDownloadWorker.kt:114` uses
  `NetworkType.CONNECTED`; `ModelCatalog.kt:83` has a gated model with `sha256 = null`.
- **Impact:** user API tokens may persist in app/WorkManager storage, multi-GB downloads can
  run on metered networks from some entry points, and one model lacks content pinning.
- **Solution:** always wrap user secrets with Android Keystore independent of app lock. Pass
  only a token reference to workers and clear encrypted token material in `finally`. Use
  `NetworkType.UNMETERED` or a shared metered-network confirmation. Require SHA-256 for every
  release model variant.

#### 16. Recognition and chat image paths still have robustness gaps
- **Evidence:** Weight/Machine local/AICore recognizers bypass LCD preprocessing; chat image
  decode paths use full-size `BitmapFactory.decodeStream/decodeFile`; machine confirm save is
  enabled even when duration/distance are blank and become zero.
- **Impact:** OCR accuracy differs by backend, large chat images can spike memory or ignore
  EXIF, and machine OCR can save zero-duration workouts into achievements/coach/sync.
- **Solution:** route weight/machine local and AICore input through the same bounded
  `preprocessForOcr()` path used by cloud where appropriate. Add bounded EXIF-aware decode for
  chat attachments. Add a machine workout `isValid` rule requiring positive duration and at
  least one meaningful activity metric.

#### 17. Product/domain gaps
- **Evidence:** `ReportViewModel.kt:60` / `:101` only gate PDF generation on BP rows even
  though renderer supports glucose; BP crisis classification exists but BP save completes
  without immediate acknowledgement; weight edit of a missing id still falls back to a new
  blank draft.
- **Impact:** glucose-only users cannot generate reports, users may miss immediate high-BP
  safety guidance, and broken/deleted weight edit links can silently create new rows.
- **Solution:** enable reports when BP or glucose rows exist. Add one-time non-diagnostic
  BP crisis acknowledgement on save for >=180/120, analogous to the existing low-glucose
  safety warning. Add a not-found state for weight edit.

#### 18. Nutrition and Coach logs are still globally scoped
- **Evidence:** `NutritionEntities.kt:17` has no `memberId`; `NutritionDao.kt:12` uses
  unscoped all/range queries; coach plan/sleep/diet/dose entities are also effectively
  global unless explicitly documented as owner-only.
- **Impact:** nutrition screens, sodium rollups, weekly coach reports, and AI coach context
  can merge data from different family members.
- **Solution:** either document these modules as owner-only and enforce that in UI/queries,
  or add `memberId` to food, coach plan, sleep, diet, and medication dose records with a
  migration that backfills the owner. Then scope DAO/repository APIs by current member.

#### 19. Lower-risk hardening
- **FileProvider:** `file_paths.xml` exposes `files/photos/` although current sharing only
  needs cached PDFs. Remove the photos path or create a separate purpose-specific provider.
- **Manifest optional features:** mark microphone, Wi-Fi, and broad/network location
  hardware as `required="false"` to avoid Play device filtering for optional features.
- **Protocol forward compatibility:** unknown protocol envelope keys/message types currently
  abort sessions; skip unknown fields and return explicit protocol errors.
- **Accessibility:** `AppNavHost.kt:659` back icon for standalone weight history has null
  content description; use existing `R.string.a11y_back`.

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
