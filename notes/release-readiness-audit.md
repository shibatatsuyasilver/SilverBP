# SilverBP — Play Store Release-Readiness Audit

_Lead release engineer synthesis. Date: 2026-06-09. Branch: `main`, `versionCode = 1`, `versionName = "1.0"`, `minSdk 33 / targetSdk 36 / compileSdk 36`._

---

## 0. Fix Progress — updated 2026-06-09 (live tracker)

**5 of 7 P0 code fixes landed and verified.** `./gradlew :app:testDebugUnitTest :sync:test` green throughout; added an 8-case LWW gate unit test (`app/src/test/.../sync/LwwGateTest.kt`). UI changes not yet device-verified. Remaining P0s are content/platform work.

| P0 | Status | Note |
|----|--------|------|
| P0-1 sign-in hard gate | ✅ Fixed | Skippable via `googleSignInDeferred` (+ "稍後再說" button); gate fires only on genuine first launch; disconnecting Google no longer ejects (fixes the related P1). |
| P0-2 Health Connect crash | ✅ Fixed | All 4 launch sites routed through `launchHealthConnectOrInstall` (getSdkStatus guard + install detour + runCatching). |
| P0-3 LWW / data loss | ✅ Fixed | `CombinedRoomSyncSink` LWW gate via `lwwShouldApply` (live + tombstone HLC); `localHlc()` on 12 entities; HLC stamped on local writes for 11 entities. REPLACE-restore unaffected (tombstones cleared). Tail (no regression): `daily_step_log` (derived) + coach_task in-place `@Query` toggles don't bump HLC. |
| P0-4 Auto Backup plaintext DB | ⏳ Decided: **disclose** | Folded into P0-5 (privacy policy + Data Safety). |
| P0-5 privacy ↔ Data Safety | ⏳ Pending | Needs developer/company name + contact email; then rewrite `privacy.html`, fix onboarding consent, produce Data Safety mapping. |
| P0-6 medical disclaimer | ✅ In-app done | Coach-screen footer `coach_medical_disclaimer` (en+zh-TW) added; Onboarding/Settings-About/PDF/nutrition already had one. **Store-listing first paragraph still TODO in Play Console.** |
| P0-7 account deletion | ✅ In-app done | Settings → Backup → danger zone "Delete account & data": wipes Room DB + recovery code + photos, deletes Drive backups, resets settings → onboarding. **Still need a public web deletion page + Play account-type decision (12-tester/14-day gate).** |

---

## 1. Executive Summary

**Verdict: NOT ready to submit.** The app and unit-test suite compile and pass (267 tests, 0 failures), and the SDK/target levels already satisfy the Aug-31-2026 API-36 bar. But there is a cluster of **first-run / first-launch blockers** that brick the app for a large share of devices, plus **mandatory Play Console compliance work** (Data Safety, Health declaration, medical disclaimer, closed-testing gate) that has multi-week lead time and is not yet done. Several of these are flatly contradicted by the current privacy policy, which is itself a rejection/removal risk.

Top risks (in order):

1. **Mandatory Google sign-in hard-gate with no escape** — offline / no-Google-account / declined-consent users can never reach the core BP-logging screen, and back-press quits the app. Likely Play policy rejection + 1-star reviews. (`AppNavHost.kt:96-106`, `LinkAccountScreen.kt`)
2. **Health Connect toggle crashes on Android 13 / devices without HC installed** — `launch()` throws `ActivityNotFoundException`, no SDK-status guard, no try/catch. A prominent first-run action hard-crashes the app. (`SettingsScreen.kt:298-302`)
3. **Privacy policy is factually false about data leaving the device** — default Android Auto Backup ships the *plaintext* health DB to the user's Google Drive; in-app Drive auto-backup, Gemini cloud chat (health profile + photos), and Open Food Facts barcode calls are all undisclosed. Data Safety form must match reality or the app is removed.
4. **LAN-sync has no working last-writer-wins (LWW) gate** — every incoming record blindly overwrites local rows and local edits never stamp an HLC, so sync silently loses the newer edit. Gated behind the optional pairing/backup feature, but a genuine P0-class data-loss defect in that subsystem.
5. **Mandatory Play compliance lead time** — personal-account closed-testing 12-tester / 14-consecutive-day gate, Health Apps declaration, FGS-location declaration, Data Safety, account-deletion (in-app + web URL), and first-paragraph medical disclaimer. Budget **3+ weeks** of calendar time before production is even possible.

Issue counts (after adjudicated/adjusted severities): **P0 = 7**, **P1 = 23**, **P2 = 40**.

---

## 2. P0 — Release Blockers (MUST fix before submitting)

Merged from code, config, and compliance findings; overlapping items deduplicated.

