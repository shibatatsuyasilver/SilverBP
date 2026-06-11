# SilverBP 上架前審查報告

日期:2026-06-11。方法:12 維度並行深度程式碼審查,所有 blocker/major 級發現均經獨立對抗驗證 agent 讀原始碼確認後才列入(共 70 個 agent;誤報已剔除)。單元測試基線:`:app:testDebugUnitTest` 與 `:sync:test` 全數通過。

總計 **87** 條:🔴 6 / 🟠 48 / 🟡 9 / ⚪ 24

## 🔴 上架阻擋 (Blocker)

### B1. [chat-llm] Chat camera attachment crashes with SecurityException when CAMERA permission not yet granted

`app/src/main/java/com/silverbp/android/ui/chat/ChatScreen.kt:162`

**問題:** The attach sheet calls camera.launch(null) (ActivityResultContracts.TakePicturePreview, i.e. MediaStore.ACTION_IMAGE_CAPTURE) with no runtime permission check. AndroidManifest.xml declares android.permission.CAMERA (line 5), and Android throws a SecurityException when an app that declares CAMERA launches ACTION_IMAGE_CAPTURE without holding the grant — this is the documented MediaStore behavior. Every other camera entry point in the app (CaptureScreen.kt:82-91, MachineCaptureScreen.kt:88-97, BarcodeScanScreen.kt:69) checks/requests the permission first; ChatScreen alone does not.

**情境:** Fresh install. User taps the prominent assistant FAB, opens chat, taps '+' then '拍照' before ever using the BP-capture flow (so CAMERA was never granted) — the app immediately crashes with SecurityException. Also reproducible after the user revokes Camera in system settings. Google Play pre-launch report robo crawl is likely to hit this crash.

### B2. [gps-exercise] Checkpoint-restore path calls startForeground(type=LOCATION) without re-checking location permission — SecurityException crash on Android 14+

`app/src/main/java/com/silverbp/android/exercise/LocationTrackingService.kt:135`

**問題:** In beginTracking() (line 116) ensureForeground() runs BEFORE startLocationUpdates(). ensureForeground() calls startForeground(..., FOREGROUND_SERVICE_TYPE_LOCATION) with no try/catch; with targetSdk 36, startForeground throws SecurityException if neither ACCESS_FINE_LOCATION nor ACCESS_COARSE_LOCATION is granted. The only permission guard in the whole restore chain is the SecurityException catch inside startLocationUpdates() (line 158), which is never reached because the crash happens first. The Start-button path is gated by rememberExercisePermissionState, but the recovery path (ExerciseHomeViewModel.resumeRecoverable() line 173 → ExerciseController.restore() line 54 → ACTION_RESTORE → beginTracking()) performs zero permission checks.

**情境:** User starts a GPS walk. Mid-session they open Settings and revoke location permission (the OS kills the app process; the 10-second checkpoint survives). They reopen the app, the Exercise tab shows the 'recover session' card, they tap Resume — the service starts, startForeground throws SecurityException, and the app crashes.

### B3. [nutrition] Photo-meal camera button crashes with SecurityException when CAMERA permission not yet granted

`app/src/main/java/com/silverbp/android/ui/nutrition/NutritionScreen.kt:109`

**問題:** NutritionScreen launches ActivityResultContracts.TakePicturePreview() (MediaStore.ACTION_IMAGE_CAPTURE) via camera.launch(null) with no runtime CAMERA permission check or request. The manifest declares android.permission.CAMERA (AndroidManifest.xml line 5, needed for the CameraX barcode/BP capture flows). Per the ACTION_IMAGE_CAPTURE contract, an app targeting M+ that declares CAMERA but has not been granted it gets a SecurityException ('starting Intent ... with revoked permission android.permission.CAMERA') when starting the capture intent, which propagates out of onClick and crashes the app. Only BarcodeScanScreen requests the permission; the photo button does not. (ChatScreen.kt:117 has the same pattern.)

**情境:** Fresh install. User opens the Nutrition tab (no camera permission has ever been requested) and taps the '拍照' button to photograph a meal. App immediately crashes with SecurityException instead of opening the camera.

### B4. [onboarding-nav] Google sign-in is a hard gate with no skip/offline path — users without Play Services, network, or willingness to grant Drive access are permanently bricked after onboarding

`app/src/main/java/com/silverbp/android/ui/nav/AppNavHost.kt:96`

**問題:** Seed concern CONFIRMED. AppNavHost.kt:91-106 implements what its own comment calls a 'Hard gate — a user without a Google account cannot pass': whenever didOnboard is true and googleAccountEmail is blank, ONBOARDING_LINK is pushed with popUpTo(HOME){inclusive=true}, so HOME is removed from the back stack. LinkAccountScreen.kt (lines 88-154) offers exactly one action: the sign-in button. On failure (vm.autoBackupErrors) it shows an error + Retry — there is no Skip, no 'continue without backup', no exit. The failure chain is total: GoogleAuthClient.requestDriveToken (GoogleAuthClient.kt:63-79) throws ApiException on devices without Google Play Services or without network; BackupViewModel.startGoogleConnect (BackupViewModel.kt:205-218) catches it and emits an error; user cancelling the consent sheet yields TokenResult.Cancelled (BackupViewModel.kt:212, 228); even after consent succeeds, finishConnect calls drive.whoAmI over the network (BackupViewModel.kt:236-239) which throws offline. Every path loops back to the same dead-end retry screen. The app's core features (local BP logging for elderly users) do not require Google at all, yet are unreachable. This is a near-certain Play review flag (login wall blocking all functionality, app non-functional on non-GMS devices, non-functional offline at first launch).

**情境:** Install the app on a phone with no Google account configured (common for elderly users whose family set up the phone), or simply turn off Wi-Fi/mobile data, complete the 7-step onboarding, and land on the '連結 Google 帳號' screen. Tap the button → error → Retry → error, forever. The user can never reach the home screen; force-closing and relaunching returns straight to the same gate. Same outcome if the user taps Cancel on the Google consent sheet because they don't want cloud backup.

### B5. [play-policy] Health Connect permissions require an approved Play Console Health Apps Declaration — missing from the entire release process

`app/src/main/AndroidManifest.xml:36`

**問題:** The manifest declares 8 android.permission.health.* permissions (WRITE_BLOOD_PRESSURE, WRITE_EXERCISE, WRITE_EXERCISE_ROUTE, READ_STEPS, READ_SLEEP, READ_NUTRITION, WRITE_NUTRITION, READ_HEALTH_DATA_IN_BACKGROUND, lines 36-47). Since 2024, Google Play rejects any release containing health.* permissions unless the developer has (a) submitted and had APPROVED the 'Health apps declaration' in Play Console -> App content, justifying each individual permission against an allowed use case, and (b) set the app category to an eligible one (Health & Fitness or Medical). The release checklist in /Users/tatsuyashiba/Documents/SilverBP/RELEASE.md (section 5, lines 63-68) lists only Data Safety, graphics, screenshots, and IARC — the Health Apps Declaration is never mentioned, so the developer will hit a hard rejection at first AAB review. Action required: complete the declaration for all 8 permissions, pick Health & Fitness category, and ensure the privacy policy URL in the declaration matches the hosted policy.

**情境:** Developer follows RELEASE.md, uploads the AAB to Closed testing, and the release is blocked with 'Your app uses health permissions but does not have an approved Health apps declaration' — review cannot proceed until the declaration is filed and approved, which itself takes days to weeks.

### B6. [sync] No LWW/HLC gate when applying inbound records — stale peer copies blindly overwrite newer local edits

`app/src/main/java/com/silverbp/android/sync/BpReadingSyncMapper.kt:130`

**問題:** Every mapper's apply() writes through unconditionally: BpReadingSyncMapper.apply ends in bpDao.insert(entity) where BpDao.insert is @Insert(onConflict = REPLACE) (BpDao.kt:24), with no comparison of record.hlc against the local row's hlcUpdatedAt. The mapper's own doc comment (line 37) says 'Caller is responsible for the LWW gate (record.hlc > local.hlcUpdatedAt)', but the caller — CombinedRoomSyncSink.apply (RoomSyncAdapters.kt:484-507) — just dispatches to the mappers, and SyncSession.run (sync/.../protocol/SyncSession.kt:78) calls sink.apply(rec) for every record. The Merger interface that was supposed to enforce LWW (sync/.../engine/Merger.kt:11) is an unimplemented 'Phase 1 stub' with zero implementations. Since both peers dump ALL rows every round (recordsSince ignores peerLastHlcSeen), every conflicting row is overwritten in both directions: each device ends the round holding the OTHER device's version. The same hole applies to tombstone records — a stale tombstone deletes a newer row without any HLC check.

**情境:** User edits a BP reading's systolic value (or a food log note) on phone A, then pairs/syncs with tablet B which still has the old copy. During the round, B's stale record REPLACEs A's edited row — A silently loses the edit while B receives it, leaving the two devices permanently swapped/divergent on that health record. Repeats every sync round.

## 🟠 重要 Bug (Major)

### M1. [backup] Custom BackupAgent copies live DB + WAL files without checkpoint; cloud restore can be torn/corrupt

`app/src/main/java/com/silverbp/android/backup/SilverBpBackupAgent.kt:69`

**問題:** Because the app declares a custom android:backupAgent (AndroidManifest.xml line 55), Android does NOT launch it in restricted mode for Auto Backup — SilverBpApplication.onCreate runs (SilverBpApplication.kt lines 28-50), which inits ServiceLocator, starts bpAnomalyWatcher and several WorkManager reconcile coroutines that open and write the Room DB. onFullBackup then copies silverbp.db, silverbp.db-wal and silverbp.db-shm one at a time (lines 68-78) with no `PRAGMA wal_checkpoint(TRUNCATE)`, no db close, and no write lock. A write landing between the .db copy and the -wal copy produces a mutually inconsistent file set. On restore, SQLite either replays a mismatched WAL or detects corruption; Room's default corruption handler deletes the DB, so the user ends up with empty data after a 'successful' device-to-device or cloud restore.

**情境:** User has app-lock off (plain SQLite). Overnight, Android runs Auto Backup while a coroutine from Application.onCreate (e.g. step-sync or anomaly watcher) writes a row. The Drive snapshot contains a .db copied before the write and a -wal copied after it. User buys a new phone, restores from Google backup, opens SilverBP — SQLite reports corruption, Room wipes the DB, and all BP/medication history appears gone.

### M2. [backup] Replace-mode restore wipes DB, then applies records non-atomically; leaving the screen mid-restore silently drops the rest of the data

`app/src/main/java/com/silverbp/android/backup/BackupManager.kt:238`

**問題:** In import(), Replace mode commits clearSyncTables() in its own transaction (line 224-226), then applies records one by one with NO enclosing transaction. The per-record `catch (t: Throwable)` at line 238 also swallows CancellationException. The import runs in viewModelScope (BackupViewModel.kt line 153/308), so if the user navigates back off BackupScreen, the ViewModel is cleared, the job is cancelled, every remaining sink.apply() throws CancellationException, each is caught and counted as 'skipped', and the loop spins to the end. The wipe is already committed, so everything after the cancellation point is permanently missing from the DB. Process death mid-restore has the same effect with no skip accounting at all.

**情境:** User picks a backup, selects 「取代」(Replace), taps 開始匯入, then presses Back to the previous screen while the restore is still running. The DB was already wiped; only the records applied before navigation survive. The user sees no error (the screen with PhaseRow is gone) and later discovers months of readings missing. Recoverable only if they realize and re-import the same file.

### M3. [backup] Replace-mode restore never clears the food_log table — 'replace' silently merges nutrition data

`app/src/main/java/com/silverbp/android/backup/BackupManager.kt:292`

**問題:** clearSyncTables() (lines 287-315) enumerates the sync tables to DELETE for Replace mode but omits `food_log`, even though FOOD_LOG (v16) is a fully synced entity included in export (ServiceLocator.kt lines 416-417/441) and applied on import (RoomSyncAdapters.kt line 507). The doc comment even claims '清空 21 個 sync 表' but food_log is missing from the list. Replace-mode semantics ('還原備份到全新狀態') are violated for nutrition records: local rows not present in the backup survive the restore.

**情境:** User logs 30 food entries on the phone after the backup was taken, then restores that older backup with Replace mode expecting the device to exactly match the backup. BP, medication, exercise etc. are reset to the backup state, but all 30 newer food-log entries (including ones they had deliberately wanted gone, e.g. test data) remain, producing an inconsistent mixed state.

### M4. [backup] Rotating the recovery code destroys the stored code before the new one is verified — cancelling mid-flow breaks auto-backup

