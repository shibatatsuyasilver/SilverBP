# Plan: Opt-in biometric app-lock + at-rest encryption

## Decisions (locked with user)
- Scope: **app-lock UI gate + encrypt data at rest** (SQLCipher Room + encrypted sensitive DataStore fields).
- Re-lock: **on return from background after a timeout** (default 60 s; not on every foreground).
- Opt-out (decrypt back to plaintext): **in scope / required** — must be a reliable
  escape hatch, same snapshot/verify/swap discipline as the forward migration.
- Default: **OFF**. Single opt-in toggle in Settings ("保護我的健康資料 / Protect my health data").
- Auth model: **A — fingerprint + device PIN/password as co-equal factors**.
  No app-specific password; encryption key stays Keystore-wrapped (never derived
  from a user secret) → a forgotten/failed credential never loses data.

## Core design principle (non-negotiable)
**Decouple the encryption key from biometric.** The DB passphrase is a random 32-byte
key wrapped by a hardware-backed Android Keystore key (envelope encryption), stored in
`EncryptedSharedPreferences` — *not* `setUserAuthenticationRequired(true)`-bound.

Rationale:
- A failed/reset/locked-out fingerprint must **never** destroy health data. Auth-bound
  Keystore keys are invalidated when biometrics change → permanent data loss. Unacceptable.
- Background work (coach narration, reminders, sync) must read the DB while the UI is
  locked. A non-auth-bound key allows this; biometric is purely a UI gate.
- Honest threat model: encryption defends against ADB backup / file copy / non-root
  forensic extraction. Biometric defends against unattended-unlocked / shared device.
  Neither claims to stop a rooted attacker who can read Keystore — state this in privacy.html.

## Threat model statement (for privacy.html §8 update)
> Optional app-lock requires biometric or device PIN to open the app. When enabled,
> the local database and sensitive settings are encrypted at rest with a key held in
> the device's hardware-backed keystore. This protects against device backup and file
> extraction on a non-rooted device; it is not designed to resist a rooted device.

## Components

### 1. Crypto/key layer — reuse the existing pattern
Mirror `sync/.../pairing/KeyStore.kt`'s `EncryptedPairingKeyStore` (Keystore +
EncryptedSharedPreferences, AES256-GCM/SIV). New: `app/.../security/DbKeyStore.kt`
- `getOrCreatePassphrase(): ByteArray` — 32 random bytes, created once on opt-in,
  persisted Keystore-wrapped. `clear()` for opt-out.
