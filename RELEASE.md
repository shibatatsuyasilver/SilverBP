# SilverBP — 上架發布指南

建置、簽章、上架 Google Play 的完整步驟。本文件不含任何機密 —
所有密鑰都放在 `local.properties`(已被 git 忽略)。

---

## 0. 待辦事項總覽(2026-06-11 盤點)

> 開發人員帳戶於 2026-06-11 註冊(2023-11-13 之後建立的個人帳戶),
> 因此**適用封閉測試規定**:至少 12 位測試者連續加入滿 14 天,
> 才能申請生產(正式)發布資格。Google 帳號本身的建立日期無關。

**今天就做(審核等待期最長,先排隊):**

- [ ] Play Console 建立應用程式(名稱 SilverBP、預設語言 zh-TW、免費)
- [ ] 提交**健康應用程式聲明**(見第 5 節;審核需數天到數週)
- [ ] 提交**前景服務權限聲明** `FOREGROUND_SERVICE_LOCATION`(需錄示範影片)
- [ ] 開始找**至少 12 位測試者**(建議湊 13–15 位留餘裕;需用 Google 帳號加入並實際安裝)

**等待審核期間並行準備:**

- [ ] 確認 GitHub Pages 已啟用、`docs/privacy.html` 可公開存取,URL 填入 App content → 隱私權政策
- [ ] 填寫 Data Safety(資料安全)表單(對照表見第 5 節)
- [ ] IARC 內容分級問卷、目標對象(18+,勿勾兒童)、廣告聲明(無)
- [ ] 商店資訊(en + zh-TW):名稱、簡短/完整說明、圖示 512×512、Feature graphic 1024×500、≥2 張截圖
      (文案**不得出現醫療宣稱**:不可寫「治療」「診斷」,用「記錄」「追蹤」「參考」)
- [ ] `./gradlew :app:bundleRelease` 建出 AAB,先上**內部測試**軌道自己冒煙測試
- [ ] 第一次上傳後,到 Play Console → Setup → App integrity 取得 **App signing key 的 SHA-1**,
      加進 Maps API key 的限制清單(否則正式版地圖會是空白)→ 在測試版上驗證地圖有畫面

**封閉測試(14 天):**

- [ ] 把 AAB 發布到封閉測試軌道,邀請 12+ 位測試者加入並安裝
- [ ] 跑完冒煙測試清單(見第 7 節)
- [ ] 觀察 Pre-launch report 與 Crashes & ANRs,有問題即修

**14 天期滿後:**

- [ ] 申請生產發布資格(填測試過程問卷;`notes/prerelease-audit-2026-06-11.md`
      的審查與三波修復就是現成素材)
- [ ] 通過後分階段發布:10% → 50% → 100%,每階段觀察 1–2 天

**程式碼面剩餘事項(v1.1,詳見審查報告):**

- [ ] LAN 同步補 LWW merge + tombstone 後重新開放配對入口(現藏於 `BuildConfig.DEBUG`)
- [ ] 本地化補齊:6 個中文-only 字串檔、ViewModel 內寫死的錯誤訊息
- [ ] Chat 模型下載入口、「去量血壓」按鈕導航、器材 OCR 欄位(卡路里/心率/樓層)顯示
- [ ] 重訓 process-death checkpoint、`HealthConnectBpBridge` 的 clientRecordVersion 修復、
      `EncryptedSharedPreferences` 棄用 API 遷移

---

## 1. 一次性設定:簽章 keystore