`app/src/main/java/com/silverbp/android/ui/backup/BackupViewModel.kt:105`

**問題:** rotateRecoveryCode() calls recoveryStore.clear() immediately (line 105) and only puts the new code in the in-memory _pendingRecoveryCode; it is persisted only after the user completes the ShowCode → VerifyCode retype in ExportDialog (commitRecoveryCode, line 98). If the user dismisses the dialog at either step (BackupScreen.kt lines 547-583), or the process dies, the store is left empty: hasRecoveryCode becomes false, 「查看恢復碼」 disables, 「立即備份」/frequency controls disable, and every scheduled AutoBackupWorker run fails with 「未設定恢復碼」 (AutoBackupWorker.kt lines 37-44) until the user happens to redo setup. The destructive clear should happen at commit time, not at generate time. (Side note: backup_rotate_warning also misstates the consequence — old .sbpbk files remain decryptable with the OLD paper code on any device; rotation does not invalidate them.)

**情境:** User taps 重新生成恢復碼, confirms the warning, the new-code dialog appears, then they get a phone call and dismiss it. From that moment their daily Drive auto-backup silently fails every cycle with 「未設定恢復碼」; they only notice if they reopen the Backup screen and read the small status row.

### M5. [bp-capture] Rotation or process death on the confirm screen silently wipes the OCR draft, photo, and all user edits

`app/src/main/java/com/silverbp/android/ui/confirm/ConfirmReadingScreen.kt:85`

**問題:** ConfirmReadingScreen runs `LaunchedEffect(readingIdArg) { vm.initWith(readingIdArg) }`. The activity has no orientation lock or configChanges (AndroidManifest.xml:122), so rotating recreates the composition and re-runs the effect. For arg "draft", initWith (ConfirmReadingViewModel.kt:56-57) calls `CaptureSessionHolder.take()` — which was already consumed and cleared on first entry — and falls back to a blank `BpReadingDraft(timestamp = Instant.now())`, overwriting the ViewModel's surviving _draft StateFlow. The same re-init resets a "new" manual entry to blank and reloads an edit from DB, discarding unsaved edits. Process death is worse: CaptureSessionHolder is an in-memory AtomicReference and the ViewModel has no SavedStateHandle, so the restored route also lands on a blank form.

**情境:** User photographs their BP monitor, OCR fills in 142/88 with the photo attached on the confirm screen, the user rotates the phone (or the app is killed in background while they answer a call) — the form comes back completely blank, photo gone; they must retake the photo and re-enter everything.

### M6. [bp-capture] Double-tapping Save inserts duplicate BP readings (no in-flight guard, new UUID per attempt)

`app/src/main/java/com/silverbp/android/ui/confirm/ConfirmReadingViewModel.kt:77`

**問題:** `save()` launches a coroutine with no isSaving flag and the Save TextButton (ConfirmReadingScreen.kt:116-118) stays enabled while the first save runs. Each invocation calls `current.toReading(...)` which constructs a BpReading with a fresh `UUID.randomUUID()` default (core/Models.kt:36), so `repo.upsert` (BpRepository.kt:38) sees no existing row and inserts a second record. The coroutine also does a disk write (`writePhotoToDisk`, JPEG compress) plus an optional Health Connect mirror before `onDone()`, leaving a window of hundreds of milliseconds during which a second tap lands. Each duplicate also writes its own copy of the photo JPEG and mirrors twice to Health Connect.

