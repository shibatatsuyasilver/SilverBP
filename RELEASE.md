# SilverBP — Release Guide

Steps to build, sign, and ship a Play Store release. Nothing here is committed —
secrets live in `local.properties` (git-ignored).

## 1. One-time: signing keystore

```sh
keytool -genkey -v -keystore ~/keystores/silverbp-upload.jks \
  -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

Add to `local.properties` (never commit):

```properties
KEYSTORE_PATH=/Users/<you>/keystores/silverbp-upload.jks
KEYSTORE_PASS=…
KEY_ALIAS=upload
KEY_PASS=…
MAPS_API_KEY=AIza…
```

When any `KEYSTORE_*` value is missing the release config falls back to the debug
key (a fresh clone can still build), so a real signed release **requires all four**.

## 2. One-time: restrict the Maps API key

In Google Cloud Console → Credentials → the Maps key:

- Application restriction: **Android apps**.
- Add the **package name** `com.silverbp.android` + the **SHA-1** of *both* the
  debug key and the release/upload key:
  ```sh
  keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey   # debug
  keytool -list -v -keystore ~/keystores/silverbp-upload.jks -alias upload        # release
  ```
  If you use Play App Signing, also add the **App signing key** SHA-1 from
  Play Console → Setup → App integrity.

The release build **fails** (`assembleRelease`/`bundleRelease`) if `MAPS_API_KEY`
is blank — see `app/build.gradle.kts`.

## 3. Before each release

1. Bump `versionCode` (always +1) and `versionName` in `app/build.gradle.kts`.
2. If the Room schema changed, bump `version` in `SilverBpDatabase.kt`, add the
   `MIGRATION_*`, and confirm the new `app/schemas/.../<n>.json` is committed.
3. Tests must pass:
   ```sh
   ./gradlew :app:testDebugUnitTest
   ./gradlew :app:connectedDebugAndroidTest   # needs a device/emulator; runs RoomMigrationTest, DbCipher…
   ```

## 4. Build the artifact

```sh
./gradlew :app:bundleRelease     # AAB for Play Console upload (preferred)
# or  ./gradlew :app:assembleRelease   # APK for sideload testing
```

Output: `app/build/outputs/bundle/release/app-release.aab`.

## 5. Play Console

- Upload the AAB to the **Closed testing** track first.
- Complete (if not already): Data Safety form, feature graphic 1024×500,
  ≥2 screenshots, store listing in **en** + **zh-TW**, IARC content rating.
- Closed testing needs **12 testers for 14 continuous days** before production.
- Staged rollout for production: 10% → 50% → 100% over ~1 week (elderly user base
  — catch regressions before full reach).

## 6. After release

- Watch Play Console → **Crashes & ANRs** for the new version.
- Stack traces de-obfuscate via the `mapping.txt` Play Console uploads with the
  AAB (R8 is on; `-keepattributes SourceFile,LineNumberTable` is set).

## Notes

- `data_extraction_rules.xml` / `SilverBpBackupAgent` exclude the SQLCipher DB
  from cloud backup when at-rest encryption is on — verify after schema changes.
- Privacy policy is hosted from `/docs` via GitHub Pages; `BuildConfig.PRIVACY_POLICY_URL`
  injects the URL so translators can't break it.