```sh
keytool -genkey -v -keystore ~/keystores/silverbp-upload.jks \
  -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

加進 `local.properties`(**絕不提交**):

```properties
KEYSTORE_PATH=/Users/<你>/keystores/silverbp-upload.jks
KEYSTORE_PASS=…
KEY_ALIAS=upload
KEY_PASS=…
MAPS_API_KEY=AIza…
```

只要任一 `KEYSTORE_*` 缺漏,release 設定就會退回 debug 簽章(讓全新 clone
也能建置),所以真正可上架的簽章版**四項缺一不可**。

## 2. 一次性設定:限制 Maps API key

到 Google Cloud Console → 憑證 → 該 Maps key:

- 應用程式限制:**Android 應用程式**。
- 加入**套件名稱** `com.silverbp.android` + debug 與 release/upload 兩把金鑰的 **SHA-1**:
  ```sh
  keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey   # debug
  keytool -list -v -keystore ~/keystores/silverbp-upload.jks -alias upload        # release
  ```
  使用 Play App Signing 時,還要加上 Play Console → Setup → App integrity
  裡的 **App signing key** SHA-1。

`MAPS_API_KEY` 留空時 release 建置(`assembleRelease`/`bundleRelease`)會**直接失敗**
— 見 `app/build.gradle.kts`。

## 3. 每次發布前

1. 在 `app/build.gradle.kts` 調升 `versionCode`(每次上傳必 +1)與 `versionName`。
2. 若 Room schema 有變動:調升 `SilverBpDatabase.kt` 的 `version`、新增對應的
   `MIGRATION_*`,並確認新的 `app/schemas/.../<n>.json` 已提交。
3. 測試必須全數通過:
   ```sh
   ./gradlew :app:testDebugUnitTest
   ./gradlew :app:connectedDebugAndroidTest   # 需要裝置/模擬器;會跑 RoomMigrationTest、DbCipher…
   ```

## 4. 建置產出物

```sh
./gradlew :app:bundleRelease     # AAB,上傳 Play Console 用(建議)
# 或  ./gradlew :app:assembleRelease   # APK,旁載測試用
```

產出:`app/build/outputs/bundle/release/app-release.aab`。

## 5. Play Console

- AAB 先上傳到**封閉測試**軌道。
- 完成(若尚未):Data Safety 表單、Feature graphic 1024×500、≥2 張截圖、
  **en** + **zh-TW** 商店資訊、IARC 內容分級。
- **健康應用程式聲明**(App content → Health):必須**提交並通過審核**才能正式發布。
  逐項說明 8 個 `android.permission.health.*` 權限的用途,並把應用程式類別設為
  **Health & Fitness**。審核可能需要數天到數週,**請及早提交**。各權限用途:
  - `WRITE_BLOOD_PRESSURE` / `WRITE_EXERCISE` / `WRITE_EXERCISE_ROUTE` / `WRITE_NUTRITION`:
    使用者記錄的血壓 / 運動(含 GPS 路線)/ 飲食可選擇性鏡像到 Health Connect
  - `READ_STEPS` / `READ_SLEEP` / `READ_NUTRITION`:步數獎章與連續達標、睡眠與飲食趨勢
  - `READ_HEALTH_DATA_IN_BACKGROUND`:排程回補缺漏天數讓趨勢保持最新(全部在裝置端處理)
- **前景服務權限聲明**(targetSdk 34+):宣告 `FOREGROUND_SERVICE_LOCATION`,
  附功能說明 + 使用者主動開始 GPS 運動追蹤的**示範影片**
  (前景定位只在使用者主動開始運動 session 時啟動;影片錄:開始運動 → 持續通知
  與地圖軌跡 → 結束,約 30 秒,上傳 YouTube 不公開連結)。
- 核對 **Data Safety** 表單涵蓋:位置、麥克風、`ACTIVITY_RECOGNITION`、相機與健康資料
  — 宣告為**儲存於裝置端**,外加**選用的端對端加密 Google Drive 備份**。對照表:

  | 資料類型 | 蒐集 | 分享 | 處理方式 |
  |---|---|---|---|
  | 健康與健身(血壓、運動、營養、睡眠) | 是 | 否 | 裝置端;選用的加密 Drive 備份(可刪除) |
  | 精確位置(運動追蹤) | 是 | 否 | 僅裝置端 |
  | 照片(血壓計/器材/食物) | 是 | 選用 Gemini 雲端 OCR 時送 Google API(使用者自備 key) | 裝置端處理 |
  | 語音 | 否(系統語音辨識即時轉文字,app 不儲存) | 否 | — |
  | 個人識別(email) | 是(Google 登入,僅備份用,可略過) | 否 | — |

  「帳號刪除」問題:app 無自建帳號(Google OAuth 僅授權 Drive 備份),可答**不建立帳號**;
  app 內已有「解除連結 + 刪除雲端備份」路徑佐證。
- 封閉測試需 **12 位測試者連續加入滿 14 天**(申請當下回頭算),才能申請生產發布。
- 生產採分階段發布:10% → 50% → 100%,約一週(高齡使用者族群 — 寧可慢,先攔住回歸問題)。

## 6. 發布之後

- 盯 Play Console → **Crashes & ANRs** 的新版數據。
- 堆疊追蹤用 AAB 隨附上傳的 `mapping.txt` 反混淆(R8 已開啟;
  `-keepattributes SourceFile,LineNumberTable` 已設定)。

## 7. 冒煙測試清單(對應 2026-06 三波修復的高風險路徑)

- 全新安裝 → onboarding → **不登入 Google,按「稍後再說」** → 能進主畫面
- 拍血壓計 → 旋轉螢幕 → 數值與照片還在 → 儲存(連點兩下)→ 歷史只有一筆
- 開始 GPS 運動 → 殺掉 app → 重開 → 恢復卡 → 繼續
- 結束運動 → 摘要頁 → 殺掉 app → 重開 → 直接進摘要可儲存
- 重訓記幾組 → 按系統返回 → 再點開始 → 出現「繼續上次訓練」對話框
- 掃一個只有每 100g 營養資料的條碼 → 確認頁出現換算/每 100g 提示
- 設定 → 開啟 app 鎖(觸發加密遷移)→ 強制重開 app → 正常解鎖、資料完整
- 備份 → 匯出 → 用「取代」匯入 → 中途按返回 → 資料完整(交易回滾)
- 正式簽章版:運動地圖有顯示(驗證 Maps key 的 App signing SHA-1)

## 備註

- 開啟靜態加密(at-rest encryption)時,`data_extraction_rules.xml` /
  `SilverBpBackupAgent` 會把 SQLCipher 資料庫排除在雲端備份之外 —
  schema 變動後請再驗證一次。
- 隱私政策由 `/docs` 透過 GitHub Pages 提供;`BuildConfig.PRIVACY_POLICY_URL`
  注入網址,翻譯時不會弄壞連結。