- Prefs file `silverbp.dbkey` (separate from sync's `silverbp.sync.rootkeys`).

### 2. SQLCipher Room integration — single touch point
- Deps (`gradle/libs.versions.toml` + `app/build.gradle.kts`):
  `net.zetetic:sqlcipher-android:4.6.1`, `androidx.sqlite:sqlite:2.4.0`.
- `SilverBpDatabase.get(context)` ([app/.../core/db/SilverBpDatabase.kt:50-72](app/src/main/java/com/silverbp/android/core/db/SilverBpDatabase.kt#L50-L72)):
  if encryption enabled, add `.openHelperFactory(SupportOpenHelperFactory(passphrase))`.
  Flag + key resolved **synchronously** at first call (EncryptedSharedPreferences read
  is sync). No signature change for callers; `ServiceLocator.database` unaffected.
- Sync module reads app DAOs via `ServiceLocator` (it does **not** open its own DB —
  verified), so it inherits the encrypted handle automatically. No sync changes.

### 3. Plaintext → encrypted migration (the irreversible, highest-risk step)
Runs **once, on opt-in**, with Room closed, off the main thread:
1. Snapshot: copy `silverbp.db*` → `silverbp.db.bak*`.
2. Open plaintext DB; `ATTACH DATABASE 'enc.db' AS encrypted KEY '<pp>'`;
   `SELECT sqlcipher_export('encrypted')`; `DETACH`.
3. Integrity check: open `enc.db` with passphrase, `PRAGMA quick_check`, verify row
   counts on key tables (bp_reading, chat_message, medication) vs original.
4. Atomic swap: replace `silverbp.db` with `enc.db` only after step 3 passes.
5. On any failure: restore from `.bak`, abort opt-in, surface a clear error, leave
   the app in the plaintext+unlocked state (no data loss, no partial state).
6. Delete `.bak` only after the encrypted DB opens cleanly through Room once.
- Opt-out path (**required**): reverse `sqlcipher_export` back to plaintext +
  `DbKeyStore.clear()`, same snapshot → integrity/row-count verify → atomic swap →
  rollback-on-failure discipline as the forward migration. Symmetric, fully tested.

### 4. Sensitive DataStore fields
Preferences DataStore has no native encryption. Scope-bounded approach: encrypt only
the sensitive subset at the repository layer in
[UserSettingsRepository.kt](app/src/main/java/com/silverbp/android/settings/UserSettingsRepository.kt)
— transparent encrypt-on-set / decrypt-on-get via the same Keystore envelope:
`geminiApiKey`, `systemPrompt`, `chatPersona`, `userNickname`, `hfToken` (if stored).
Non-sensitive prefs stay plain (no perf/migration cost). One-time migration of existing
values on opt-in.

### 5. Biometric UI gate
- Dep: `androidx.biometric:biometric:1.1.0`. `androidx.lifecycle:lifecycle-process`
  for `ProcessLifecycleOwner` (check libs.versions.toml — lifecycle likely present).
- `LockManager` (singleton via ServiceLocator): holds `enabled`, `unlocked`,
  `lastBackgroundedAt`, `timeoutSeconds`. `ProcessLifecycleOwner` observer:
  ON_STOP → stamp time; ON_START → if `enabled && now-stamp > timeout` → set locked.
- `LockGate` composable wrapping `AppNavHost` content in
  [MainActivity.kt](app/src/main/java/com/silverbp/android/MainActivity.kt) /
  [AppNavHost.kt](app/src/main/java/com/silverbp/android/ui/nav/AppNavHost.kt):
  when locked, render an opaque lock screen + auto-invoke `BiometricPrompt`
  with `setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`.
  Fingerprint **and** the device PIN/pattern/password are presented as co-equal
  unlock paths (one system sheet, user picks either) — not a buried fallback.
  Critical for elderly: worn/wet fingerprints fail often, so the PIN path must be
  immediately reachable. Onboarding gate stays first; lock gate after onboarding.
- No-device-lockscreen handling: `BiometricManager.canAuthenticate(...)` returns
  `ERROR_NONE_ENROLLED` → block the toggle, explain, and deep-link via
  `ACTION_BIOMETRIC_ENROLL` / `Settings.ACTION_SECURITY_SETTINGS` so the user sets
  a device lock first (such devices also have no OS file-based encryption anyway).
- `MainActivity`: set `WINDOW_FLAG_SECURE` when lock enabled (blocks recents
  thumbnail + screenshots). Clear when disabled.

### 6. Settings + state
- `UserSettings`: add `appLockEnabled: Boolean = false`,
  `appLockTimeoutSeconds: Int = 60`. Repo keys + setters.
- `SettingsScreen` Personalization/Security section: a toggle. Enabling →
  `BiometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)` check →
  if no enrollment, deep-link to system enrollment / show explanation → confirm dialog
  ("此動作會加密既有資料,請稍候") → run migration (progress UI, blocking) → set flag.
  Disabling → confirm → decrypt-back → clear key → clear flag → clear FLAG_SECURE.
- New strings in both `values/` and `values-zh-rTW/strings.xml`.

## Files touched
- New: `app/.../security/DbKeyStore.kt`, `app/.../security/DbCipherMigration.kt`,
  `app/.../security/LockManager.kt`, `app/.../ui/lock/LockScreen.kt`.
- Modified: `SilverBpDatabase.kt`, `ServiceLocator.kt`, `MainActivity.kt`,
  `AppNavHost.kt`, `UserSettings.kt`, `UserSettingsRepository.kt`,
  `SettingsScreen.kt`, `SettingsViewModel.kt`, `strings.xml` (×2),
  `gradle/libs.versions.toml`, `app/build.gradle.kts`, `docs/privacy.html` (§8).

## Risks / call-outs
- **Migration data loss** is the dominant risk → snapshot + verify + atomic swap +
  rollback, gated behind explicit user confirmation. Heavily unit/instrumentation-test
  the migration before shipping.
- SQLCipher: ~5–15% query overhead (acceptable for this workload); APK +~3 MB
  native libs; R8 keep rules needed (currently `isMinifyEnabled=true`).
- Room `exportSchema=true` + SQLCipher: keep schema export; SupportFactory is
  compatible with Room migrations (apply key before migrations run).
- `MIGRATION_*` chain still runs **inside** the encrypted DB after opt-in — verify
  the existing 1→11 migrations execute under SQLCipher (they should; same SQL).
- Elderly UX: device PIN path co-equal (not buried); timeout default 60 s (not 0);
  toggle fully reversible (opt-out decrypts back).

## Verification
- Unit: `DbKeyStore` round-trip; `LockManager` timeout logic (boundary cases).
- Instrumentation: seed plaintext DB → opt-in → assert all row counts preserved,
  DB unreadable without key (`sqlite3` open fails), Room reads succeed; opt-out
  restores plaintext; forced migration failure → `.bak` restored, no data loss.
- Manual: enable → background > 60 s → BiometricPrompt; unlock via device PIN
  (fingerprint skipped); no-lockscreen path; recents thumbnail blocked; background
  coach/reminder still writes while locked; **opt-out restores plaintext, app reopens
  with all data intact**.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest`.

## Suggested commit sequence (feature branch)
1. Add SQLCipher/biometric deps + `DbKeyStore`.
2. Wire optional SQLCipher into `SilverBpDatabase`/`ServiceLocator` (flag off = no-op).
3. Migration engine + tests.
4. Encrypt sensitive DataStore fields.
5. `LockManager` + `LockScreen` + FLAG_SECURE + AppNavHost gate.
6. Settings toggle + strings + privacy.html §8.