| # | Severity | Area | Issue | File / location | Fix |
|---|----------|------|-------|-----------------|-----|
| P0-1 ✅ FIXED | P0 | Navigation / onboarding | **Mandatory Google sign-in is a hard gate with no skip/offline/no-Play-Services escape** — bricks first launch; back-press exits app. Core BP logging needs no account. Likely Play policy rejection. (Two findings merged: AppNavHost gate + offline/no-GMS.) | `app/.../ui/nav/AppNavHost.kt:96-106`; `ui/onboarding/LinkAccountScreen.kt:49-155`; `GoogleAuthClient.kt:42,73`; `ui/backup/BackupViewModel.kt:236-239` | Make account-link optional: add "Skip / set up later" that records a deferred flag and routes to HOME, OR surface linking only inside Settings/Backup. Do not `popUpTo(HOME){inclusive=true}` for the gate. Detect `GoogleApiAvailability` + offline and auto-skip. |
| P0-2 ✅ FIXED | P0 | Health Connect | **Enabling HC toggle crashes (`ActivityNotFoundException`) when HC not installed** (common on Android 13 / minSdk 33). No `getSdkStatus` guard before `launcher.launch()`, no try/catch; toggle always interactive; no global crash handler. | `ui/settings/SettingsScreen.kt:298-302` (also `:469`, `:495`, `CoachLogSleepScreen.kt:163`) | Check `HealthConnectClient.getSdkStatus(context)`; only `launch()` when `SDK_AVAILABLE`, deep-link to Play for `PROVIDER_UPDATE_REQUIRED`, message otherwise. Wrap every `launch(...)` in `runCatching`. Expose status from bridges so all launch sites share one guard. |
| P0-3 ✅ FIXED | P0 | LAN sync (HLC) | **Local writes never stamp an HLC; sync re-mints a fresh HLC each round** → LWW ordering is non-deterministic, stale copies beat fresh edits. Defeats convergence even if the gate were present. | `sync/RoomSyncAdapters.kt:296-298,144-148`; entities default `hlcUpdatedAt="0"` (`Entities.kt:28`, `NutritionRepository.kt:101`) | Stamp `clock.next()` into `hlcUpdatedAt` on every local create/update and persist it; `recordsSince` ships the stored HLC unchanged (no mint at encode). One-time backfill legacy rows from `updatedAt`. |
| P0-4 | P0 | Privacy / Data Safety | **Policy claims health data "never uploaded," but default Android Auto Backup ships the plaintext `silverbp.db` to Google Drive** (DB only dropped when SQLCipher app-lock is on, which is off by default). Factually false §2/§6; Data Safety must declare off-device transfer. | `AndroidManifest.xml` (`allowBackup="true"`); `res/xml/data_extraction_rules.xml` (`<cloud-backup>` includes `silverbp.db`); `backup/SilverBpBackupAgent.kt` | Either set `allowBackup="false"` / remove `silverbp.db`+DataStore from `<cloud-backup>`, OR rewrite §2/§6 to disclose Auto Backup and update Data Safety to declare Health data transferred to Google Drive. |
| P0-5 | P0 | Privacy / Data Safety | **In-app Google Drive auto-backup + Gemini cloud chat + food/machine photo upload + Open Food Facts are undisclosed off-device transfers.** Health profile (BP+notes, stats, sleep/diet/med-adherence) goes to Gemini; full encrypted snapshot + account email/ID go to Drive; barcodes go to OFF. Onboarding consent even says health data is NOT uploaded — direct contradiction. (Four privacy findings + permission-audit P0 merged.) | `backup/auto/AutoBackupWorker.kt`, `GoogleDriveBackupClient.kt`, `GoogleAuthClient.kt`; `recognition/GeminiCloudRecognizer.kt`; `recognition/chat/GeminiCloudChatRecognizer.kt`; `ui/chat/RecordsContextBuilder.kt`; `nutrition/OpenFoodFactsClient.kt`; consent string `strings.xml:392` | Rewrite privacy policy §1/§3/§4/§6 to disclose Drive backup (5-file retention, account email/ID, OAuth), Gemini (photos + health summary + chat), and Open Food Facts. Fix the onboarding consent text. Complete Data Safety: Health/Fitness + Photos + User IDs as Collected **and** Shared (Google). Add a one-time consent before enabling the Cloud backend. |
| P0-6 ⚠️ IN-APP DONE | P0 | Play policy (store listing) | **"Not a medical device" disclaimer must be in the FIRST paragraph of the store description** (en + zh-TW) and surfaced in-app. BP + AI-coach app without regulatory clearance. Missing this causes rejection. | Play Console store listing; in-app BP/Coach screens | Add to first paragraph: "SilverBP is not a medical device and does not diagnose, treat, cure, or prevent any medical condition. Always consult a qualified healthcare professional…" Mirror short version in-app. Make no accuracy/diagnosis claims. |
| P0-7 ⚠️ IN-APP DONE | P0 | Play Console process | **Mandatory pre-production gates: Health Apps Declaration, FGS-location declaration, Health Connect per-permission review, Account Deletion (in-app + web URL), and (personal accounts) 12-tester / 14-consecutive-day closed test.** Multi-week lead time; blocks production. | Play Console > App content; LinkAccount triggers in-app account → deletion required | Decide account type now (Organization avoids the 12/14 gate). Complete Health Apps declaration (no regulated-device), FGS "location" declaration (with <30s video), HC data-access review (per-permission justification), implement in-app + web "delete account & data," and run the closed test if personal account. Budget 3+ weeks. |