**情境:** An elderly user (the app's target demographic) taps Save twice in quick succession on a slow phone; the history list and all stats/averages now show the same 142/88 reading twice, and they must find and delete the duplicate manually.

### M7. [bp-capture] Failed camera capture navigates from a CameraX background thread, crashing the app

`app/src/main/java/com/silverbp/android/ui/capture/CaptureScreen.kt:210`

**問題:** `capturePhoto` passes `Executors.newSingleThreadExecutor()` to `capture.takePicture` (line 300), so both `onImageSaved` and `onError` callbacks run on that background thread. In the shutter handler, the `bmp == null` branch (onError, or EXIF decode failure) calls `CaptureSessionHolder.put(...)` then `onCaptured("draft")`, which is `rootNav.navigate(Routes.confirmEdit(...))` (AppNavHost.kt:153). NavController.navigate mutates back-stack entries whose LifecycleRegistry enforces main-thread access, throwing IllegalStateException ("Method ... must be called on the main thread") — an uncaught exception on a raw executor thread that kills the process. The success path is safe only because it goes through viewModelScope (Main dispatcher).

**情境:** User taps the shutter just as the camera is being unbound (backgrounding the app, incoming call, or any ImageCaptureException such as storage pressure); instead of falling back to manual entry as intended, the app crashes.

### M8. [bp-capture] save() rethrows inside viewModelScope — any photo-write or DB failure crashes the app instead of showing an error

`app/src/main/java/com/silverbp/android/ui/confirm/ConfirmReadingViewModel.kt:104`

**問題:** The catch block in `save()` logs and then does `throw e` inside `viewModelScope.launch`. An exception rethrown from a launch-ed coroutine is unhandled and crashes the process. Real failure sources exist in the try block: `writePhotoToDisk` (line 119-124) opens a FileOutputStream and JPEG-compresses — IOException on a full disk — and `repo.upsert` can throw SQLiteFullException or a Health Connect bridge error. The user gets no error UI; the draft is lost with the crash.

**情境:** User with nearly-full storage photographs a reading and taps Save; the JPEG write throws IOException and the app hard-crashes, losing the reading, instead of saving without the photo or showing a message.

### M9. [bp-capture] Gallery-picked photos are decoded at full resolution with no downsampling — OOM on large images

`app/src/main/java/com/silverbp/android/recognition/ImagePreprocess.kt:73`

**問題:** `decodeUriWithExif` calls `BitmapFactory.decodeStream(it)` with no BitmapFactory.Options/inSampleSize, then `rotateByExif` may allocate a second full-size copy via `Bitmap.createBitmap` (line 57). CaptureFlowViewModel.loadFromUri (CaptureFlowViewModel.kt:121) feeds this any image from the photo picker. A 50MP photo (8160x6144, common on 2024+ phones) is ~200MB ARGB_8888, plus a rotated copy ~400MB transiently — well past typical 256-512MB app heaps. Downsampling to 1024px only happens after the full decode (CaptureFlowViewModel.kt:58). The error/low-confidence catch paths (CaptureFlowViewModel.kt:97,101,110,114) additionally stash the full-resolution bitmap into the draft instead of the downsized one.

**情境:** User taps the gallery icon on the capture screen and selects a photo of their BP monitor taken with the phone's main 50MP camera; the app throws OutOfMemoryError and crashes before recognition starts.

### M10. [chat-llm] Local engine can be torn down (or used by OCR) concurrently with an in-flight chat stream — native use-after-free

`app/src/main/java/com/silverbp/android/recognition/chat/GemmaChatService.kt:46`

**問題:** GemmaChatService grabs the raw engine via GemmaBpService.engineOrNull() and streams from a Conversation under its OWN private Mutex (GemmaChatService.kt:31). GemmaBpService.tearDown() (GemmaBpService.kt:165-170) closes the native engine under GemmaBpService's separate mutex, so nothing stops tearDown from calling engine.close() while a chat Conversation is mid-sendMessageAsync. tearDown is triggered by Settings actions: ModelBootstrap.switchTo (model variant change), reloadCurrentVariant (maxNumTokens change), preloadAICore (backend switch), and ModelBootstrap.shutdown() on MainActivity.onDestroy. Similarly, GemmaBpService.extract/generate (lines 172, 231) take no lock at all, so a chat generation and an OCR/nutrition extraction can run two Conversations on the same engine concurrently.

**情境:** User starts a local-model chat reply (30-60 s on a mid-range phone), then while it streams goes to Settings → Advanced and switches the model variant or toggles speculative decoding (or simply backs out of the app, firing shutdown()). The native LiteRT engine is closed under the still-streaming Conversation — use-after-free in native code, typically a SIGSEGV crash that ends the process.

### M11. [chat-llm] Failed/corrupt model download permanently wedges with 'HTTP 416' — stale .part file is never deleted

`app/src/main/java/com/silverbp/android/recognition/ModelDownloader.kt:67`

**問題:** On sha256 mismatch (line 67) the flow throws but the full-size .part file is left on disk; no code path ever deletes it (ModelBootstrap.cleanupLegacyModelFiles explicitly skips .part at ModelBootstrap.kt:75). The next download attempt sends 'Range: bytes=<fullLength>-' (line 44), the server answers 416 Range Not Satisfiable, and check(response.isSuccessful) fails with 'HTTP 416' — forever, on every retry, while a 2-4.4 GB orphan eats storage. A second corruption source feeds this: the code never distinguishes HTTP 200 from 206 when resuming (line 45-50) — if the server ignores Range and returns the full body, it is APPENDED to the existing partial (FileOutputStream(partial, true)), producing a corrupt file that then trips the sha256 check and enters the same wedge. Only escape is clearing app data.

**情境:** User starts the 2 GB Gemma download in Advanced Settings; HuggingFace re-uploads the file (which the catalog comment at ModelCatalog.kt:43 says has happened before) or the resume gets a 200. Banner shows '模型載入失敗:sha256 mismatch'. Every subsequent tap of 下載並載入模型 now fails instantly with '模型載入失敗:HTTP 416', and 2 GB of storage stays consumed, until the user clears the app's data.

### M12. [chat-llm] Voice input fails completely silently on permission denial or missing speech recognizer

`app/src/main/java/com/silverbp/android/ui/chat/ChatScreen.kt:140`

**問題:** Two silent dead ends: (1) the RECORD_AUDIO permission callback does nothing when granted == false (line 139-141 has no else branch) — after a permanent denial ('Don't ask again'), every mic tap instantly auto-denies and produces zero UI feedback; (2) launchVoice wraps the ActivityNotFoundException from launching ACTION_RECOGNIZE_SPEECH in runCatching and only logs it (lines 584-588), so on devices with no Google speech service (de-Googled / many China-market devices — relevant for a zh-TW elderly audience) the mic button silently does nothing. Note also the intent-based RecognizerIntent flow does not actually require the calling app to hold RECORD_AUDIO, so the permission gate adds a failure mode without being needed.

**情境:** An elderly user denies the mic permission once with 'don't ask again' (or uses a phone without the Google app). From then on, tapping the microphone icon in the chat input bar does absolutely nothing — no snackbar, no dialog, no hint — and the user concludes voice input is broken.

### M13. [coach-workers] Weekly plan phase is permanently stuck in DeRamp: adherence ratio counts Diet/Sleep/Medication tasks that can never be completed

`app/src/main/java/com/silverbp/android/coach/CoachEngine.kt:202`

**問題:** derivePhase() (CoachEngine.kt:197-207) uses adherenceRatio(), which sums done/total across ALL coach_task rows of the prior plan (CoachEngine.kt:209-219, backed by CoachPlanDao.adherenceForPlan). But buildTasks() creates 7 Diet + 7 Sleep + 7 Medication tasks per week (CoachEngine.kt:267-295) and the only code that ever writes completedAt is CoachViewModel.buildReadyState, which mirrors strictly the Exercise task (CoachViewModel.kt:303-332; CoachRepository.kt:176-180 explicitly documents that completedAtMillis is never set for Diet/Sleep). With the default 5 exercise days, the maximum possible adherence is 5/26 ≈ 0.19, always below the 0.5 DeRamp threshold at CoachEngine.kt:202. The hardcoded medication task targetValue=1.0 (CoachEngine.kt:286-295) is otherwise inert — rings use real per-schedule counts — but these never-completable tasks poison the phase denominator. Phase.Ramp and Phase.Hold are unreachable after week 1.

**情境:** A user completes every scheduled walk in week 1 (100% real exercise adherence). On Monday the WeeklyReportWorker generates week 2's plan: phase is DeRamp, so their walking minutes are cut by 30% (perSessionMinutes * 0.7). Every subsequent week is also DeRamp. The coach permanently de-trains a perfectly adherent user and never progresses them, the opposite of the documented Ramp behavior.

### M14. [coach-workers] Changing the daily reminder time in Settings never takes effect (ExistingPeriodicWorkPolicy.UPDATE ignores the new initial delay after first run)

`app/src/main/java/com/silverbp/android/coach/CoachReminderScheduler.kt:71`

**問題:** scheduleDaily() re-enqueues the 24h periodic DailyReminderWorker with a freshly computed initialDelay but uses ExistingPeriodicWorkPolicy.UPDATE (line 71; same for weekly at line 83). Per WorkManager semantics (project uses WorkManager 2.10.0), UPDATE preserves the existing work's period start and next-run time — initialDelay is only honored if the work has never run. SettingsViewModel.setReminderTime → rescheduleReminders → scheduleAll (SettingsViewModel.kt:137-158) therefore changes nothing once the worker has fired at least once: the reminder keeps firing at the old time forever. The same applies to the cold-start realignment in SilverBpApplication.reconcileCoach, so DST shifts are also never corrected. The fix is ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE (or cancel + enqueue) when the target time changes.

**情境:** User sets the daily coach reminder to 07:00. After receiving it for a few days they change the time to 20:00 in Settings. The notification keeps arriving at ~07:00 every day; nothing the user does in Settings (changing the time again, changing weekday mask) moves it. Only toggling the whole Coach feature off and on (which goes through cancelAll) fixes it.

### M15. [coach-workers] Medication reminders fire at the wrong wall-clock time after a DST transition or timezone change, and are not exact alarms

`app/src/main/java/com/silverbp/android/coach/MedicationReminderScheduler.kt:75`

**問題:** scheduleOne() converts the next wall-clock firing (nextFiringMillis) into a fixed elapsed-millis initialDelay at enqueue time (line 75-77). If the UTC offset changes between enqueue and fire (DST fall-back/spring-forward, or the user travels/changes timezone), the worker fires at the old UTC instant — off by the offset delta in local time. There is no ACTION_TIMEZONE_CHANGED/ACTION_TIME_CHANGED receiver to re-enqueue; correction only happens after the worker fires once (self-reschedule) or on the next app cold start. Additionally, the chain uses plain WorkManager one-time work with no expedited/exact-alarm mechanism, so Doze/OEM batching can delay a dose reminder by 15min-hours; if a late-evening reminder slips past midnight, CoachNotifier.postMedicationReminder computes dayStart at post time (CoachNotifier.kt:177), so the 'Mark as taken' action records the dose against the wrong calendar day.

**情境:** An elderly user takes BP medication daily at 08:00. On the night daylight saving ends, the queued reminder fires at 07:00 (or 09:00 going the other way) local time, and every reminder that was already enqueued for that week is off by an hour until the app is reopened. Separately, on an aggressive OEM (e.g. the Vivo devices this app is tested on), a 23:00 reminder deferred by Doze to 00:20 marks the dose on the next day's log when tapped, leaving yesterday's dose showing as missed.

### M16. [coach-workers] Weekly report medication adherence percentage is computed from dose rows that only exist when the user interacted — shows 100% for partial adherence and 0% for silent full adherence

`app/src/main/java/com/silverbp/android/coach/CoachRepository.kt:121`

**問題:** medicationAdherence() uses doses.countScheduledInRange as denominator, which is 'SELECT COUNT(*) FROM medication_dose' in the range (CoachDao.kt:147-148). medication_dose rows are only created when the user toggles the in-app Switch (CoachLogMedicationScreen.kt:160-172) or taps the notification's 'Mark as taken' (MedicationActionReceiver, always taken=true). Scheduled-but-untouched doses produce no row, so the denominator is 'doses interacted with', not 'doses scheduled'. The correct schedule-derived denominator already exists (countScheduledInWeek, CoachRepository.kt:210-222) but is only used for the rings, not the report. This wrong number feeds WeeklyReport.medAdherence (CoachEngine.computeWeeklyReport line 129) and the LLM narrator prose on the weekly report screen.

**情境:** A user with one daily 08:00 medication taps 'Mark as taken' on 3 of 7 days and ignores the other 4 reminders. The Monday weekly report announces 100% medication adherence (3/3 rows) and the narrator praises them, when true adherence is 43%. Conversely a user who takes the med every day but never logs it sees 0%.

### M17. [coach-workers] BP anomaly alert re-fires every 30+ minutes for the same already-alerted readings, including after the user's BP returns to normal

`app/src/main/java/com/silverbp/android/coach/BpAnomalyWatcher.kt:57`

**問題:** detectAndMaybePost() runs on every new BP reading and calls CoachEngine.detectAnomaly(), which scans the entire trailing 24h window for any 3-consecutive-elevated run (CoachEngine.kt:51-73). The only dedup is the in-process lastFiredAtMillis 30-minute cooldown (line 57); the anomaly's triggeredAtMillis is never compared against the last alerted event, and the cooldown state dies with the process. So one elevated episode keeps re-triggering the IMPORTANCE_HIGH 'BP critical' alert on every subsequent reading logged more than 30 minutes later within 24h — even readings that are completely normal — and again after any process restart.

**情境:** A user logs three readings ≥180/110 at 09:00 and gets the critical alert (correct). They rest, then log a normal 128/82 at 11:00 — the watcher re-detects the 09:00 window and posts the 'blood pressure critical' high-importance alert again. Same at 14:00, 17:00, 20:00. A hypertensive senior is repeatedly told their BP is in crisis all day despite normal readings, causing real distress or unnecessary ER contact.

### M18. [coach-workers] Coach screen shows a stale previous week's plan (with no regeneration) whenever the WeeklyReportWorker didn't fire — currentPlan query has no upper bound

`app/src/main/java/com/silverbp/android/core/db/CoachDao.kt:39`

**問題:** currentPlan/observeCurrentPlan select 'WHERE weekStart <= :nowMillis ORDER BY weekStart DESC LIMIT 1' — any old plan matches; there is no 'now < weekStart + 7d' bound. CoachViewModel.init only generates a plan when currentPlan returns null (CoachViewModel.kt:118-123), and DailyReminderWorker does the same (DailyReminderWorker.kt:41-44). New-week generation therefore depends entirely on WeeklyReportWorker firing Monday 07:00 — which is cancelled while Coach is toggled off and is first re-enqueued for the NEXT Monday on re-enable (CoachReminderScheduler.scheduleWeekly). todayDayOffset coerces the offset into 0..6, so a stale plan silently renders its Sunday tasks.

**情境:** A user disables Coach in Settings for two weeks, then re-enables it on a Wednesday. The Coach tab shows the two-week-old plan's Sunday task as 'today's task' (and the daily reminder notification echoes it) for five days until the weekly worker finally runs the following Monday. Also affects everyone in the Monday 00:00–07:00 window, where last week's Sunday task is shown as today's.

### M19. [db-migrations] SQLCipher encrypt/decrypt file swap is not crash-atomic and has no startup recovery — process death in the swap window means empty DB or permanent crash loop

`app/src/main/java/com/silverbp/android/security/DbCipherMigration.kt:134`

**問題:** Step 5 of transform() executes a multi-step non-atomic sequence: sidecars.forEach{delete()} (line 133) -> main.delete() (line 134) -> side.renameTo(main) (line 135) -> keyStore.setDbEncrypted(toEncrypted) (line 137). Two unrecoverable failure points: (a) if the process dies/power is lost after main.delete() but before renameTo(), silverbp.db no longer exists and the encrypted marker is still false, so on next launch Room silently creates a brand-new empty database — the user sees all BP/health history wiped. (b) If death occurs after renameTo() but before the marker write reaches disk (DbKeyStore.setDbEncrypted at DbKeyStore.kt:71 uses prefs.edit().apply(), which is asynchronous), the on-disk file is SQLCipher ciphertext while isDbEncrypted() returns false, so SilverBpDatabase.build() opens it with plain SQLite and throws 'file is encrypted or is not a database' on every launch — a permanent crash loop. The .bak snapshot files are still on disk in both cases, but a repo-wide grep confirms no code outside transform() ever looks for *.bak or *.mig, so there is no startup reconciliation or self-heal. Fix: write a journal/state flag before the swap and reconcile at startup (try the passphrase if plain open fails; restore .bak if main is missing), and use commit() instead of apply() for the marker.

**情境:** A user with months of BP history enables app-lock encryption in Settings; the phone battery dies (or the OS kills the app) during the half-second swap window. On reboot the app either shows a completely empty database (all readings 'gone') or crashes on every launch with no recovery path other than clearing app data — which destroys the data for real.

### M20. [db-migrations] Unguarded EncryptedSharedPreferences/Keystore init in the Room open path can crash-loop ALL users, including those who never enabled encryption

`app/src/main/java/com/silverbp/android/core/db/SilverBpDatabase.kt:110`

**問題:** SilverBpDatabase.build() unconditionally calls DbKeyStore.create(appContext) (line 110) before every first DB open. DbKeyStore.create() (DbKeyStore.kt:98-110) constructs a MasterKey and EncryptedSharedPreferences (security-crypto 1.1.0-alpha07, a deprecated alpha library) — both of which have well-documented field failure modes that throw (KeyStoreException after OS updates, AEADBadTagException / InvalidProtocolBufferException if the prefs keyset is corrupted). There is no try/catch, so any such exception propagates out of Room.databaseBuilder...build()/get() and crashes the app on every DB access — for the entire user base, even the overwhelming majority that never opted into app-lock encryption and gets zero benefit from this code path. The team clearly knows this call can throw: the identical call in SilverBpBackupAgent.onFullBackup is wrapped in runCatching{...}.getOrDefault(false) (SilverBpBackupAgent.kt:46-48). Fix: wrap the keystore read in build(); when it fails and no encryption was ever enabled (the marker defaults to false), fall back to a plain SQLite open instead of crashing.

**情境:** A user who never touched the app-lock setting takes an OTA Android update that leaves their hardware Keystore in a transiently bad state (a recurring Play Console crash signature across apps using EncryptedSharedPreferences). The next app launch throws from DbKeyStore.create inside the DB open and the app crash-loops on startup until they clear app data — losing all locally stored health records.

### M21. [gps-exercise] START_STICKY restart with null intent never calls startForeground or stopSelf — ForegroundServiceDidNotStartInTimeException crash loop

`app/src/main/java/com/silverbp/android/exercise/LocationTrackingService.kt:112`

**問題:** onStartCommand returns START_STICKY, but the when(intent?.action) block at line 79 has no branch for intent == null. When the system kills the process mid-session (low memory, aggressive OEM battery manager) and later restarts the sticky service, intent is null: the new service instance neither calls startForeground() nor stopSelf(). Because the original start was via startForegroundService (ExerciseController line 22/69) the fgRequired flag is re-delivered on restart, so after ~5s the system throws android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException, crashing the app in the background. Even on devices where it doesn't crash, the service sits idle with an empty LiveStore doing nothing. Null intent should at minimum stopSelf(), or better, auto-restore from the checkpoint.

**情境:** User is on a long walk with the screen off; the OS kills the app under memory pressure and restarts the sticky service a minute later. The app crashes in the background (crash dialog / Play Console ANR-crash report). The user finds the workout notification gone and a crash report; the session is only recoverable via the checkpoint card.

### M22. [gps-exercise] activeDurationMillis counts entire GPS-dropout gaps as active time (no gap cap like distance has)

`app/src/main/java/com/silverbp/android/exercise/ExerciseSessionLiveStore.kt:216`

**問題:** Duration only accrues inside appendSample(): durationDeltaMs = (nowMs - cur.lastSampleAtMillis) whenever the previous state was Running. Auto-pause can only trigger when samples arrive (line 196), and indoor fixes with accuracy > 50 m are dropped at line 168 without updating lastSampleAtMillis. So if GPS fixes stop for N minutes while Running (mall, tunnel, building), the first good fix afterwards adds the entire N-minute gap to activeDurationMillis in one jump. Distance is protected against the same gap by MAX_DISTANCE_GAP_MS = 45 s (line 190), but duration has no equivalent cap, so pace (duration/distance) is also corrupted.

**情境:** User starts a 30-minute walk, stops at a convenience store for 15 minutes (no usable GPS indoors; the session-screen timer freezes and shows the 'GPS weak' banner), then walks out. On the first new fix the timer leaps forward 15 minutes; the saved session reports ~45 min of 'active' time and a wildly slow average pace.

### M23. [gps-exercise] Distance keeps accruing from GPS jitter while session is AutoPaused

`app/src/main/java/com/silverbp/android/exercise/ExerciseSessionLiveStore.kt:221`

**問題:** appendSample() lets samples through when runState is AutoPaused (line 164) so a moving fix can auto-resume, but deltaMeters is computed and added to accumulatedDistanceMeters (line 221) regardless of state — only durationDeltaMs is gated on Running (line 216). While the user stands still in AutoPaused, every 3-second fix contributes its position-jitter haversine delta (typically 1–10 m per fix under 50 m accuracy). Route points are also appended for every stationary fix, drawing a scribble on the map. deltaMeters should be zeroed (or the point skipped) while not Running.

**情境:** User pauses at a park bench for 10 minutes mid-walk; the session auto-pauses (timer stops) but the distance figure creeps up by tens to hundreds of meters from GPS drift, and the route map shows a jitter blob at the bench. The saved distance/pace are inflated despite 'auto-pause'.

### M24. [gps-exercise] Workout is unrecoverable if the app dies on the Summary screen before Save — checkpoint cleared at stop, DB write only on Save

`app/src/main/java/com/silverbp/android/exercise/ExerciseSessionLiveStore.kt:291`

**問題:** snapshotAndFinish() deletes the crash-recovery checkpoint (checkpoint?.clear(), line 291) the moment the user taps Stop, but the session is only written to Room when the user taps Save on the Summary screen (ExerciseSummaryViewModel.save → repo.upsert). Between Stop and Save the only copy of the workout lives in the in-memory LiveStore/ViewModel. Any process death in that window (user switches apps to take a call and the OS reclaims the process, device reboot) silently loses the entire workout — no checkpoint, no DB row, no recovery offer. Auto-saving the snapshot (or keeping the checkpoint until save/discard) would close the window.

**情境:** User finishes a 1-hour walk, taps Stop, lands on the summary, then answers a phone call / opens another app before tapping Save. The OS kills the backgrounded app. When they return, the workout is gone with no trace and no recovery prompt.

### M25. [gps-exercise] 1 Hz notification loop does checkpoint JSON serialization and large bitmap rendering on the main thread — jank/ANR risk grows with session length

`app/src/main/java/com/silverbp/android/exercise/LocationTrackingService.kt:189`

**問題:** startNotificationRefresh() launches lifecycleScope.launch (Dispatchers.Main) and every second builds the notification — ExerciseNotification.build → obtainBitmaps renders a up-to-1024-px-wide route bitmap plus thumbnail on the main thread whenever the route grew (every ~3 s) or every 5 s — and every 10th tick calls liveStore.persist(), which synchronously kotlinx-serializes the ENTIRE route (one point per ~3 s; a 2-hour walk is ~2,400 points, hundreds of KB of JSON) and writes it to disk with file.writeText (SessionCheckpointStore.save, line 27), still on the main thread. The service shares the main thread with the app's Compose UI, so the live map screen janks on every checkpoint, and the cost grows linearly with session duration; very long sessions risk ANR. onStartCommand's pause/resume persist() calls are also main-thread disk I/O.

**情境:** User keeps the live session map open during a 2-hour hike. Every 10 seconds the UI visibly stutters (route serialization + file write on the UI thread), worsening as the route grows; on slow flash storage this can escalate to an ANR report in Play Console.

### M26. [gps-exercise] Resuming a checkpoint with only approximate (coarse) location yields a permanently dead session — every fix filtered, no error surfaced

`app/src/main/java/com/silverbp/android/exercise/ExerciseSessionLiveStore.kt:168`

**問題:** The Start path requires precise location (PermissionGate rejects when fineGranted is false, line 110-114 of PermissionGate.kt), but the restore path (ExerciseController.restore) does no check. With only ACCESS_COARSE_LOCATION granted, startForeground succeeds (coarse satisfies the FGS-location requirement) and requestLocationUpdates succeeds (no SecurityException), but fused delivers ~2 km-accuracy fixes that are all dropped by the accuracy > MAX_ACCURACY_METERS (50 m) filter at line 168. The session resumes into a state where distance/duration/route never advance and only the generic 'GPS weak' banner shows, with no hint that precise location is required.

**情境:** Mid-walk, the user switches the app's location from 'Precise' to 'Approximate' in Settings (process killed, checkpoint kept). They reopen the app and tap Resume on the recovery card. The session screen appears and they keep walking, but distance and time stay frozen forever with only a vague 'GPS weak' banner; the workout records nothing.

### M27. [l10n-a11y] Six feature string files exist only in values/ with Chinese-only content — English users get a mixed-language app

`app/src/main/res/values/strings_traininghub.xml:10`

**問題:** values/ contains strings_traininghub.xml, strings_strength.xml, strings_onboarding.xml, strings_login.xml, strings_schedule.xml and strings_bpworkout.xml (about 90 keys total) whose values are all Traditional Chinese (e.g. hub_start_exercise = 開始運動, onboarding 你的主要目標是什麼?, login 使用 Google 登入, bpworkout_gate 血壓偏高,建議今天休息). There is no English version and no values-zh-rTW counterpart (values-zh-rTW/ only holds the main strings.xml). Since values/ is the default locale and the main strings.xml in values/ is English, every non-Chinese-locale device renders these screens in Chinese next to English chrome. Related: WorkoutSessionScreen.kt:344 also hardcodes "%d 次" for rep counts.

**情境:** A user with the phone set to English installs from Play. First-run onboarding (goal picker), the Google login screen, the entire Exercise hub start flow, the strength library, the weekly schedule and the pre-workout BP warning dialog all appear in Chinese while the rest of the app is English.

### M28. [l10n-a11y] Workout BP safety-gate warnings hardcoded in Chinese in domain layer

`app/src/main/java/com/silverbp/android/coach/CoachEngine.kt:391`

**問題:** CoachEngine.shouldAllowWorkout() builds WorkoutBpGate.Block("血壓過高(≥180/110),今天請先休息") and Caution reasons (lines 383, 391, 401) as raw Chinese literals. WorkoutBpGateDialog.kt:64 renders gate.reason verbatim (text = { gate.reason?.let { Text(it) } }). The same pattern exists in TodayExerciseTaskGenerator.kt:144 (BP_MEASURE_HINT = "先量血壓再開始"). This is the safety message that tells a hypertensive elderly user NOT to exercise today; an English-locale user cannot read it.

**情境:** An English-locale user with a 185/112 reading in the last 24h taps Start Exercise. The blocking dialog's explanation 血壓過高(≥180/110),今天請先休息 is Chinese; the user cannot understand why the app is warning them and may start the workout anyway via the proceed button.

### M29. [l10n-a11y] Device pairing screen and its status/error messages are fully hardcoded Chinese

`app/src/main/java/com/silverbp/android/ui/sync/PairingScreen.kt:97`

**問題:** PairingScreen.kt hardcodes every label as Chinese string literals: title 配對裝置 (line 97), 顯示 QR 配對碼 (183), 掃描其他裝置 QR (192), 授予權限 (255), SAS confirm buttons 號碼不同 — 取消 / 號碼相同 — 確認 (314/321), 完成 (393), 重試 (415). PairingViewModel.kt does the same for runtime status and errors: 正在啟動 LAN 廣播… (70), 配對失敗:... (102, 154), 無法儲存配對:... (173), 配對成功但同步失敗:... (268). None go through string resources, so locale has no effect. The screen is reachable from main navigation (AppNavHost.kt:290, Routes.SYNC_PAIRING).

**情境:** An English-locale user opens Settings > device sync pairing. The whole pairing flow, including the security-relevant short-auth-string confirmation buttons ('numbers differ — cancel' vs 'numbers match — confirm'), is in Chinese; the user may confirm the wrong button during pairing verification.

### M30. [l10n-a11y] Runtime error/status messages hardcoded in Chinese across ViewModels (capture OCR, chat, backup, report)

`app/src/main/java/com/silverbp/android/ui/capture/CaptureFlowViewModel.kt:100`

**問題:** User-visible error strings are Chinese literals in Kotlin, bypassing resources: CaptureFlowViewModel.kt:95-124 (e.g. 無網路連線,請確認連線後再試,或先手動輸入; 辨識信心度過低...; 無法載入照片) shown in the BP photo-capture flow; ChatViewModel.kt:234-271 (請先在「設定」輸入 Gemini API key, 網路連線失敗..., fallback (沒有產生內容)) shown via ChatScreen snackbar (ChatScreen.kt:145); BackupViewModel.kt:142-317 (無法開啟匯出目的地, 尚未連結 Google 帳號, 需重新連結 Google 帳號) surfaced as toasts in BackupScreen.kt:112; ReportViewModel.kt:77 (報告產生失敗...); ReportScreen.kt:71/104 (區間共 X 筆讀數, 大小 X KB). Same class of bug as the known CoachViewModel.kt:400/404 seed.

**情境:** An English-locale user photographs their BP monitor with no network. The capture screen shows the Chinese error 無網路連線,請確認連線後再試,或先手動輸入 instead of an English message, dead-ending a core flow for them.

### M31. [l10n-a11y] Camera shutter buttons have no accessibility semantics (unlabeled clickable Box, no Role.Button)

`app/src/main/java/com/silverbp/android/ui/capture/CaptureScreen.kt:252`

**問題:** ShutterButton in CaptureScreen.kt:252-272 is a plain Box with .clickable(onClick) — no contentDescription, no semantics, no Role.Button, and no visible text. The identical copy exists in MachineCaptureScreen.kt:262-280. TalkBack announces it as 'unlabeled, double-tap to activate'. This is the single primary action of the BP photo-capture flow, which is the headline feature for the elderly target audience. Google Play's pre-launch accessibility report flags unlabeled actionable controls.

**情境:** A low-vision elderly user running TalkBack opens the BP capture screen. Swiping through elements, the shutter is announced only as 'unlabeled', so they cannot tell it takes the photo; the same happens on the gym-machine OCR capture screen.

### M32. [nutrition] Barcode lookup logs per-100g nutriments as the whole meal and mixes per-serving with per-100g bases

`app/src/main/java/com/silverbp/android/nutrition/OpenFoodFactsClient.kt:66`

**問題:** Two basis errors: (1) When the OFF product has no *_serving fields (very common for EU/Asia entries), all values fall back to *_100g and are stored directly as the meal's calories/sodium/macros — i.e. the nutrition of exactly 100 g/ml is logged regardless of package or serving size. The serving_size field is requested in the URL (line 40) and modeled (line 122) but never used or shown, so the user has no way to know the numbers are per-100g. (2) useServing (line 64) is true if ANY of energyKcalServing/sodiumServing/saltServing exists, and pick() (line 66) then falls back per-field to the 100g value, so one log can sum per-serving calories with per-100g protein/sodium (line 70 chain even falls from sodium_serving to sodium_100g). In a blood-pressure app the resulting sodiumLevel classification (forMealMg) is computed from this wrong mg value, so the Low/Mid/High badge and the Coach diet rollup are wrong too. (The salt/2.5 and g→mg conversions themselves are correct.)

**情境:** User scans a 600 ml soda or a 120 g instant-noodle bowl whose OFF record only has per-100g data. The app logs ~1/6 (or ~1/1.2) of the real calories and sodium as the entire item, shows it on the confirm screen as if it were the meal, marks it 'Label' (trusted) source, and the day's sodium ring and Coach high-sodium detection are silently understated.

### M33. [nutrition] Deleting a food log never deletes the mirrored Health Connect NutritionRecord

`app/src/main/java/com/silverbp/android/nutrition/NutritionRepository.kt:68`

**問題:** NutritionRepository.delete() removes the Room row and recomputes the diet rollup but never calls the Health Connect bridge, and HealthConnectNutritionBridge has no delete method — grep confirms deleteRecords() is never called anywhere in the app even though the hcRecordId (and clientRecordId) needed for deletion is stored on the entity. The mirrored NutritionRecord therefore persists in Health Connect forever after in-app deletion.

**情境:** User with Health Connect sync enabled logs a meal (mirrored to HC), then realizes it was a duplicate or wrong and deletes it in SilverBP. Google Fit/Health and any other HC-connected app keep counting the deleted meal's calories and sodium; the user sees totals that disagree with SilverBP and cannot fix it except by manually deleting inside Health Connect.

### M34. [nutrition] Edits to a food log never propagate to Health Connect (clientRecordVersion always 0)

`app/src/main/java/com/silverbp/android/health/HealthConnectNutritionBridge.kt:70`

**問題:** write() always uses Metadata.manualEntry(clientRecordId = log.id) which leaves clientRecordVersion at its default of 0. Per the connect-client SDK docs (HealthConnectClient.insertRecords: 'whichever Record with the higher clientRecordVersion takes precedence'), re-inserting the same clientRecordId with an equal (0) version does not replace the existing record — the original values win. NutritionRepository.upsert (line 57-63) relies on this insert-as-upsert for edits (the code comment says 'edits upsert instead of duplicate'), and updateRecords() is never used despite hcRecordId being stored. The version should be bumped (e.g. to updatedAt millis) or updateRecords used. The BP bridge shares this flaw, but food logs are the records users actually edit via the confirm screen.

**情境:** User logs a meal (mirrored to HC as 800 kcal / 1500 mg sodium), then taps the entry, corrects it to 400 kcal / 600 mg and saves. SilverBP shows the corrected values but Health Connect / Google Fit keep showing 800 kcal and 1500 mg indefinitely.

### M35. [onboarding-nav] Tapping 'disconnect Google' in Backup settings throws the user back into the sign-in hard gate and wipes the whole back stack

`app/src/main/java/com/silverbp/android/ui/nav/AppNavHost.kt:99`

**問題:** The comment at AppNavHost.kt:95 claims 'once googleAccountEmail is set it becomes false and never re-fires', but that is false: BackupScreen exposes a disconnect dialog (BackupScreen.kt:410-423) whose confirm button calls BackupViewModel.disconnectGoogle() (BackupViewModel.kt:241-247), which calls settings.clearGoogleAccount(). The settings flow re-emits with a blank googleAccountEmail, needsGoogleSignIn recomputes to true (AppNavHost.kt:96-98), and LaunchedEffect(needsGoogleSignIn) at line 99-106 fires navigate(ONBOARDING_LINK){popUpTo(HOME){inclusive=true}}. The user is yanked out of the Backup screen mid-session, SETTINGS/BACKUP/HOME are all popped, and they are stuck on the mandatory sign-in screen again with no way back into the app except re-linking the account they just deliberately unlinked — making the advertised disconnect feature a trap.

**情境:** User opens Settings → 資料備份, taps 解除連結 and confirms in the dialog (e.g. they no longer want cloud backup, or want to switch accounts). The Backup screen instantly disappears and the full-screen 'link Google account' gate replaces the entire app. Pressing back exits the app; relaunching shows the gate again. The only way to use the app at all is to re-link a Google account.

### M36. [onboarding-nav] App-lock has no fallback when biometrics + device credential are gone — permanent lockout loop, reachable via backup-restore/KV-sync onto a device with no screen lock

`app/src/main/java/com/silverbp/android/ui/lock/LockScreen.kt:58`

**問題:** LockScreen.authenticate() (LockScreen.kt:58-84) unconditionally calls BiometricPrompt with BIOMETRIC_STRONG|DEVICE_CREDENTIAL and never checks canDeviceAuthenticate() (defined right below at line 119 but only used at enable-time in SettingsViewModel.kt:240). If the device has neither biometrics nor a PIN/pattern/password, prompt.authenticate() immediately fails with ERROR_NO_DEVICE_CREDENTIAL, the callback sets error=true (line 71), and the Unlock button just repeats the same failure — an unbreakable loop with no 'disable lock' escape, since Settings is behind the gate. This state is reachable: (1) user enables app-lock, later removes their screen lock in system settings; (2) more dangerously, appLockEnabled=true is restored to a NEW device via Auto Backup (SilverBpBackupAgent.kt:53-60 always backs up the DataStore file) or pushed via cross-device KV sync (UserSettingsRepository.kt:374 exports APP_LOCK_ENABLED, line 434 imports it) — if that device has no screen lock configured, the app is locked out on first launch with zero on-device recovery path (the user must figure out that adding a system screen lock fixes it, or wipe app data losing local readings).

**情境:** An elderly user enables app-lock, then their grandchild removes the phone's PIN to 'make it easier'. Next time the app cold-starts, LockManager.bind() bootstraps locked=true (LockManager.kt:62-64), the BiometricPrompt errors out instantly, and every tap of 解鎖 shows the error again. The app — and all their blood-pressure history — is inaccessible until they independently discover that re-adding a system PIN unlocks it.

### M37. [play-policy] READ_HEALTH_DATA_IN_BACKGROUND directly contradicts the privacy policy's 'nothing is collected in the background' claim

`docs/privacy.html:69`

**問題:** privacy.html line 69 states: 'Nothing is collected in the background without your action.' (zh version line 143 says the same). But the app declares android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND (AndroidManifest.xml:47) precisely so that WorkManager backfill jobs can read sleep/nutrition/steps from Health Connect while the app is NOT in the foreground (HealthConnectBridge.kt:42-44 documents this), and it is bundled into every HC permission request (SettingsScreen.kt:96, 458, 486; CoachLogSleepScreen.kt:164). Background HC reads have the strictest tier of Health Connect policy review — Google requires the developer to justify why background access is essential, and reviewers cross-check the privacy policy. A policy that explicitly denies background collection while the app requests the background-read permission is a near-certain declaration rejection or policy-violation strike. Fix: amend the policy to disclose the periodic background sync of HC sleep/nutrition/step data (it stays on-device, so the disclosure is easy), and prepare the background-read justification for the Health Apps Declaration.

**情境:** Play reviewer (or a user) opens the linked privacy policy from the store listing, sees 'nothing is collected in the background', then sees READ_HEALTH_DATA_IN_BACKGROUND in the permission list of the same policy is absent and the manifest requests it — declaration gets rejected for inconsistent disclosure.

### M38. [play-policy] Privacy policy never mentions Google Drive auto-backup; claims health data is 'never uploaded'

`docs/privacy.html:86`

**問題:** privacy.html line 86: 'SilverBP does not have a server. Your health data is never uploaded to us or to any third-party analytics or advertising service', and section 3 'Network use' (lines 89-95) lists model download, Maps, LAN sync, and Gemini cloud OCR — but NOT the Google Drive auto-backup feature, which uploads encrypted .sbpbk snapshots of the entire health database to the user's Drive appDataFolder on a schedule (backup/auto/AutoBackupWorker.kt, GoogleDriveBackupClient.kt upload at line 54). A grep for 'drive' in privacy.html returns zero hits. Health Connect policy requires the privacy policy to describe ALL transmission of health data off the device, even user-initiated and encrypted; the Data Safety form ('data shared/transferred') must also align. Also minor: line 73 says HC integration 'reads ... nutrition' but omits that the app also WRITES nutrition (WRITE_NUTRITION, manifest line 43). Add a Drive-backup section (what is uploaded, that it is end-to-end encrypted with the recovery code, how to delete it) and fix the HC write list.

**情境:** User enables auto-backup; gigabytes-of-trust moment aside, a Play reviewer comparing the Data Safety form / HC declaration against the policy finds undisclosed off-device transfer of health data and rejects the Health Apps Declaration or flags a Data-Safety misrepresentation violation.

### M39. [play-policy] Disconnecting Google leaves health-data backups stranded in Drive with no in-app or web deletion path

`app/src/main/java/com/silverbp/android/ui/backup/BackupViewModel.kt:241`

**問題:** disconnectGoogle() (lines 241-247) only cancels the scheduler and clears the locally stored email/permissionId — it never deletes the uploaded .sbpbk files nor revokes the OAuth grant. The code itself notes (line 261-263) that drive.appdata is invisible in the user's regular Drive UI: 'they can only see backups through us'. GoogleDriveBackupClient.deleteFile() (line 118) exists but is only used for retention pruning in AutoBackupWorker; no UI exposes it. Consequence: once a user unlinks, their encrypted health-database snapshots remain in Drive forever with no way to view or remove them except revoking the app in Google account settings. For Play's account/data-deletion policy: if the Data Safety form answers 'users can sign in with Google', Play requires an in-app deletion path AND a web deletion-request URL (docs/index.html is an 11-line stub with neither). Either (a) answer 'no account creation' in Data Safety (defensible since this is OAuth authorization for a backup feature, not an account) and still add 'delete cloud backups' to the disconnect flow, or (b) provide the full deletion path + URL.

**情境:** User links Google, runs auto-backups for months, then taps disconnect expecting their data to be gone. The encrypted health backups silently remain in Drive appDataFolder; the user cannot see or delete them from the Drive app, and SilverBP no longer offers any way to remove them.

### M40. [play-policy] FOREGROUND_SERVICE_LOCATION on targetSdk 36 needs a Play Console FGS declaration — absent from release checklist

`RELEASE.md:66`

**問題:** The manifest declares FOREGROUND_SERVICE_LOCATION (AndroidManifest.xml:27) with a foregroundServiceType="location" service (line 79), and targetSdk is 36 (build.gradle.kts:39). Since targetSdk 34, Play Console requires a per-type Foreground Service declaration (App content -> 'Foreground service permissions'), including a description of the user-facing feature and a video demonstrating it, before a release using the permission can roll out. RELEASE.md's Play Console section (lines 63-68) does not mention it. The actual usage is compliant (user-initiated exercise tracking, ExerciseController.kt:22 starts the service only from the Start flow after the PermissionGate grants fine location), so approval is likely — but the declaration must be filed or the release is blocked. Same section should also note the prominent-disclosure-aligned Data Safety entries for Location, Microphone (RECORD_AUDIO, ChatScreen.kt:289), and ACTIVITY_RECOGNITION (PermissionGate.kt:99).

**情境:** Developer uploads the AAB; Play Console shows 'You must complete the foreground service permissions declaration for FOREGROUND_SERVICE_LOCATION' and refuses to let the release go to review until the declaration plus demo video is submitted.

### M41. [strength-machine-ocr] In-progress strength workout is silently wiped or orphaned: no resume path, no back guard, no persistence

`app/src/main/java/com/silverbp/android/ui/exercise/StrengthLibrarySection.kt:37`

**問題:** StrengthWorkoutLiveStore is in-memory only and start() unconditionally replaces _flow.value (StrengthWorkoutLiveStore.kt:60-70). WorkoutSessionScreen has no BackHandler and the STRENGTH_SESSION route (AppNavHost.kt:203) offers no onClose; the only way back into a Running workout is startStrength(), which calls start() and erases all logged sets. Unlike cardio (SessionCheckpointStore + recoverable banner in ExerciseHomeViewModel.checkRecoverable), strength has no checkpoint, so process death also loses everything. The same orphaning applies to the Finished state if the user back-presses out of WorkoutSummaryScreen without Save/Discard.

**情境:** User starts a squat workout, logs 4 sets over 20 minutes, accidentally swipes back (or takes a call and Android kills the process). There is no banner or button anywhere to return to the workout; tapping 開始訓練 on the exercise again starts a fresh workout and all 4 logged sets are gone with no warning.

### M42. [strength-machine-ocr] BP reading taken BEFORE the workout gets linked as the 'post-workout' BP

`app/src/main/java/com/silverbp/android/ui/strength/WorkoutSummaryViewModel.kt:70`

**問題:** findRecentPostBpId() searches bpRepo.observeRange(now-30min, now) and takes the latest reading, with no lower clamp at the workout's startedAt. The pre-workout BP gate flow explicitly nudges users to measure BP right before starting; for any workout shorter than 30 minutes that pre-workout reading falls inside the window and linkRecentPostBp() stores it with contextType="post". hasRecentPostBp also flips the summary's 已連結 affordance to true based on the same stale reading.

**情境:** User measures BP (gate dialog suggests it), does a 15-minute strength session, finishes and saves. The summary shows 'post-workout BP linked' and the association table records the pre-exercise reading as the post-exercise one — corrupting the BP-vs-exercise analysis this app is built around.

### M43. [strength-machine-ocr] Machine capture failure path calls navigation from a background camera-executor thread (crash) and consumes stale drafts

`app/src/main/java/com/silverbp/android/ui/exercise/machine/MachineCaptureScreen.kt:224`

**問題:** capturePhoto() runs its callback on Executors.newSingleThreadExecutor() (lines 309-323). When the bitmap is null (ImageCaptureException or decodeFileWithExif failure) the callback invokes onAnalyzed() directly, which is rootNav.navigate(...) — NavController/LifecycleRegistry enforce the main thread, so this throws IllegalStateException and crashes. Additionally this path navigates without staging a draft, so MachineConfirmViewModel.init() will take() whatever stale RecognizedMachineWorkout was left in MachineWorkoutDraftHolder by an earlier failed analysis (the catch block in MachineCaptureViewModel.kt:70 stages a draft even when the user then taps Retry/Cancel and never consumes it). A new executor is also leaked per shutter tap.

**情境:** User taps the shutter while storage is full or the camera is wrenched away by another app; ImageCapture errors, onResult(null) runs on the background executor, navigate() is called off the main thread and the app crashes mid-capture.

### M44. [strength-machine-ocr] Console photo is saved to disk but never persisted or shown — orphaned JPEGs accumulate forever

`app/src/main/java/com/silverbp/android/ui/exercise/machine/MachineCaptureViewModel.kt:49`

**問題:** analyzeBitmap() writes a full JPEG into filesDir/photos via writePhoto() and threads photoFilename through RecognizedMachineWorkout into MachineConfirmUiState, but MachineConfirmScreen never renders it and toSession() (MachineConfirmViewModel.kt:127-144) has no photo field — ExerciseSession/exercise_session v17 have no photo column. Unlike the nutrition flow it mirrors (food_log.photoFilename + confirm-screen preview), every machine capture leaks an unreferenced ~1-3 MB JPEG that nothing displays, deletes, or can ever find again. KDoc on MachineWorkoutDraftHolder even promises the manual form 'keeps the photo'.

**情境:** User logs a treadmill workout by photo a few times a week. App storage grows by megabytes per capture indefinitely (visible in Settings > Storage), and the user can never view the console photo they took — neither on the confirm screen nor on the saved session.

### M45. [strength-machine-ocr] Bare-minutes console time ('32') is parsed as 32 seconds, making duration and pace 60x off

`app/src/main/java/com/silverbp/android/ui/exercise/machine/MachineConfirmViewModel.kt:172`

**問題:** parseClockToSeconds() returns parts[0] for a single-segment time with the comment '// bare minutes', but the caller (buildInitial, lines 91-92) treats the return value as total seconds — so OCR'd time_text "32" prefills 0 min 32 s instead of 32 min 0 s. It should return parts[0] * 60. If saved unnoticed, activeDurationMillis, startedAt (now - duration) and averagePaceSecPerKm are all wrong by a factor of 60.

**情境:** A bike console shows TIME as a whole-minute counter '32'. The confirm form opens prefilled with 0 minutes 32 seconds; an elderly user trusts the prefill and saves a 32-second 'workout' with an absurd pace, polluting duration totals and the pace chart.

### M46. [sync] Deletes never propagate over LAN sync and deleted records resurrect on the deleting device

`app/src/main/java/com/silverbp/android/sync/RoomSyncAdapters.kt:136`

**問題:** CombinedRoomSyncSource.recordsSince (lines 136-294) — the source used for every LAN sync round — never queries syncDao for tombstones; only the backup-only snapshotAll path emits them (line 430). Worse, local delete paths never create tombstones in the first place: BpRepository.delete (BpRepository.kt:55) is just dao.delete(id), ExerciseRepository.delete (ExerciseRepository.kt:77), MedicationManageScreen.kt:181, NutritionConfirmViewModel.kt:73 likewise. Tombstones are only ever written when APPLYING an inbound tombstone record (e.g., BpReadingSyncMapper.kt:98), and no code path ever produces an outbound one. Combined with the full-table re-send each round, the peer ships its live copy back and the sink re-inserts it.

**情境:** Grandma deletes an embarrassing or erroneous BP reading on her phone, then syncs with the family tablet (the tablet still has the row). The 'deleted' reading reappears on her phone after the sync round. There is no way to permanently delete a record across paired devices — deletion silently undoes itself.

### M47. [sync] SAS rejection on one device leaves the other device hung forever in 'Syncing' with a persisted pairing to a possibly-MITM'd peer

`app/src/main/java/com/silverbp/android/ui/sync/PairingViewModel.kt:166`

**問題:** onConfirmSas(matched=false) just sets state back to Picker (lines 166-169) — it neither closes the open socket/Noise channel nor signals the peer. Meanwhile the peer that tapped '號碼相同 — 確認' has already executed confirmAndPersist (line 171, writing the peer's static key into the keystore) and entered State.Syncing, where SyncSession.run blocks in StreamFrameChannel.receive → DataInputStream.readInt (FrameChannel.kt:57-58) on a socket with no SO_TIMEOUT and no session-level timeout anywhere in the stack. The spinner runs indefinitely; the sync job is also not tracked in activeJob so cancelActive() can't stop it. Net result: an SAS mismatch (the explicit MITM-detection mechanism, per the on-screen warning at PairingScreen.kt:300) aborts on only ONE side, while the other side keeps a trusted root-key row for the unconfirmed peer and hangs.

**情境:** During pairing the two 6-digit codes differ (or one user simply taps cancel). Device A's user taps 'matches' first: A persists the pairing, shows '正在同步資料…' with a spinner forever, and never times out. The user's only escape is the back button; the bogus paired-device key remains stored with no UI to view or remove it.

### M48. [sync] QR joiner connects to the first discovered SilverBP device on the LAN, not the device whose QR was scanned

`app/src/main/java/com/silverbp/android/ui/sync/PairingViewModel.kt:132`

**問題:** onScanned parses the QR (which carries deviceId and bonjourServiceName) but then takes `nsd.waitForPeer()` — the first peer the resolver happens to deliver — without comparing peer.deviceId or peer.pubKeyFingerprint against the scanned payload (both fields are available on NsdDiscovery.Peer, NsdDiscovery.kt:33-39, and a fingerprint is even advertised in TXT records at NsdDiscovery.kt:69). If another SilverBP device on the same Wi-Fi is advertising (e.g., a third family device left on the Show-QR screen), the joiner TCP-connects to the wrong host; the Noise XK handshake then fails against the pinned static key from the QR, surfacing only '配對失敗: …'. Because seenPeerNames/resolve order is deterministic per attempt, retrying tends to hit the same wrong peer, making pairing impossible while the interfering device advertises.

**情境:** A family with two parents' phones and a tablet: both phones open '顯示 QR 配對碼' while the tablet scans one of them. The tablet's browser resolves the other phone first, connects to it, and the handshake fails with a cryptic pairing error. Retries keep failing until the unrelated phone leaves the pairing screen.

## 🟡 不完整功能 (Incomplete)

### I1. [bp-capture] Permanently-denied camera permission: Retry button silently does nothing, no link to app settings

`app/src/main/java/com/silverbp/android/ui/capture/CaptureScreen.kt:148`

**問題:** On Android 11+ a second denial of CAMERA is permanent: `permLauncher.launch(...)` then returns `granted = false` immediately with no system dialog shown. The denied-state UI offers only this Retry button — there is no `shouldShowRequestPermissionRationale` check and no intent to ACTION_APPLICATION_DETAILS_SETTINGS, so tapping Retry visibly does nothing forever. The auto-launch in LaunchedEffect (line 91) plus a denial already burns one of the two prompts, so a single user-facing "deny" is enough to reach this dead end.

**情境:** User denies the camera prompt once (auto-shown on entry), taps Retry and denies again; from then on every visit to the capture screen shows a Retry button that flashes nothing and never enables the camera, with no hint to enable it in Settings.

### I2. [chat-llm] Fresh install: chat is a dead end — 'Model not loaded' banner with no way to download the model from chat

`app/src/main/java/com/silverbp/android/ui/chat/ChatScreen.kt:257`

**問題:** Default backend is Local (UserSettingsRepository.kt:102) and ModelBootstrap.start does nothing when the model file is absent (ModelBootstrap.kt:49-54 — phase stays Idle). Chat then renders ModelLoadBanner(Idle) which is just the static text 'Model not loaded' / '尚未載入模型' (ModelLoadBanner.kt:30) and ChatUiState.canSend returns false for Local when phase != Ready (ChatViewModel.kt:57), so the send button is permanently disabled. The only download trigger in the entire app is buried in Settings → Advanced (AdvancedSettingsScreen.kt:174); the banner offers no button, link, or instruction.

**情境:** New user (or Play reviewer) installs the app, taps the floating assistant FAB, types a question — the send button is greyed out and the only feedback is '尚未載入模型'. Nothing tells them they must find Settings → 進階 → 下載並載入模型 and pull 2 GB first. The flagship AI feature appears broken out of the box.

### I3. [coach-workers] Weekly Progress card is a permanent dead placeholder with hardcoded English text

`app/src/main/java/com/silverbp/android/ui/coach/CoachViewModel.kt:400`

**問題:** buildReadyState always emits WeeklyProgressUi(placeholderText = "Charts will appear once 7 days of data are available.", hasData = false) — both values are hardcoded, so the promised chart never appears no matter how much data exists, and WeeklyProgressCard renders the raw English string verbatim (WeeklyProgressCard.kt:31-35), not a string resource, so zh-TW users (the app's primary locale, per locales_config.xml) see untranslated English. The sibling narration placeholder at line 404 ("Once data is collected, your coach will explain your plan here.") is dead code — it is always overwritten by ready.copy(narration = narration) at line 217.

**情境:** A user logs BP, exercise, sleep and diet daily for a month. The Coach screen's '每週進度' card still says, in English, 'Charts will appear once 7 days of data are available.' forever — the feature visibly dead-ends and looks broken in a zh-TW UI.

### I4. [nutrition] Health Connect nutrition retry/backfill is dead code — failed mirrors are never retried

`app/src/main/java/com/silverbp/android/core/db/NutritionDao.kt:43`

**問題:** FoodLogDao.findUnmirrored() is documented as 'the retry/backfill set' but has no callers: BpSyncWorker only backfills BP readings (bpDao().findUnmirrored()), and no nutrition equivalent exists. So when the inline mirror in NutritionRepository.upsert fails (HC temporarily unavailable, WRITE_NUTRITION granted after the meal was logged, transient error) the row stays hcRecordId == null forever and is silently never mirrored. FoodLogSyncMapper.apply (FoodLogSyncMapper.kt:139) also resets hcRecordId = null on synced-in rows, explicitly expecting a backfill pass that does not exist.

**情境:** User logs a week of meals, then enables Health Connect (or grants the nutrition permission after initially declining). BP readings get backfilled into HC by BpSyncWorker, but none of the existing meals ever appear there — only meals logged after the toggle, with no error or pending indicator.

### I5. [onboarding-nav] Notification deep links navigate on top of the onboarding/privacy-consent and Google sign-in gates, bypassing them; coach reminders are scheduled before consent is ever given

`app/src/main/java/com/silverbp/android/ui/nav/AppNavHost.kt:111`

**問題:** The DeepLinkBus collector at AppNavHost.kt:111-127 navigates to COACH_WEEKLY_REPORT / COACH_LOG_DIET / COACH_LOG_SLEEP / COACH_LOG_MEDICATION / EXERCISE_* without checking needsOnboarding or needsGoogleSignIn. When either gate is active, HOME has been popped inclusive (lines 85, 102), so popBackStack(Routes.HOME, inclusive=false) at line 122 silently no-ops and navigate(route) pushes the destination directly on top of the gate screen — the user lands in a data-entry screen (diet/sleep/medication logging writes to the DB) without having accepted the privacy policy or passed the mandatory sign-in. The pre-conditions are real: SilverBpApplication.reconcileCoach() (SilverBpApplication.kt:58-66) schedules CoachReminderScheduler/MedicationReminderScheduler on every cold start whenever enableCoach is true — and enableCoach defaults to true with no didOnboard check — so daily coach reminder notifications get scheduled the moment a fresh install first launches, before the consent checkbox is ever shown. acceptedPolicyVersion is only written in finish() at the very last onboarding step (OnboardingNicknameScreen.kt:123), so a user who granted notification permission at step 1 and then abandoned onboarding (or any user on Android ≤12, where no permission is needed) will receive these notifications while still un-consented.

**情境:** Fresh install on Android 12: user opens the app, sees the welcome/consent step, and closes it without ticking the consent box. Next morning a '今日健康提醒' coach notification appears (scheduled at first app start). Tapping it opens the app and pushes the diet-log screen on top of the onboarding screen — the user can browse and save health data without ever accepting the privacy policy; pressing back drops them back onto the consent screen, revealing the gate was bypassed. The same mechanism overlays coach screens on the mandatory Google sign-in gate.

### I6. [play-policy] HC permissions rationale screen omits sleep/nutrition/route/background permissions and is Chinese-only

`app/src/main/java/com/silverbp/android/health/PermissionsRationaleActivity.kt:79`

**問題:** The rationale screen linked from the Health Connect permission sheet (ACTION_SHOW_PERMISSIONS_RATIONALE) only explains two things: read steps (line 79) and write exercise/blood-pressure (line 87). It says nothing about READ_SLEEP, READ_NUTRITION, WRITE_NUTRITION, WRITE_EXERCISE_ROUTE, or READ_HEALTH_DATA_IN_BACKGROUND — all of which the app requests (manifest lines 38-47, requested in SettingsScreen SleepTrackingRow/DietTrackingRow). Health Connect policy requires the rationale to cover each requested data type; reviewers open this exact screen during the Health Apps Declaration review. Additionally all strings are hardcoded Traditional Chinese ('資料使用說明' etc.) even though the app ships an 'en' locale (build.gradle.kts localeFilters en + zh-rTW), so an English-locale reviewer/user gets an untranslated rationale. Also the statement at line 97 '不會上傳至我們的伺服器' (never uploaded) conflicts with the Drive backup feature, same issue as the privacy policy.

**情境:** User taps the privacy/rationale link inside the system Health Connect grant sheet when enabling Sleep tracking — the screen that appears explains only steps and BP writes, in Chinese regardless of device language, and never mentions sleep data at all; a Play HC reviewer doing the same marks the rationale as insufficient.

### I7. [strength-machine-ocr] OCR-captured calories, heart rate and floors are stored but invisible everywhere; floors/steps sessions show '0 m' distance

`app/src/main/java/com/silverbp/android/ui/exercise/ExerciseDetailScreen.kt:118`

**問題:** ExerciseDetailScreen's StatsCard shows only distance/duration/pace/steps. No UI in the app reads session.caloriesKcal, heartRateBpm, floors, distanceUnitRaw or rawMetricsJson (grep confirms the only writer is MachineConfirmViewModel). For a stair-climber session logged in floors, distanceMeters stays 0.0 (MachineConfirmViewModel.kt:118) so the detail screen renders distance as '0 m' while the actual floors count is hidden in an unread column.

**情境:** User photographs a stair-climber console showing 45 floors, 250 kcal, HR 110, confirms and saves. Opening the session from history shows distance '0 m', no calories, no heart rate, no floors — everything the OCR feature captured has vanished from view.

### I8. [strength-machine-ocr] BP-gate dialog's primary '去量血壓' button is a dead-end — it only dismisses the dialog

`app/src/main/java/com/silverbp/android/ui/exercise/StrengthLibrarySection.kt:73`

**問題:** WorkoutBpGateDialog renders onMeasure as the prominent filled Button (WorkoutBpGateDialog.kt:69-71, '[onMeasure] lets the user go measure BP first'), but the strength caller passes onMeasure = { pendingStrength = null } — a pure dismiss with no navigation to the BP capture flow (the cardio caller at ExerciseHomeScreen.kt:270 has the same no-op). For a Block verdict this is the safety-critical action and it does nothing.

**情境:** User with a high reading taps 開始訓練, the gate dialog warns and offers 去量血壓 as the main button. Tapping it just closes the dialog and leaves them on the exercise detail page; nothing guides them to measure, so they most likely tap 'start anyway' next time.

### I9. [sync] Paired devices never sync again after the one pairing-time round — ongoing sync is dead code

`sync/src/main/java/com/silverbp/android/sync/pairing/PairingService.kt:180`

**問題:** The only sync that ever runs is runInitialSyncRound during the pairing ceremony, and it hardwires getLocalLastHlcSeen = { Hlc.ZERO } and updateLocalLastHlcSeen = { } (PairingService.kt:180-181), so no watermark is ever persisted. SyncCoordinator.runSessionAsInitiator/runSessionAsResponder (SyncCoordinator.kt:62/97) have zero callers in app code; the root key persisted by confirmAndPersist (keyStore.storeRootKey, line 154) is never read back for any later session; there is no background NSD listener, no WorkManager job, and no 'sync now' button anywhere in the UI. The Picker screen promises '配對另一台裝置以同步血壓資料' but new readings taken after pairing never reach the peer.

**情境:** User pairs phone and tablet, sees data appear, and assumes the devices stay in sync. The next day's BP readings never show up on the tablet. The only way to sync new data is to physically redo the whole QR-scan + SAS ceremony, which re-runs a full-table dump each time (and triggers the LWW-less overwrite bug).

## ⚪ 建議改善 (Polish)

### P1. [backup] Auto-backup work has no network constraint — guaranteed failures offline and uploads over metered data

`app/src/main/java/com/silverbp/android/backup/auto/AutoBackupScheduler.kt:36`

**問題:** The periodic and one-shot work requests (lines 36-38, 52-54) set no Constraints at all — not even NetworkType.CONNECTED — so WorkManager happily runs the worker in airplane mode, where the Drive token request / upload throws, retries 4 times with backoff, and writes a raw IOException message into the user-facing status row. It also uploads on cellular data without any unmetered preference. The code comment documents this as a product decision, but a CONNECTED constraint would cost nothing (WorkManager would just defer the run) and would replace scary 'Unable to resolve host' status text with a clean deferred backup.

**情境:** Elderly user's phone sits on airplane mode overnight when the daily backup fires. Next time they open the Backup screen the status row shows 備份失敗 with a raw English socket error string, even though nothing is actually wrong with their setup.

### P2. [backup] Backup header schemaVersion hardcoded to 16 while Room DB is v17, despite lock-step comment

`app/src/main/java/com/silverbp/android/di/ServiceLocator.kt:451`

**問題:** BackupManager is constructed with schemaVersion = 16 and the comment says 'keep in lock-step with SilverBpDatabase / 升 schema 時改這裡', but SilverBpDatabase.kt line 40 is now version = 17 (gym OCR / exercise hub migration). Every backup written today stamps the wrong schema version into both the manifest and the AEAD-authenticated header. Today import() never inspects header.schemaVersion so nothing breaks yet, but the field exists precisely for the promised future '匯入時做相容處理' and for the iOS counterpart — any version-gated compat logic added later (on either platform) will misclassify all v17-era backups as v16.

**情境:** A future app version (or the iOS app) adds the planned schema-compat check on import and treats schemaVersion 16 files as pre-v17, applying an unnecessary or wrong upgrade transform to backups that actually contain v17 data.

### P3. [bp-capture] No way to cancel recognition; full-screen spinner can block the camera for up to 60 seconds

`app/src/main/java/com/silverbp/android/ui/capture/CaptureScreen.kt:156`

**問題:** While phase is Recognizing, `showCameraControls` (line 124) hides the top bar (Cancel/manual entry) and the overlay offers no cancel affordance. GeminiCloudRecognizer's OkHttp client has a 60s read timeout (GeminiCloudRecognizer.kt:40), and local Gemma inference can take even longer on weak hardware. The only escape is the system back gesture, which abandons the whole capture (the photo is discarded because CaptureSessionHolder.put happens only after extract returns).

**情境:** On a flaky connection the Gemini call hangs; the user stares at an unlabeled spinner for up to a minute with no Cancel or 'enter manually instead' option, and backing out throws away the photo they just took.

### P4. [bp-capture] Pulse is never validated — values like 999 bpm save successfully

`app/src/main/java/com/silverbp/android/ui/confirm/BpReadingDraft.kt:32`

**問題:** `isValid` checks systolic/diastolic ranges and dia < sys, but not pulse. The pulse NumberField (ConfirmReadingScreen.kt:168-173) accepts any 3-digit value, and OCR output `r.pulse` (CaptureFlowViewModel.kt:72) is passed through unchecked, so a misread or typo of 999 or 1 bpm is stored and charted (and mirrored to Health Connect).

**情境:** OCR misreads the monitor's pulse row (or the user fat-fingers an extra digit) producing pulse = 888; Save is enabled, the value persists, and pulse trends/insights are skewed by a physiologically impossible reading.

### P5. [bp-capture] A new single-thread executor is created per shutter press and never shut down

`app/src/main/java/com/silverbp/android/ui/capture/CaptureScreen.kt:300`

**問題:** `capturePhoto` calls `Executors.newSingleThreadExecutor()` for every takePicture invocation and never calls shutdown(). Single-thread executors keep their core thread alive indefinitely, so each photo leaks one non-daemon thread (plus its stack) for the life of the process.

**情境:** A user who retakes a blurry monitor photo many times in one session accumulates idle threads; on low-RAM devices this contributes to memory pressure and eventual sluggishness, though no immediate crash.

### P6. [chat-llm] No free-space preflight before 2-4.4 GB model download; ENOSPC surfaces as raw English exception in zh-TW UI

`app/src/main/java/com/silverbp/android/recognition/ModelDownloader.kt:50`

**問題:** download() starts writing the multi-GB .part file without checking StatFs/available space against variant.approxSizeBytes. When the disk fills, the IOException message (e.g. 'write failed: ENOSPC (No space left on device)') is stored verbatim into ModelLoadPhase.Failed (ModelBootstrap.kt:125) and shown raw inside the localized banner string '模型載入失敗:%1$s' (ModelLoadBanner.kt:43). The giant partial file remains on disk, further squeezing the already-full device, with no cleanup affordance.

**情境:** User with 1.5 GB free starts the 2 GB Gemma download. It fails near the end after minutes of waiting; the banner shows an untranslated Unix error string, and the ~1.5 GB .part keeps the device full until the user figures out to clear app data.

### P7. [chat-llm] Error and diagnostic messages are persisted as assistant turns and replayed into the model's context

`app/src/main/java/com/silverbp/android/ui/chat/ChatViewModel.kt:273`

**問題:** Failure paths write UI error strings into the assistant placeholder row via repo.updateAssistantText: '回應失敗: ...' (line 273), '請先在「設定」輸入 Gemini API key' / '模型尚未就緒' (line 237), and '(沒有產生內容)' (line 262). These rows are normal role=assistant messages, so ChatTranscriptBuilder includes them in the history of every subsequent turn — the model sees 'Assistant: 回應失敗: 網路連線失敗...' as something it previously said, which degrades replies (apologies for errors it never made, echoing the error text). They are also permanently visible as assistant bubbles in the conversation history with no retry affordance.

**情境:** Cloud user sends a message while offline, gets the network-error bubble, reconnects and continues chatting. From then on every prompt sent to Gemini contains the fake assistant turn '網路連線失敗,請確認連線後再試', and the model occasionally references its earlier 'connection problem' in answers.

### P8. [coach-workers] Coach module ring cards display hardcoded English labels 'Exercise'/'Diet'/'Sleep', bypassing existing localized strings

`app/src/main/java/com/silverbp/android/ui/coach/CoachViewModel.kt:349`

**問題:** buildReadyState constructs ModuleRowUi with displayName = "Exercise" (line 349), "Diet" (line 355), "Sleep" (line 361) as raw literals. ModuleCard only falls back to the localized stringResource(row.moduleKey.labelRes()) when displayName is blank (ModuleCard.kt:28), and localized resources (coach_module_exercise etc.) already exist for both en and zh-rTW. Passing empty strings (as the medication rows effectively do by using the med name) would fix it.

**情境:** A Traditional-Chinese user (the app's default audience — every other Coach string is zh-TW) opens the Coach tab and sees the three lifestyle ring cards titled in English: 'Exercise', 'Diet', 'Sleep', mixed with Chinese progress text underneath. Visible on the main screen of the feature in every session.

### P9. [coach-workers] CoachEngine persists hardcoded Traditional-Chinese task titles, so English-locale users get Chinese plan and notification text

`app/src/main/java/com/silverbp/android/coach/CoachEngine.kt:259`

**問題:** buildTasks writes user-facing titles as Kotlin literals — "今日休息,聯絡醫師" / "散步 N 分鐘" (line 259), "鈉攝取 < N 毫克" (271), "睡眠 N 小時" (281), "依時服藥" (291) — into the coach_task table. The app declares English support in locales_config.xml and ships values/ (en) resources, and these titles surface in the weekly-plan screen, the today card, and the DailyReminderWorker notification body (DailyReminderWorker.kt:54-56). Because they are persisted at generation time, even a later locale switch keeps showing the generation-time language. The class KDoc claims the safety-gate reasons are 'the only Chinese the engine emits', which buildTasks contradicts.

**情境:** A user running the phone in English (a locale the Play listing claims to support) opens the Coach tab: today's task card and the entire weekly plan grid are in Chinese ('散步 21 分鐘', '依時服藥'), and the daily reminder notification body is Chinese too. Shippable for the zh-TW launch audience but guaranteed 1-star feedback from English users.

### P10. [db-migrations] Exported schema 14.json is missing — v13->v14 strength-training migration can never be covered by MigrationTestHelper

`app/schemas/com.silverbp.android.core.db.SilverBpDatabase/13.json:1`

**問題:** The schemas directory contains 1.json-13.json and 15.json-17.json but no 14.json (verified by directory listing), matching the project note that MIGRATION_13_14 was 'only unit-tested, never device-tested'. Room's MigrationTestHelper requires the exported schema of the target version to validate a migration, so neither 13->14 nor 14->15 can ever be instrumentation-tested while the file is absent, and any future hotfix touching that range has no regression net. Mitigating factor (verified): I executed the full v1->v17 migration SQL chain from SilverBpDatabase.kt against a real SQLite database seeded from 1.json and diffed the result against 17.json — columns, types, NOT NULL constraints, primary keys, indexes, and foreign keys all match exactly, so current upgrades will not trip Room's 'Migration didn't properly handle' check. Fix: recover 14.json from git history (the commit that bumped version to 14) or regenerate it by checking out that revision and building.

**情境:** No direct user impact today (the live migration chain validates clean against 17.json), but a developer adding a migration test suite or modifying MIGRATION_13_14 has no schema to test against, so a future regression in the most complex CREATE-TABLE migration would ship unvalidated to every user upgrading from the strength-training release.

### P11. [l10n-a11y] Actionable IconButtons with contentDescription = null (back, refresh, overflow menu, expand/collapse)

`app/src/main/java/com/silverbp/android/ui/achievements/MedalsScreen.kt:59`

**問題:** Four icon-only buttons pass contentDescription = null inside an IconButton, making them unlabeled to TalkBack: MedalsScreen.kt:59 (top-bar back arrow), CoachWeeklyReportScreen.kt:55 (regenerate-report refresh), CoachWeeklyPlanScreen.kt:201 (MoreVert overflow menu), NarrationBlock.kt:51 (expand/collapse coach narration). null is correct only for decorative icons; these are the sole affordance of their action. Most other IconButtons in the app are correctly labeled (e.g. CaptureScreen.kt:243), so these four are regressions/omissions.

**情境:** A TalkBack user on the Medals screen focuses the top-left button and hears only 'unlabeled button'; on the weekly plan screen the overflow menu that holds plan actions is likewise announced without any name.

### P12. [l10n-a11y] Heatmap row labels clipped: fixed 44.dp width + maxLines=1 cannot fit 'Morning'/'Evening' or large-font Chinese

`app/src/main/java/com/silverbp/android/ui/insights/charts/Charts.kt:317`

**問題:** DaypartCategoryHeatmap gives row labels Modifier.width(44.dp) (rowLabelWidth, line 292) with maxLines = 1 (line 320) and default TextOverflow.Clip, style bodyMedium (~14sp). The English strings part_morning='Morning'/part_evening='Evening' need roughly 55-60dp at fontScale 1.0, so they clip mid-glyph even at default settings; at elderly-typical fontScale 1.5-2.0 the Chinese 早上/晚上 also clips. Column headers at line 304 (categoryShortLabel, maxLines=1) share the issue at large scales.

**情境:** An English-locale user opens Insights and scrolls to the daypart/category heatmap: the row labels render as 'Morni'/'Eveni' cut mid-letter. An elderly zh-TW user at 1.7x font scale sees the second character half-clipped.

### P13. [l10n-a11y] No plurals resources: count strings always say '1 readings', '1 records'

`app/src/main/res/values/strings.xml:611`

**問題:** There are zero <plurals> resources in the project. English count strings are plain format strings, e.g. history_readings_count='%1$d readings' (line 611), today_readings_logged='%1$d readings logged' (line 692), backup_phase_success='Done — %1$d records, %2$s.' (line 649), exercise_notification_idle_body='Paused for %1$d minutes…' (line 180). With count 1 the English UI shows '1 readings' / '1 records'. Chinese is unaffected (no plural forms), but the default/English locale shows broken grammar on the History chips and Today card.

**情境:** An English-locale user logs their first BP reading of the day; the History day card chip reads '1 readings' and the Today screen shows '1 readings logged'.

### P14. [l10n-a11y] Saving a diet log shows a blank snackbar (showSnackbar(message = ""))

`app/src/main/java/com/silverbp/android/ui/coach/CoachLogDietScreen.kt:134`

**問題:** After upserting the DietCheckEntity, the save handler calls snackbar.showSnackbar(message = "") and then onClose(). An empty-string snackbar renders as a bare dark bar with no text (a placeholder where a 'saved' confirmation string was evidently intended), flashing briefly as the screen closes. Also on this screen the −/+ vegetable-serving steppers (lines 110/118) are TextButtons whose only content is '−'/'+', which TalkBack reads as bare symbols without context.

**情境:** A user logs today's diet in the Coach flow and taps Save: an empty grey snackbar bar flashes at the bottom with no message before the screen closes, looking like a rendering glitch.

### P15. [l10n-a11y] Dates forced to Taiwan/Chinese format regardless of device locale

`app/src/main/java/com/silverbp/android/ui/history/HistoryScreen.kt:187`

**問題:** HistoryScreen.kt:187 builds day headers with DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.TAIWAN) — the pattern itself contains Chinese characters and EEEE resolves to 星期三 etc. NutritionScreen.kt:297 uses dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.TAIWAN) for chart axis labels. Other screens (TodayScreen.kt:182, ConfirmReadingScreen.kt:318, ExerciseDetailScreen.kt:94, RecentSessionsCard.kt:74) hardcode Locale.TAIWAN with numeric patterns, which renders OK but ignores locale conventions. The History tab is a primary navigation tab.

**情境:** An English-locale user opens the History tab. Every day-group card is titled like 6月11日 星期三 instead of 'June 11, Wednesday', making the core reading log hard to navigate for non-Chinese readers.

### P16. [nutrition] Analyzing/Error overlay does not block touches — buttons underneath remain tappable during photo analysis

`app/src/main/java/com/silverbp/android/ui/nutrition/NutritionScreen.kt:213`

**問題:** CaptureOverlay is a full-screen Box with only a background color and no clickable/pointerInput modifier, so in Compose taps pass through to the sibling content beneath (the camera/barcode/gallery/manual buttons and meal rows). During the multi-second on-device model analysis the user can re-launch the camera, open the barcode scanner, or navigate to edit a meal while NutritionCapturePhase.Analyzing is showing; on the Error overlay, taps outside its two buttons also fall through.

**情境:** User photographs a meal; while the '分析中' spinner overlay is up (several seconds on the local Gemma backend) they tap the screen where the 拍照 button sits — the system camera launches again on top of the in-flight analysis, and the eventual NutritionDraftHolder hand-off races with the second capture, producing a confusing double-confirm flow.

### P17. [nutrition] Network/server errors during barcode lookup are reported as 'product not found'

`app/src/main/java/com/silverbp/android/ui/nutrition/BarcodeScanScreen.kt:198`

**問題:** The overlay branch handles BarcodePhase.NotFound and BarcodePhase.Error identically and always shows R.string.nutrition_barcode_not_found. OpenFoodFactsClient returns Error for IOException (offline), non-2xx responses, and JSON failures (and OFF's HTTP 404 for unknown products is also mapped to Error via !isSuccessful at OpenFoodFactsClient.kt:54, making the distinct NotFound/Error states largely meaningless). The user is told the product isn't in the database when the real problem is connectivity.

**情境:** User in airplane mode (or on a captive Wi-Fi portal) scans a common product that IS in Open Food Facts. The app says the product was not found in the database, so the user gives up on barcode scanning and types the label by hand instead of fixing their connection and rescanning.

### P18. [nutrition] Barcode screen blocks the main thread on ProcessCameraProvider future .get()

`app/src/main/java/com/silverbp/android/ui/nutrition/BarcodeScanScreen.kt:104`

**問題:** LaunchedEffect runs on the main dispatcher and calls ProcessCameraProvider.getInstance(context).get(), a blocking ListenableFuture.get(). On the first camera use after process start, CameraX initialization can take hundreds of milliseconds on low-end devices, freezing the UI thread (the screen renders nothing/janks; in the worst case contributes to ANR). The standard pattern is a future listener or awaiting on a background dispatcher. The onDispose path repeats the blocking get() (line 98), though by then the future is normally complete.

**情境:** User on a budget device opens the Nutrition tab and taps '掃條碼' as their first camera action; the whole UI (including the Cancel button and permission UI) freezes for a noticeable beat while CameraX initializes before the preview appears.

### P19. [nutrition] Meal photos are written before recognition and never cleaned up (orphans on cancel, leftover file on delete)

`app/src/main/java/com/silverbp/android/ui/nutrition/NutritionViewModel.kt:100`

**問題:** analyzeBitmap unconditionally writes a full JPEG to filesDir/photos before analysis. If the user cancels the confirm screen (or analysis fails and they tap Cancel), the file is orphaned — nothing ever deletes it. NutritionRepository.delete and NutritionConfirmViewModel.delete also remove only the DB row, never the photoFilename file. App-private storage grows unboundedly with daily meal photos (a JPEG per capture, including abandoned ones).

**情境:** User photographs meals daily for months, frequently retaking/canceling. The app's storage footprint grows by megabytes per week with images that no longer correspond to any log; deleting old food logs does not reclaim the space, eventually prompting the user to clear app data (losing all readings).

### P20. [onboarding-nav] System back button during onboarding steps 1-6 exits the app and loses all progress instead of going to the previous step

`app/src/main/java/com/silverbp/android/ui/onboarding/OnboardingNicknameScreen.kt:137`

**問題:** Onboarding is a single nav destination with internal step state (step 0-6, OnboardingNicknameScreen.kt:88, 137-182) and ONBOARDING is the only entry on the back stack (AppNavHost.kt:84-87 pops HOME inclusive). There is no BackHandler mapping the system back gesture/button to 'step - 1', so while the goal-profile steps render an on-screen 上一步 TextButton (GoalSelectionStep, lines 423-429), pressing the device back button or using the back gesture at any step finishes the Activity. Step state is rememberSaveable, which survives rotation but not Activity finish, so relaunch restarts at step 0. For the elderly target audience that habitually uses the hardware back gesture, this means repeatedly losing mid-onboarding progress (consent, nickname, goal answers).

**情境:** A user on step 5 of 7 (weekly availability) instinctively swipes the system back gesture to return to the previous question. The app closes entirely. Reopening it starts onboarding again from the consent screen with nickname and all selections cleared.

### P21. [play-policy] Release build silently falls back to debug signing when any keystore property is missing

`app/build.gradle.kts:74`

**問題:** buildTypes.release applies the release signingConfig only when all four KEYSTORE_* properties exist (hasReleaseSigning, lines 26-27, 74-79); otherwise it silently keeps the default debug signing. Unlike the MAPS_API_KEY guard (lines 204-214) which fails bundleRelease loudly, there is no warning or failure for missing signing config — so on a new machine with MAPS_API_KEY set but no keystore entries, ./gradlew :app:bundleRelease produces a debug-signed 'release' AAB with no indication anything is wrong. Play Console rejects debug-certificate uploads, so it cannot actually ship through Play, but assembleRelease APKs could be sideload-distributed to testers debug-signed, and the failure only surfaces at upload time after a full build. Mirror the Maps guard: in the same doFirst, throw (or at least log a prominent warning) when hasReleaseSigning is false for bundleRelease. versionCode=1 / versionName="1.0" (lines 40-41) are correct for a first release.

**情境:** Developer sets up a new laptop, copies only MAPS_API_KEY into local.properties, runs bundleRelease — build succeeds, then the Play Console upload fails with 'You uploaded an APK or Android App Bundle that was signed in debug mode', costing a confusing debugging round; an APK from the same build handed to the 12 closed testers is debug-signed.

### P22. [play-policy] minSdk 33 excludes roughly a third of active Android devices (note for store reach)

`app/build.gradle.kts:38`

**問題:** minSdk = 33 (Android 13, line 38) means the app is invisible on Play to all Android 12 and lower devices — a meaningful share of the elderly target demographic, who tend to keep older phones. This is not a policy problem (largeHeap at AndroidManifest.xml:60 is also fine), just a deliberate reach trade-off worth confirming before launch; the Health Connect dependency makes 28+ technically feasible but 33 keeps the standalone-HC-app handling simple (the manifest already handles the Android 13 legacy VIEW_PERMISSION_USAGE path, lines 100-109). No code change required — just be aware the closed-testing tester pool and production audience are limited to Android 13+.

**情境:** An elderly user with a 2020-era Android 11/12 phone searches Play for SilverBP and the listing shows 'not compatible with your device', with no way to install.

### P23. [strength-machine-ocr] Machine confirm Save has no in-flight guard and mints a new UUID per call — double-tap saves duplicate sessions

`app/src/main/java/com/silverbp/android/ui/exercise/machine/MachineConfirmViewModel.kt:74`

**問題:** save() launches a coroutine doing repo.upsert (Room write + Health Connect write, easily hundreds of ms) before onSaved() pops the screen, and the Save TextButton (MachineConfirmScreen.kt:68) stays enabled with no `saving` flag — unlike WorkoutSummaryViewModel which guards with _saving. toSession() builds ExerciseSession with the default id = UUID.randomUUID() on every call, so two taps produce two distinct rows rather than an idempotent upsert.

**情境:** An elderly user double-taps 儲存 on the confirm screen; two identical treadmill sessions appear in history and in the weekly distance/duration aggregates, and the user has to find and delete the duplicate manually.

### P24. [strength-machine-ocr] Finishing a workout with zero logged sets persists an empty strength session

`app/src/main/java/com/silverbp/android/ui/strength/WorkoutSummaryViewModel.kt:49`

**問題:** WorkoutSessionScreen's 完成 button is always enabled, and snapshotAndFinish() (StrengthWorkoutLiveStore.kt:124-129) only filters out set-less exercises, happily returning a session with items = emptyList(). WorkoutSummaryViewModel.save() then upserts the empty session row once a difficulty is picked. Nothing requires at least one logged set anywhere in the flow.

**情境:** User opens an exercise, taps 完成 immediately without adding a set, picks 剛剛好 and saves. The training hub's strength history now shows a permanent 0-set, near-0-duration workout entry that can only be removed if a delete affordance exists (history detail is the documented missing screen, so it cannot be deleted).