**Notes on demotions:** Several items the source data initially tagged P0 were adjudicated down and appear in P1: `LocationTrackingService.startForeground` crash (→P1, narrow Resume-path combo), `ModelDownloader` Range-resume corruption (→P1, conditional non-206), strength in-memory workout loss (→P1, transient draft), missing-LWW-gate apply path & stale-tombstone delete (→P1, UUID PKs limit collisions), missing schema `14.json` (→P2, test-integrity only — end users unaffected). They are still important; see P1/P2.

---

## 3. P1 — Should Fix Before Release

| Severity | Area | Issue | File / location | Fix |
|----------|------|-------|-----------------|-----|
| P1 | LAN sync | **No LWW gate in apply/sink path** — every incoming live record blindly REPLACE-overwrites local row; no HLC comparison anywhere (Merger is an unimplemented stub). | `sync/BpReadingSyncMapper.kt:92-131`; `RoomSyncAdapters.kt:484-521`; `Phase2Mappers.kt:122-174`; `SyncSession.kt:77-81` | Fetch local `hlcUpdatedAt`, apply only when `record.hlc > localHlc`; centralize in `CombinedRoomSyncSink.apply`. |
| P1 | LAN sync | **Stale tombstone deletes a newer live record** — unconditional `delete()` with no HLC gate; backup snapshot replays all tombstones since "0" → resurrect-then-destroy. | `BpReadingSyncMapper.kt:96-107`; `Phase2Mappers.kt:124-135,248-258,296-307` | Gate tombstone deletes on `record.hlc > localRow.hlc` AND `> existing tombstone.hlc`; store tombstone HLC and compare on later upserts. |
| P1 | LAN sync | **Ongoing/incremental sync not wired** — sync fires only once at pairing and re-ships the entire DB (`recordsSince` ignores `peerLastHlcSeen`; watermark hardcoded `Hlc.ZERO`). | `RoomSyncAdapters.kt:136-294`; `PairingViewModel.kt:245`; `PairingService.kt:180-181`; `SyncCoordinator.kt:62-127` (unused) | Implement re-run on paired-peer discovery + persist per-peer watermark and make `recordsSince` filter by it; OR label pairing "one-time import" for v1. |
| P1 | LAN sync | **No socket read/handshake timeout** — blocking `readInt/readFully` on a plain TCP socket; a stalled/disconnected peer hangs the IO thread forever, UI stuck on "Syncing…", socket leaks. | `sync/transport/FrameChannel.kt:56-68`; `NsdDiscovery.kt:98-102` | Set `socket.soTimeout`, wrap run/handshake in `withTimeout`, close socket on cancellation, translate `SocketTimeoutException` to a clean error. |
| P1 | DB / Room | **Strength session sync apply uses `OnConflictStrategy.REPLACE` on parent** → FK CASCADE wipes all `set_log` children on re-sync (data loss). | `sync/StrengthSyncMappers.kt:153`; `StrengthWorkoutDao.kt:37-38`; `StrengthEntities.kt:43-49` | Look up existing row; `updateSession` when present, `insertSession` only when new (mirror cardio mapper). |
| P1 | Recognition / ML | **Resumed model download corrupts file when server ignores Range** (no 206/Content-Range check; append-mode write). 3n variant `sha256=null` so corruption passes verification. | `recognition/ModelDownloader.kt:42-71`; `ModelCatalog.kt:82` | Require `code==206` when resuming; else delete `.part`, reset, overwrite. Pin a non-null sha256 for the 3n variant. |
| P1 | Recognition / ML | **Full-resolution bitmap decode on gallery import can OOM before downsampling** — no `inSampleSize`/`inJustDecodeBounds`; second full copy in EXIF rotate; decode runs uncaught on IO dispatcher. | `recognition/ImagePreprocess.kt:60-84` | Bounds-first decode → compute `inSampleSize` to cap longest side ~2048px → real decode. Prefer `ImageDecoder.setTargetSampleSize`. |
| P1 | Exercise / FGS | **`startForeground(FGS_TYPE_LOCATION)` before any location-permission check** — Android 14+ throws `SecurityException` from `startForeground` itself; ACTION_RESTORE/Resume path unguarded → service crash. | `exercise/LocationTrackingService.kt:116-143` | Check `ACCESS_FINE_LOCATION` at top of `beginTracking()`; bail before `startForeground` if missing; wrap `ensureForeground()` in try/catch and tear down cleanly. |
| P1 | Strength | **In-progress workout is in-memory only; lost on process death** — single `MutableStateFlow` singleton, no SavedStateHandle/draft/DB write until summary save. Cardio sibling has checkpoint recovery; strength omits it. | `strength/StrengthWorkoutLiveStore.kt:54-70`; `ServiceLocator.kt:165` | Persist live workout to DataStore/draft tables on each mutation; rehydrate on init; clear on finish. |
| P1 | Strength | **Summary duration keeps growing while user deliberates; persisted `endedAt` is a later timestamp** — flows into coaching minutes, last-workout timing, HC export. | `ui/strength/WorkoutSummaryScreen.kt:83-89`; `WorkoutSummaryViewModel.kt:49`; `StrengthWorkoutLive` (no `endedAt`) | Capture `endedAtMillis` once at `finish()`; use that fixed value for both display and persistence. |
| P1 | Strength | **`BodyPart.fromRaw` / `DifficultyFeedback.fromRaw` throw on unknown discriminator** — `entries.first{}` inside `Flow.map` with no `.catch`; synced/malformed data crashes home + library lists. | `strength/StrengthModels.kt:13-16,27-29`; `StrengthMappers.kt:39,102`; `StrengthWorkoutRepository.kt:54` | Use `firstOrNull{} ?: default`, or `runCatching` like the adjacent `muscleGroups` decode. |
| P1 | Nutrition | **OpenFoodFacts mixes per-serving and per-100g into one record** — global `useServing` flag with per-field fallback; `serving_size` parsed but never used to reconcile. Marked authoritative (confidence 1.0). | `nutrition/OpenFoodFactsClient.kt:64-92` | Compute each nutrient from a single basis: prefer its own `_serving`; if absent and `useServing`, scale that nutrient's `_100g` by serving/100. |
| P1 | Navigation | **Summary screens are a dead-end with silent total data loss after process death** — finished snapshot lives only in in-memory `_flow`; `snapshotAndFinish` already cleared the recovery checkpoint; null-state shows static empty text with no save/discard. | `ui/exercise/ExerciseSummaryViewModel.kt:33-54`; `ExerciseSummaryScreen.kt:70-76`; `ExerciseSessionLiveStore.kt:291`; `WorkoutSummaryViewModel.kt:31` | Don't clear checkpoint in `snapshotAndFinish` until summary persists/discards; in null-state branch `LaunchedEffect{ onDiscard() }` to self-dismiss. |
| P1 | Coach | **Weekly-report medication adherence counts touched dose rows, not scheduled doses** → systematically over-reports (≈100%), can never reflect missed doses. Per-med ring already does it right. | `coach/CoachRepository.kt:120-125`; `CoachDao.kt:147-148` | Derive denominator from the schedule mask (`countScheduledInWeek` approach); numerator stays `countTakenInRange`. |
| P1 | Coach | **Medication reminders rely on inexact WorkManager delays** — no AlarmManager/`setExactAndAllowWhileIdle`/`USE_EXACT_ALARM`; under Doze a clinical med alarm fires late/coalesced, not reboot-persistent. | `coach/MedicationReminderScheduler.kt:76-87`; `MedicationReminderWorker.kt:44` | Move to `AlarmManager.setExactAndAllowWhileIdle`/`setAlarmClock` gated by `canScheduleExactAlarms()`; add `USE_EXACT_ALARM` + `RECEIVE_BOOT_COMPLETED` re-arm; WorkManager fallback. _(Source data downgraded to P2; retained at P1 here given the clinical-alarm nature — confirm product intent.)_ |
| P1 | Health Connect | **Master HC toggle reports success/ON even when BP/nutrition/exercise WRITE denied** — only validates `READ_STEPS`; cold-start reconciler also only checks steps, so broken state is permanent. Mirror feature silently dead. | `ui/settings/SettingsViewModel.kt:54-65`; `SettingsScreen.kt:89-97`; `SilverBpApplication.kt:97-114` | Validate the full requested set (`containsAll(coreRequired)`); re-query each bridge's `has*Permission()`; reflect partial grants in UI. |
| P1 | Navigation | **Disconnecting Google in Settings ejects user out of the app** — `clearGoogleAccount()` flips the same `needsGoogleSignIn` gate → bounced to un-skippable login. Surprising coupling. | `ui/backup/BackupViewModel.kt:241-247`; `AppNavHost.kt:96-98` | Decouple gate from backup-account field (fixed by P0-1); disconnect should only stop auto-backup, not eject. |
| P1 | Backup | **Replace-mode restore never clears `food_log`** → stale nutrition rows survive a "clean restore"; no FK so no CASCADE removes it. | `backup/BackupManager.kt:287-315`; `NutritionEntities.kt:12-17` | Add `DELETE FROM food_log` to `clearSyncTables()`; add a test asserting every `SyncEntityType` non-CASCADE entity is cleared. |
| P1 | Backup | **Replace-mode import wipes then applies records outside any transaction** — committed DELETE then record-by-record restore; a kill/cancel mid-import leaves wiped + partial DB, no rollback. Runs in `viewModelScope`, not a foreground service. | `backup/BackupManager.kt:224-242` | Wrap clear + full apply loop in one `database.runInTransaction{}` so an interrupt rolls back to pre-import state. |
| P1 | Localization | **Entire PDF export report is hardcoded zh-TW** (title, stats, disclaimer paragraphs) + `Locale.TAIWAN` date — English users get a Chinese-only clinical PDF. | `reporting/PdfReportRenderer.kt:33-34,68-90,104-162` | Move all `drawText` literals + `DISCLAIMER_PARAGRAPHS` to `strings.xml` (en + zh-TW); pass `Context`; localize the `DateTimeFormatter`. |
| P1 | Localization | **Pairing screen + ViewModel + capture/chat/backup/report error messages all hardcoded zh-TW** (many user-facing strings/snackbars). | `ui/sync/PairingScreen.kt`, `PairingViewModel.kt`; `ui/capture/CaptureFlowViewModel.kt`; `ui/chat/ChatViewModel.kt`; `ui/report/ReportScreen.kt`/`ReportViewModel.kt`; `ui/insights/InsightsScreen.kt`; `ui/backup/BackupViewModel.kt`; `backup/auto/AutoBackupWorker.kt`; `ui/strength/WorkoutSessionScreen.kt:344` | Extract to `strings.xml` (both locales) with `%s/%d` placeholders; resolve via `stringResource`/`Context.getString`. |
| P1 | Accessibility | **Bottom-nav hides labels for inactive tabs** (`alwaysShowLabel=false`) — icon-only nav is hard for elderly target users (Favorite=Coach, Assessment=Data). | `ui/nav/AppNavHost.kt:398` | Set `alwaysShowLabel = true`. |
| P1 | Accessibility | **Icon-only buttons missing `contentDescription`** (incl. destructive Delete + overflow menu) → TalkBack reads only "button." | `ExerciseDetailScreen.kt:69,75`; `MedalsScreen.kt:59`; `CoachWeeklyReportScreen.kt:55`; `CoachWeeklyPlanScreen.kt:201`; `NarrationBlock.kt:51`; `StrengthExerciseDetailScreen.kt:64`; `LibraryScreen.kt:74` | Add meaningful `contentDescription = stringResource(...)` to each. |
| P1 | Accessibility | **Pairing back button uses a Done checkmark icon** labeled "返回" — checkmark signals confirm to elderly users; literal not localized. | `ui/sync/PairingScreen.kt:100` | Use `Icons.AutoMirrored.Filled.ArrowBack`; move strings to resources. |
| P1 | Accessibility | **BP category colors fail WCAG contrast on white light theme** (Elevated yellow 1.51:1, Normal green 2.22:1) — too faint for aging eyes. | `ui/theme/Color.kt:49-54` | Add darkened light-theme-specific category colors clearing ≥3:1 (text uses where applicable). |
| P1 | Accessibility | **Typography defines only 4 styles; heavy real content uses ~11-12sp Material defaults** — below the ~14-16sp floor for elderly. | `ui/theme/Type.kt:9-38`; `HistoryScreen.kt:206,250,257`; `Charts.kt:299-301,271` | Define fuller `Typography` with raised minimums (bodySmall ≥14sp, labelSmall ≥13sp, bodyMedium ≥16sp). |
| P1 | Accessibility | **Fixed-height 56.dp primary buttons clip/truncate text at large font scale** (elderly often 1.5-2x). | `ExerciseHomeScreen.kt:223`; `NutritionScreen.kt:110,125`; `WorkoutSummaryScreen.kt:135,143,162,174`; `WorkoutSessionScreen.kt:146`; `ConfirmReadingScreen.kt:305` | Use `.heightIn(min = 56.dp)` and allow content to wrap. |
| P1 | Insights / Today | **Today & History classify BP against hardcoded `Taiwan2022`, ignoring the user's selected guideline** — same reading shows Stage 1 (red) on Today/History but Normal/Elevated in Insights/PDF. | `ui/components/BpCategoryColors.kt:78-79`; `TodayScreen.kt:179,243`; `HistoryScreen.kt:225` | Thread the user's `guideline` into Today/History the way Insights does; pass into `classify(...)`. _(Source downgraded to P2; retained P1 here as a cross-screen correctness/trust issue — team's call.)_ |
| P1 | Chat | **Chat camera attach launches `ACTION_IMAGE_CAPTURE` with no CAMERA runtime check** — declared-but-ungranted CAMERA → `SecurityException`/dead button on a Chat-first fresh install. Same pattern in `NutritionScreen.kt:84/109`. | `ui/chat/ChatScreen.kt:116-122,160-164` | Check/request CAMERA before `camera.launch(null)`; wrap in `runCatching`; snackbar on permanent denial. |
| P1 | Recognition / ML | **AICore backend selectable on unsupported devices; `isAvailable()` is dead code** → silent blank manual draft on capture for non-Pixel devices. | `recognition/AICoreBpService.kt:48-61`; `AdvancedSettingsScreen.kt:136-151`; `CaptureFlowViewModel.kt:79-88` | Wire `isAvailable()` into Settings (hide/disable radio + note); set Error phase at capture when `isReady()` false. _(Source downgraded to P2.)_ |
| P1 | Onboarding | **`whoAmI` may persist a blank email, trapping user in login gate forever** — only `drive.appdata` scope requested; blank email persisted unconditionally; gate keys off email. | `backup/auto/GoogleDriveBackupClient.kt:162-165`; `BackupViewModel.kt:236-239` | Treat blank email as failure (don't persist); key gate-release off `permissionId`, or request an email-bearing scope. |
| P1 | ML libs / privacy | **On-device ML (ML Kit barcode/object, AICore Gemini Nano, MediaPipe/LiteRT) under-disclosed** in privacy policy. | `build.gradle.kts` (mlkit.*, mediapipe, litertlm); `recognition/AICoreBpRecognizer.kt` | Add an "On-device processing" note clarifying these backends keep data local. |
| P1 | Health Connect / privacy | **HC disclosure incomplete** — `WRITE_NUTRITION` and `READ_HEALTH_DATA_IN_BACKGROUND` (background backfill workers) not covered; "nothing in background" wording contradicts the read worker. | `AndroidManifest.xml:36-47`; `health/HealthConnectNutritionBridge.kt`; `HealthConnectBridge.kt` | Add nutrition WRITE + background-read disclosure to policy §1/§5; reconcile the "nothing in background" claim. |
| P1 | Play / config | **ProGuard keep rule targets wrong package** (`com.silverbp.sync.**` vs actual `com.silverbp.android.sync.**`) — no-op; sync types unprotected in release once any reflection/`@Serializable`/name-lookup is added. | `app/proguard-rules.pro:47` | Change to `com.silverbp.android.sync.**` (or add `consumer-rules.pro` to `:sync`); confirm release pairs + syncs. |
| P1 | Play policy (AI) | **Cloud Gemini assistant triggers AI-Generated Content policy** — needs in-app report/flag control + documented red-team safety testing (esp. medical-harm). | Chat UI; `recognition/chat/GeminiCloudChatRecognizer.kt` | Add report/flag control on AI responses; perform & record safety testing; assistant defers to professionals. |
| P1 | Play policy (privacy) | **Privacy policy URL must load and cover all data types; confirm no AD_ID merged in.** | Play Console App content; `build.gradle.kts:30` | Verify URL loads + covers HC/Gemini/Drive/deletion; declare "no Advertising ID"; check merged manifest for `AD_ID` (`tools:node="remove"` if present). |
| P1 | Permissions | **`CHANGE_WIFI_MULTICAST_STATE` declared but no `MulticastLock` is ever acquired** — dead permission. | `AndroidManifest.xml:9`; `sync/.../NsdDiscovery.kt:41-129` | Acquire a `MulticastLock` around NSD discovery, OR remove the permission after testing pairing without it. |
| P1 | Permissions | **CAMERA & RECORD_AUDIO denial paths dead-end** — retry just re-launches (no system dialog after permanent deny), no app-settings deep link; RECORD_AUDIO denial silently swallowed. Voice also dead on devices lacking a recognizer. | `ui/capture/CaptureScreen.kt:80-151`; `ui/chat/ChatScreen.kt:124-141,282-291,573-588` | Add "open app settings" affordance (reuse `PermissionGate.openAppSettings`); snackbar on `ActivityNotFoundException`; drop the unnecessary RECORD_AUDIO gate around the system recognizer. _(Voice/RECORD_AUDIO items source-downgraded to P2.)_ |

---

## 4. P2 — Post-Launch Backlog

Grouped; each is real but low-impact, an edge case, or cosmetic.

**Database / schema**
- Missing exported `14.json` (test-integrity only; v13→v17 chain has no `MigrationTestHelper` coverage). Reconstruct 14.json + extend `RoomMigrationTest` to 1→17 + CI check. `app/schemas/.../SilverBpDatabase/`
- Chat session sync apply REPLACE cascade-deletes local-only `chat_message` delta on Merge-import. `sync/ChatSyncMappers.kt:80`

**LAN sync**
- Joiner pairs with first discovered peer, ignoring scanned QR `deviceId`/service name → confusing "pairing failed" on multi-device LANs (fails safe, no MITM). `ui/sync/PairingViewModel.kt:132-146`

**Recognition / ML**
- Default OkHttp 10s read timeout aborts multi-GB model downloads on flaky links (mitigated by existing resume + manual retry). `recognition/ModelDownloader.kt:21-24`
- Confidence default diverges (parser 1.0 vs draft 0.8); LowConfidence/error catch blocks retain full-size bitmap. `recognition/BpResponseParser.kt:19-20`; `CaptureFlowViewModel.kt`
- Capture→confirm hand-off via in-memory holders → OCR drafts lost silently on process death. `ui/nutrition/NutritionDraftHolder.kt`, `MachineWorkoutDraftHolder.kt`

**Nutrition**
- Barcode network/timeout errors shown as "Product not found"; OFF returns 404 for unknown codes hitting the same branch. `ui/nutrition/BarcodeScanScreen.kt:192-211`
- Recognized photo meal with zero DB matches → Save disabled, dead-end (escape-hatch exists but unwired for the zero-match case). `ui/nutrition/NutritionConfirmScreen.kt:104-129`
- Camera permanent-denial dead-end (Retry no-ops, no Settings deep link). `ui/nutrition/BarcodeScanScreen.kt:73-153`

**Coach / WorkManager**
- Plan not regenerated on new week unless weekly worker fires → home "today task" shows stale day after week 1 (self-heals when worker eventually runs). `ui/coach/CoachViewModel.kt:118-124,438-445`
- First plan can be inserted twice (VM init vs worker race) → phase-progression corruption (narrow first-run window). `core/db/CoachEntities.kt`
- `BpAnomalyWatcher` cooldown is process-memory only, resets on cold start → mild re-fire spam; documented user-configurable cooldown is a dead/over-promised doc. `coach/BpAnomalyWatcher.kt:35,57`

**Strength**
- Summary "completed sets" count diverges from persisted sets (count completed, persist all). `ui/strength/WorkoutSummaryScreen.kt:78-90`

**Exercise**
- Per-second notification rebuilds two route bitmaps on the main thread over an unbounded polyline → jank/battery on long sessions. `exercise/ExerciseNotification.kt:172-192,415-466`

**Achievements / steps**
- Cumulative-step medals double-count session steps when HC enabled (flagged intentional gamification — confirm). `achievements/AchievementStore.kt:125`
- `computeCurrentStreak` can miss today across timezone/DST shifts (sensor-only path). `AchievementStore.kt:218-242`
- Medal-unlock notification never fires for users who deny then later grant POST_NOTIFICATIONS. `AchievementStore.kt:136-163`
- No background step sync for non-HC users; daily-step/streak medals only advance on screen-open. `achievements/StepSyncWorker.kt:15-27`

**Backup**
- Custom `BackupAgent.onFullBackup` shadows XML rules → photos dropped on phone-to-phone D2D transfer (cloud unaffected). `backup/SilverBpBackupAgent.kt:45-79`
- No schema-version compatibility gate on import (latent on single-version v1). `backup/BackupManager.kt:199-220`
- Onboarding `finish()` swallows persistence failures then advances → possible re-onboard bounce (rare; self-correcting). `ui/onboarding/OnboardingNicknameScreen.kt:113-128`

**Navigation**
- "Measure post-workout BP" CTA is inert on both summary screens (`onMeasureBp` never wired). `ui/nav/AppNavHost.kt:181-186,212-217`
- DeepLinkBus can navigate on top of onboarding/sign-in gate (weekly-report + privacy-re-consent combo). `AppNavHost.kt:111-127`
- Dead `onBack == null` "as a tab" branch + stale IME/NavBar comment in ChatScreen. `ui/chat/ChatScreen.kt:218-252,481-489`
- Cancelling generation leaves an empty assistant bubble persisted in history. `ui/chat/ChatViewModel.kt:224-226,339-342`

**Health Connect**
- Deleting a BP reading / food log leaves an orphan in Health Connect (no `deleteRecords`). `health/HealthConnectBpBridge.kt`, `HealthConnectNutritionBridge.kt`; `BpRepository.kt:54`

**Chat (perf)**
- Full-res bitmaps decoded on main thread for thumbnails (Coil already on classpath). `ui/chat/ChatScreen.kt:398-400,540-542,590-594`

**Localization / a11y (minor)**
- `ExerciseCharts` axis legend "快 ▲" hardcoded. `ui/exercise/charts/ExerciseCharts.kt:212`
- History header date pinned to `Locale.TAIWAN`. `ui/history/HistoryScreen.kt:187`
- Insights heatmap column headers ~11sp cryptic abbreviations. `ui/insights/charts/Charts.kt:298-305`
- Dark-theme outline `#3A3E4A` below 3:1 for borders. `ui/theme/Color.kt:20`
- Purple primary text borderline on dark bg (currently only large-text uses, OK). `ui/theme/Color.kt:14`

**PDF / reporting**
- PDF readings table breaks on newline notes + misaligns columns (DEFAULT header vs MONOSPACE rows). `reporting/PdfReportRenderer.kt:108-114`

**Privacy / permissions (minor)**
- Cloud chat records-context to Gemini lacks in-context disclosure (covered by P0-5 policy work). `ui/chat/RecordsContextBuilder.kt`
- `ACCESS_COARSE_LOCATION` not listed in policy §5. `AndroidManifest.xml`
- "Under 13" children clause; reframe for adult health app. `docs/privacy.html §7`
- No retention/deletion detail for Drive/Gemini copies. `docs/privacy.html §6`
- `ACCESS_NETWORK_STATE`/`ACCESS_WIFI_STATE` likely transitive — verify before pruning. `AndroidManifest.xml:10-11`
- Library-injected perms (WAKE_LOCK, RECEIVE_BOOT_COMPLETED, USE_BIOMETRIC, AICore BIND_SERVICE) all map to real features — no action. (merged manifest)

**Config / versioning**
- `versionCode = 1` / `versionName = "1.0"`: fine for a genuine first upload; bump if any prior test upload used code 1. `app/build.gradle.kts:40-41`
- Lint `FullBackupContent` (×4), backup-rules XML divergence note, `uses-native-library required=false` sanity, Maps key restriction (Cloud Console SHA-1). See Build status + checklist.

---

## 5. Build & Test Status

| Check | Result |
|-------|--------|
| Compiles (`:app`, `:sync`) | ✅ Yes |
| Unit tests (`:app:testDebugUnitTest`, `:sync:test`) | ✅ 267 tests across 40 files — 0 failures / errors / skipped |
| `:app:lintDebug` | ❌ **FAILS the build** — 99 error-severity findings |
| `connectedAndroidTest` (incl. `RoomMigrationTest`) | ⏭ Not run — no device/emulator attached |
| `bundleRelease` | ⏭ Not run — needs `MAPS_API_KEY` + signing keystore |

**Lint breakdown (blocks a clean build):**
- **`MissingTranslation` (90)** — app strings not translated to `zh` across 6 `strings_*.xml` files (strength 34, schedule 15, traininghub 14, onboarding 11, bpworkout 9, login 7). _Dominant build-aborting blocker._
- **`StringFormatMatches` (2)** — `ExerciseNotification.kt:407`, `%s` received int. **Runtime crash risk — fix.**
- **`MissingPermission` (1)** — `LocationTrackingService.kt:181` `nm.notify()` may be rejected. **Runtime risk — fix.**
- `FullBackupContent` (4), `UnsafeOptInUsageError` (1, `PairingScreen.kt:516`), `FlowOperatorInvokedInComposition` (1, `MainActivity.kt:37`) — correctness concerns; fix or baseline.

**Action:** Resolve lint before a clean release — add the `zh` translations, OR add a lint baseline / downgrade `MissingTranslation` to warning. Fix the runtime-impacting `MissingPermission` and `StringFormatMatches` regardless. Then run `:app:connectedDebugAndroidTest` (requires reconstructing `14.json` first) and `bundleRelease` with the Maps key + keystore.

---

## 6. Play Store Submission Checklist

**Account & timeline**
- [ ] Decide account type **now**. Organization account avoids the personal-account closed-testing gate.
- [ ] (Personal account only) Run a Closed test with **≥12 testers opted-in & active for 14 consecutive days** before "Apply for production" (review ~7 days). Budget **3+ weeks** calendar lead time.

**Declarations (App content)**
- [ ] **Health Apps Declaration** — select BP/exercise/nutrition categories; answer "No" to regulated medical device (triggers disclaimer requirement).
- [ ] **Foreground Services** — declare the `location` type for live workout GPS/route tracking; attach a <30s demo video; ensure FGS starts only while-in-use (no `ACCESS_BACKGROUND_LOCATION` — keep it that way to avoid the heavy background-location review).
- [ ] **Health Connect data-access review** — per-permission justification for WRITE_BLOOD_PRESSURE / WRITE_EXERCISE / WRITE_EXERCISE_ROUTE / WRITE_NUTRITION / READ_STEPS / READ_SLEEP / READ_NUTRITION / READ_HEALTH_DATA_IN_BACKGROUND. Keep NOT requesting READ_BLOOD_PRESSURE. Verify rationale screen content matches.

**Data Safety form (must match code exactly)**
- [ ] Declare **Health & fitness** (BP, exercise/route, nutrition, sleep, steps) — Collected **and Shared** (Google Drive backup + Gemini cloud).
- [ ] Declare **Precise location** (FINE — workouts) — Collected, app functionality.
- [ ] Declare **Photos** (BP/food/machine images sent to Gemini on cloud backend) — Collected + Shared.
- [ ] Declare **User IDs** (Google account email/ID for sign-in + Drive backup).
- [ ] Declare **Audio** if device SpeechRecognizer sends voice to cloud (assess), else document as device-provided.
- [ ] Check **"encrypted in transit"** (HTTPS; `usesCleartextTraffic=false`).
- [ ] Declare **NO Advertising ID** (no ads SDK; confirm no transitive `AD_ID` in merged manifest).
- [ ] **Data deletion** section — provide the web deletion URL.

**Account deletion (required — app uses sign-in)**
- [ ] Implement in-app "Delete account & data" (purges local DB, Drive backups, revokes Google credential).
- [ ] Publish a public **web** deletion-request page (e.g. alongside `shibatatsuyasilver.github.io/SilverBP/`); enter the URL in Data Safety.

**Store listing**
- [ ] **First paragraph** of the en + zh-TW description: "not a medical device…consult a healthcare professional" disclaimer. No accuracy/diagnosis claims.
- [ ] Privacy policy URL loads, non-PDF, non-geofenced, and covers HC data types, Gemini cloud, Drive backup, Open Food Facts, and deletion. **Fix the false §2/§6 claims first.**
- [ ] AI-Generated Content: in-app report/flag control + documented safety testing.

**Signing / Maps / build**
- [ ] Generate upload keystore; provide `MAPS_API_KEY` in `local.properties`; build `bundleRelease`.
- [ ] Restrict the Maps key in Google Cloud to package `com.silverbp.android` + the release/upload SHA-1 **and** the Play App Signing SHA-1 (or maps render blank on store-signed installs).
- [ ] Bump `versionCode` only if any prior test upload used code 1; otherwise leave at 1 for the first upload.
- [ ] After upload, sanity-check Play Console "Reach and devices" count (no accidental required native lib / ABI filter). Set `android.hardware.camera` `required="false"` (P2) so camera-less devices can still install.
