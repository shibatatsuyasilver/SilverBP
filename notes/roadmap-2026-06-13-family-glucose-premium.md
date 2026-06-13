# SilverBP 產品路線圖:家人成員、血糖紀錄與 Premium 訂閱

日期:2026-06-13。狀態:封閉測試中(versionCode 3,DB schema v17)。本文件規劃 v1.0 正式上線後的三大功能:**家人多成員(v1.1)**、**血糖紀錄(v1.2)**、**Premium 訂閱(v1.3)**,含架構設計、分期理由、依賴與風險。相關文件:`RELEASE.md`(上架操作手冊)、`notes/prerelease-audit-2026-06-11.md`(87 條審查發現,本文沿用其 B/M 編號)。

---

## 1. 背景與現狀

### 1-1. 產品決策摘要(業主已裁定,2026-06-13)

| 決策點 | 結論 |
|---|---|
| 付款方式 | **Google Play Billing**(上架 Play 販售數位訂閱為政策強制;訂閱抽成 15%) |
| 家人整合範圍 | **單機多成員檔案**:一隻手機建立多位成員(例:孫子的手機記錄外公+外婆),量測時選歸屬,各自獨立歷史/圖表/報告。不做跨裝置成員同步,但 schema 預留 |
| 免費版 | 基本血壓紀錄(含拍照辨識)、1 位成員、單頁 PDF 摘要、BYO-key 雲端辨識、本地 AI 聊天/教練基本功能 |
| 付費版(Premium) | 多位家人成員、血糖(每成員免費 10 筆試用後)、完整多頁 PDF 報告、AI 進階(進階教練週報敘事+聊天長上下文) |
| 雲端 Gemini 辨識 | **維持免費**——現狀用使用者自己的 API key(`settings/UserSettings.kt` 的 `geminiApiKey`),app 不負擔成本,鎖進付費牆理由薄弱。未來若提供「免 key 內建雲端額度」才屬付費範圍 |
| PDF 拆層 | 免費保留**單頁統計摘要**(現有封面頁),付費解鎖完整多頁(逐筆表格+血糖節+AI 摘要)。理由:PDF 報告封測版已免費提供,上市時整個拿走是流失第一殺手 |
| 血糖閘門 | **優雅預覽**:功能可見,每成員免費 10 筆(約一兩週空腹量測,足以養成習慣),第 11 筆儲存時出付費牆;既有紀錄永遠可看。理由:付費者多半是子女,使用者是長輩——長輩先用出習慣,家人才看得到價值 |

### 1-2. 程式現狀(與本路線圖相關的事實)

- **單人架構**:`core/db/Entities.kt` 的 `BpReadingEntity` 沒有任何成員/使用者欄位;`UserProfileEntity` 是單例(`BpDao.kt` 以 `LIMIT 1` 查詢)。所有讀數隱含屬於裝置唯一使用者。
- **無金流基礎**:無 Play Billing、無 Firebase、無後端伺服器。app 離線優先,Google 登入僅供 Drive 備份(可跳過)。DI 為手寫 `di/ServiceLocator.kt`(lazy val 註冊)。
- **既有的功能閘門前例**:`enableCoach`(`settings/UserSettings.kt`)控制 Coach tab 顯示(`ui/nav/AppNavHost.kt` 過濾 tab)與提醒排程——訂閱閘門直接鏡像此模式。
- **OCR 引擎已多用途共用**:`recognition/GemmaBpService.kt` 同一引擎服務血壓(`BpPrompt.kt`)、營養(`NutritionPrompt.kt`)、健身器材(`MachineDisplayPrompt.kt`)三組 prompt——血糖 OCR 照此模式加第四組即可。
- **audit blocker 收尾狀態**:

| 編號 | 內容 | 狀態 |
|---|---|---|
| B1/B2/B3 | 相機權限崩潰 ×2、GPS 恢復崩潰 | ✅ 已修(commit `7f5ff3f`) |
| B4 | Google 登入硬閘 | ✅ 已修(commit `7f5ff3f`,新增跳過路徑) |
| M2 等備份原子性 | Replace 還原非原子 | ✅ 已修(commit `be57849`) |
| M5–M8 等確認流程 | confirm 流程資料遺失/重複儲存 | ✅ 已修(commit `1a40979`) |
| **B5** | 健康應用程式聲明未在 Play Console 提交 | ❌ **待辦——Console 作業,非程式碼**(操作見 RELEASE.md C-8) |
| **B6** | 同步缺 LWW 閘(`sync/.../engine/Merger.kt` 仍是空 stub) | ❌ 待辦——配對入口藏在 `BuildConfig.DEBUG` 後,不擋 v1.0;**排入 Phase 1**(理由見 §3-6) |

---

## 2. 階段總覽

| 階段 | 範圍 | 估時 | DB schema | 發布載具 |
|---|---|---|---|---|
| **Phase 0** | B5 健康聲明送審 + 14 天封測 + 申請生產資格 → 正式上線 | ~2 週(日曆主導) | v17(不動) | **v1.0** |
| **Phase 1** | 家人多成員(單機)+ B6 LWW 補實作 | 3–4 週 | **v18**(member 表 + memberId) | **v1.1** |
| **Phase 2** | 血糖紀錄 + OCR + 健康聲明重送 | 3–4 週 | **v19**(glucose_reading 表) | **v1.2** |
| **Phase 3** | Play Billing 訂閱 + 付費牆 | 2–3 週 + 灰度 1 週 | 不動(DataStore) | **v1.3** |

總計約 **3 個月**到 v1.3 全功能(單人開發節奏)。

### 排序理由

1. **家人先於血糖**:memberId 是「越早做越便宜」的重構。v18 先落地,血糖表(v19)出生即帶 `memberId NOT NULL`,不需要二次遷移;反過來做,血糖表要再吃一次 ALTER+回填,血糖 UI 也要為成員切換重工一次。深層 schema 重構應在使用者基數最小時完成。
2. **為什麼不先做血糖快贏**:血糖確實 2 週可出貨,但它是訂閱主力賣點——先全免費出貨等於「放送後再收回」,加重 Phase 3 的過渡負擔。照本順序,血糖在 v1.2 以「免費 10 筆」狀態誕生,v1.3 直接接上付費牆,**從未全免費過**。
3. **訂閱最後**:(a) 付費牆要等被閘的功能存在才有意義;(b) Billing 只能在內部/封閉軌道+授權測試人員上驗證,放在生產資格取得、軌道齊備之後最順;(c) 定價與文案可吸收 v1.1/v1.2 的測試者回饋。
4. ⚠️ **封測 14 天期間不動 schema**:v17→v18 是全表回填遷移,若中途推給封測者,任何遷移 bug 都會直接打斷「連續 14 天」的測試者留存(RELEASE.md G–I 節)。**v18 只在 v1.0 取得生產資格並正式發布後才上封閉軌道**;期間 Phase 1 在分支開發 + `RoomMigrationTest` 覆蓋,不發布。

---

## 3. Phase 1:家人多成員(schema v18,v1.1)

### 3-1. 資料模型:新建 `member` 表,`user_profile` 凍結

**決策:新表,不擴充 `user_profile`。** 理由:`user_profile` 是 LIMIT 1 單例、已被 sync 格式保留 tag、語意是「裝置主人的臨床檔案」;改造它的 sync/backup 相容成本高於新表,新表可乾淨定義 owner 旗標與排序。

新檔 `core/db/MemberEntities.kt`:

```kotlin
@Entity(tableName = "member")
data class MemberEntity(
    @PrimaryKey val id: String,          // UUID
    val displayName: String,             // 空字串 → UI fallback「我」/"Me"(勿把中文寫死進 DB)
    val isOwner: Boolean,                // 全表恰一筆 true;HC 鏡像與 owner-only 資料的錨點
    val birthYear: Int?,
    val hasDiabetes: Boolean, val hasCKD: Boolean, val hasASCVD: Boolean,
    val guideline: String,               // 每位成員可有自己的血壓指引(沿用 HypertensionGuideline.raw)
    val colorIndex: Int,                 // 0..7 固定調色盤(頭像/圖表識別色)
    val sortOrder: Int,
    val archived: Boolean = false,       // 軟刪除:保留歷史、從切換器隱藏
    val createdAt: Long, val updatedAt: Long,
    val hlcUpdatedAt: String = "0",      // 為未來跨裝置同步預留
)
```

### 3-2. memberId 邊界——縮小重構面的核心決策

**只有「人工輸入的臨床量測」加 memberId;感測器/生活資料維持 owner-only。**

| 加 memberId(客體=被照顧者) | 不加(主體=裝置主人) |
|---|---|
| `bp_reading` | `exercise_session`、`route_point`、`strength_*`、`daily_step_log` |
| `medication`(schedule/dose 經 FK 繼承,不冗餘存) | `food_log`、`sleep_log`、`diet_check` |
| `glucose_reading`(v19 出生即帶) | `coach_plan/task`、`chat_*`、`achievement`、`bp_workout_association` |

論證:步數/睡眠/運動來自**這支手機的感測器與 Health Connect**,把它們標成「外婆的」在資料來源上就是錯的;Coach/聊天/獎章建立在這些感測資料上,同屬 owner。memberId 限縮在血壓、血糖、用藥三族,重構面從 25 表縮到 3+1 表。`bp_workout_association` 不加欄位,但建立關聯時只對 owner 的讀數建立(repository 層 guard)。`tag`/`reading_tag` 經 readingId 派生,不需要。

### 3-3. 遷移 v18(`MIGRATION_17_18`,落點 `core/db/SilverBpDatabase.kt`)

沿用既有 additive 風格(參照 `MIGRATION_13_14` 的 NOT NULL DEFAULT 模式):

1. `CREATE TABLE member (...)` + partial unique index:`CREATE UNIQUE INDEX index_member_isOwner ON member(isOwner) WHERE isOwner = 1`(保證單一 owner)。
2. 回填「我」:讀 `user_profile LIMIT 1`;有 → INSERT member 沿用其 id、displayName、birthYear、三個病史旗標、guideline,`isOwner=1`;無(理論上 onboarding 必建)→ 新 UUID + 空 displayName。
3. `ALTER TABLE bp_reading ADD COLUMN memberId TEXT NOT NULL DEFAULT ''` → `UPDATE bp_reading SET memberId = <meId>` → 建複合索引 `(memberId, timestamp)`(主查詢路徑)。
4. `medication` 同款 ADD COLUMN + UPDATE + INDEX。schedule/dose 不動。
5. 提交 `app/schemas/.../18.json`;`RoomMigrationTest` 加 17→18 案例(**含 user_profile 不存在的分支**)。

### 3-4. 狀態貫穿:CurrentMemberStore(DataStore)

**決策:DataStore 持久化選擇,否決 session 級 MemberContext。** 理由:撐過 process death(長輩機常被 OEM 殺程序)、與 `settings/UserSettingsRepository.kt` 同款現成模式、選擇是**裝置本地**狀態(刻意不進 settings 同步——未來兩台裝置各選各的成員)。

| 新增/修改 | 內容 |
|---|---|
| `core/member/MemberRepository.kt`(新) | 包 `MemberDao`(新):`observeActive()`、`ownerId()`(快取)、`upsert/archive` |
| `core/member/CurrentMemberStore.kt`(新) | DataStore key `current_member_id`,`flow: Flow<String>`(空 → fallback ownerId) |
| `di/ServiceLocator.kt` | 加 `memberRepository`、`currentMemberStore` lazy val |
| `core/db/BpDao.kt` | 查詢全部加 memberId 維度:`observeLatest(memberId)`、`observeAll(memberId)`、`observeRange(memberId, from, to)`、`count(memberId)` |
| `core/BpRepository.kt`、`core/Models.kt` | observe 系列加參數;`BpReading` domain model 加 `memberId` |
| ViewModels | `ui/today/TodayViewModel.kt`、`ui/history/HistoryViewModel.kt`、`ui/insights/InsightsViewModel.kt`、`ui/report/ReportViewModel.kt` 改 `currentMemberStore.flow.flatMapLatest { repo.observeXxx(it) }` |
| 記錄流 | `ui/confirm/ConfirmReadingViewModel.kt` 的 draft 帶 memberId(預設=目前選擇);Confirm 畫面顯示成員列,儲存前可改該筆歸屬(不動全域選擇)。`CaptureFlowViewModel` 不動 |
| 用藥 | `MedicationEntity` 加 memberId;提醒通知文案帶成員名(「外婆的降壓藥時間到了」),`MedicationReminderScheduler` JOIN 出成員名 |

### 3-5. ⚠️ Health Connect 規則:只有 owner 成員的讀數鏡像

把外婆的血壓寫進孫子的 Google 健康是**資料正確性/隱私 bug**。Guard 兩處,缺一不可:

1. `core/BpRepository.kt` 鏡像條件加 `toSave.memberId == memberRepository.ownerId()`。
2. `BpDao.findUnmirrored()` 加 `memberId = :ownerId` 過濾(否則 `health/BpSyncWorker.kt` 的重試集合會把非 owner 列撈回來補鏡像)。

非 owner 列 `hcRecordId` 永遠為 null——在 `Entities.kt` 該欄位 KDoc 註明這是設計而非缺漏。`health/HealthConnectBpBridge.kt` 本身不改(repository 層擋下,bridge 維持單一職責)。

### 3-6. UI 設計

- **成員切換器**:Today 與 Data 兩個 tab 的 TopAppBar actions 放 `ui/member/MemberSwitcherChip.kt`(新):彩色圓形頭像(colorIndex+姓名首字)+ 名稱,點開 bottom sheet 列出成員 + 「管理成員」入口。**只有一位成員時 chip 隱藏**——現有單人使用者完全無感(對齊 enableCoach 的「預設不變」哲學)。
- **Today 隨選擇調適**:選 owner → 現狀全卡片;選其他成員 → 只顯示該成員的血壓卡 + 記錄按鈕 + 用藥卡(v1.2 起加血糖卡),隱藏步數/Coach/運動卡(owner-only)。
- **Exercise / Nutrition / Coach 三個 tab 永遠 owner-only**,不受切換影響;tab 頂部不顯示 chip,避免誤解。
- **Data / Insights / Report** 顯示目前選擇成員;**PDF 報告封面印成員姓名**(交給醫師時必要)。
- **成員管理**:Settings 新增「家人成員」卡片區 → `ui/member/MemberManagementScreen.kt`(列表+排序+封存)+ `MemberEditorSheet`(姓名、出生年、病史旗標、指引、顏色);路由加進 `ui/nav/Destinations.kt`。
- 用藥提醒維持本機職責(這支手機就是全家照護中樞);不做 per-member 提醒開關(YAGNI,進 backlog)。

### 3-7. 備份與同步(⚠️ 與 B6 的順序依賴)

- **`.sbpbk` 備份**:`backup/BackupManager.kt` 的 export/import 與 `clearSyncTables()` 加入 `member` 表;**順手修 audit M3(clearSyncTables 漏了 food_log)**;`FORMAT_VERSION` +1;舊版備份匯入時無 member 表 → 匯入器合成 owner 成員、無 memberId 的讀數歸 owner(向後相容)。
- **LAN sync wire format**:`SyncEntityType` 加 `MEMBER` tag;`BpReadingSyncMapper`/`MedicationSyncMapper` payload 加 memberId 欄位;新 `MemberSyncMapper`。
- ⚠️ **順序依賴**:B6 的 LWW 閘實作(`sync/.../engine/Merger.kt` + `CombinedRoomSyncSink.apply` 比較 `record.hlc` vs 本地 `hlcUpdatedAt`)與 memberId wire format 變更,**兩者都必須在配對入口走出 `BuildConfig.DEBUG` 之前完成**——否則公開後的第一批配對裝置會以舊格式互灌資料。兩件都排進 Phase 1;配對入口最早 v1.2 才考慮開放。

### 3-8. 免費/付費邊界(Phase 3 才接真閘)

免費 = 1 位成員(我);付費解鎖「新增成員」。**閘點唯一**:`MemberManagementScreen` 的新增按鈕。Phase 1 先不閘(封測者全開),Phase 3 接上 `EntitlementManager`。

**原則(寫死):退訂不鎖資料**——已建立的成員與其歷史永遠可看可記,只擋「再新增」。對長輩健康資料,鎖讀取是信任毀滅。

---

## 4. Phase 2:血糖紀錄(schema v19,v1.2)

### 4-1. Entity 與分類

新檔 `core/db/GlucoseEntities.kt` + `GlucoseDao.kt` + `MIGRATION_18_19`:

```kotlin
@Entity(tableName = "glucose_reading",
        indices = [Index("timestamp"), Index("memberId", "timestamp")])
data class GlucoseReadingEntity(
    @PrimaryKey val id: String,
    val memberId: String,            // v18 之後出生即 NOT NULL,無回填
    val valueMgdl: Double,           // 正準單位一律 mg/dL;1 mmol/L = 18.016 mg/dL
    val displayUnit: String,         // "mgdl" | "mmol" — 記錄當下的輸入/顯示單位
    val measureContext: String,      // fasting | before_meal | after_meal | bedtime | random
    val timestamp: Long,
    val source: String,              // manual | camera(對齊 bp_reading.source)
    val confidence: Double, val note: String, val photoFilename: String?,
    val createdAt: Long, val updatedAt: Long,
    val hlcUpdatedAt: String = "0", val hcRecordId: String? = null,
)
```

- `core/GlucoseGuideline.kt`(與 `core/HypertensionGuideline.kt` 並列):`GlucoseCategory { VeryLow, Low, Normal, Elevated, High }`,**依 measureContext 分類**——空腹 <54 VeryLow / <70 Low / 70–99 Normal / 100–125 Elevated(糖尿病前期)/ ≥126 High;餐後 2h <140 Normal / 140–199 Elevated / ≥200 High;隨機 ≥200 High。指引出處:ADA Standards of Care + 台灣糖尿病學會臨床照護指引(UI 免責聲明沿用 BP 模式:本 app 非醫療器材)。
- **長輩安全**:儲存時 VeryLow/Low 立即顯示就地警示卡(低血糖比高血糖急)。**不做背景 watcher**——避免重蹈 M17(血壓異常警報重複轟炸)的覆轍。
- 單位偏好進 `UserSettings.glucoseUnit`(預設 mg/dL,台灣血糖機主流)。

### 4-2. Gemma OCR 重用

`GemmaBpService` 已是多 prompt 共用引擎(`NutritionPrompt`、`MachineDisplayPrompt` 先例),**不改名、加 sibling**:

- `recognition/GlucosePrompt.kt`:要求模型輸出數值+判斷單位。⚠️ 單位辨識用 heuristics 交叉驗證:mmol/L 機型數值帶小數點且 <35,mg/dL 為 2–3 位整數——prompt 判斷與數值範圍不一致時降 confidence。
- `recognition/GlucoseResponseParser.kt`、`ExtractedGlucose.kt`、`GlucoseRecognizer.kt`(本地)+ 雲端走 `GeminiCloudRecognizer` 同款路徑。
- 擷取流仿健身器材 OCR 前例(`MachineCaptureScreen`):`ui/capture/GlucoseCaptureScreen.kt`(薄包裝)→ `ui/confirm/ConfirmGlucoseScreen.kt` + `ConfirmGlucoseViewModel`(**直接帶入 M5/M6/M8 的修法**:isSaving guard、SavedStateHandle、錯誤不 rethrow)。

### 4-3. UI 擺位:不開第 6 個 tab

- **Today**:新增 `GlucoseCard`(最近一筆+分類色+「記血糖」按鈕),跟隨選擇成員。
- **Data tab**:`ui/data/DataHubScreen.kt` 在 segmented control(紀錄|分析)上方加「**血壓|血糖**」filter chips;血糖側新建 `ui/history/GlucoseHistoryScreen.kt`、`ui/insights/GlucoseInsightsScreen.kt`(時間序列散點按 measureContext 上色 + 7/30 天均值線 + context 分佈,重用 `ui/insights/charts/` 的元件)。
- 否決方案:第三 segment(讓「紀錄|分析」與量測類型兩個維度打架)、第 6 個 bottom tab(M3 nav 5 個已是上限,長輩可發現性差)。

### 4-4. ⚠️ Health Connect 與健康聲明重送(critical path)

- `AndroidManifest.xml` 加 `android.permission.health.WRITE_BLOOD_GLUCOSE`;新 `health/HealthConnectGlucoseBridge.kt` 鏡像 `BloodGlucoseRecord`(`specimenSource`/`relationToMeal` 由 measureContext 映射);owner-only 規則同 §3-5。
- ⚠️ **新增 health.* 權限就要重審健康應用程式聲明**(數天到數週)。**Phase 2 第一週就提交聲明更新**,不等程式寫完——這是 Phase 2 的 critical path。理由文案沿用 RELEASE.md C-8 的格式模板(「使用者手動或拍照記錄的血糖值,經其同意鏡像寫入 Health Connect」)。
- `docs/privacy.html` 同步增修血糖揭露(連同 M37/M38 的背景讀取與 Drive 備份揭露,若屆時尚未修)。

### 4-5. 整合

- `ui/chat/RecordsContextBuilder.kt`:加血糖 7 天摘要區塊(筆數、空腹均值、餐後均值、低血糖事件),控制在 ~30 token。
- `reporting/PdfReportRenderer.kt`:報告加血糖節(封面統計+獨立表格頁)。不做血糖獨立報告——回診是同一位醫師看。
- 空腹血糖量測提醒:**遞延 v1.4 backlog**(`MedicationReminderScheduler` 模式可複用,但 Phase 2 範圍要守住)。

### 4-6. 付費閘:免費 10 筆優雅預覽

每位成員免費 10 筆,第 11 筆儲存時出付費牆;既有紀錄永遠可看可編輯。實作:`EntitlementManager.gateGlucoseSave(currentCount)`,計數查 `GlucoseDao.count(memberId)`,無需額外狀態。**Phase 2 出貨時 Billing 未上線 → 以設定旗標暫時全開**,Phase 3 接真閘(封測者屆時有促銷碼過渡,見 §5-5)。

---

## 5. Phase 3:Premium 訂閱(v1.3)

### 5-1. Play Billing 整合

- 依賴:`com.android.billingclient:billing-ktx` **8.x**。⚠️ Google 年度最低版本政策:2026-08-31 起新上傳需 8+,現在整 7.x 等於立刻欠債。`gradle/libs.versions.toml` 新增。
- 新 package `billing/`:
  - `BillingClientWrapper.kt`:連線重試、`queryProductDetailsAsync`、`launchBillingFlow`、`PurchasesUpdatedListener`、PENDING 處理、**`acknowledgePurchase`(3 天內不確認 Google 自動退款——最容易踩的坑)**。
  - `EntitlementManager.kt`:見 §5-2。
  - `ui/paywall/PaywallScreen.kt` + `PaywallViewModel.kt`。
- `di/ServiceLocator.kt` 加 `billingClient`、`entitlementManager` lazy val;`SilverBpApplication.onCreate` 觸發首次 `queryPurchasesAsync`。
- **商品結構**:一個訂閱 `silverbp_premium`,兩個 base plan:
  - `premium-monthly`:NT$90/月(壓在 NT$100 心理線下)
  - `premium-yearly`:NT$750/年(≈月付 62,約 31% off,掛 **7 天免費試用 offer**)
  - 定價思路:付費者是子女;年繳導向(健康習慣是長期的)。

### 5-2. EntitlementManager:無後端的單一真相源

```kotlin
enum class Entitlement { Free, Premium }
class EntitlementManager(billing: BillingClientWrapper, dataStore: DataStore<…>) {
    val entitlement: StateFlow<Entitlement>   // 啟動即發出快取值,無閃爍
}
```

- 來源優先序:(1) DataStore 快取的上次已知狀態(冷啟動瞬間可用,**避免離線鎖死與 UI 閃爍**);(2) 冷啟與 ON_RESUME 時 `queryPurchasesAsync`(Play 本身有本地購買快取,離線也回得來),結果寫回 DataStore。再驗證頻率:每次冷啟 + 每 24h 一次 WorkManager。
- **明寫的取捨**:無伺服器 = 無 server-side verification,root 裝置理論上可破解。對本地優先、無帳號系統的健康 app,盜版風險面 ≈ 0,**不做 Play Integrity**(若未來提供開發者出資的雲端 AI 額度才需要,屆時連後端一起上)。

### 5-3. 閘點清單(鏡像 enableCoach 模式)

可重用件:`ui/paywall/PaywallSheet.kt`(ModalBottomSheet,被閘動作觸發)+ `ui/components/PremiumBadge.kt`(統一「進階」徽章)。

| 功能 | 閘點檔案(各一行 `if (entitlement == Free) showPaywall()`) |
|---|---|
| 新增第 2 位成員 | `ui/member/MemberManagementScreen.kt` 新增按鈕 |
| 血糖第 11 筆儲存 | `ConfirmGlucoseViewModel.save()` |
| 完整多頁 PDF(免費=單頁摘要) | `ui/report/ReportScreen.kt` 匯出選項 |
| AI 進階(進階教練週報敘事、聊天長上下文) | `coach/CoachNarrator.kt` 呼叫端 / `ui/chat/ChatViewModel.kt` 上下文長度 |

**不閘**:基本血壓紀錄與拍照辨識、BYO-key 雲端辨識、本地聊天/教練基本功能、單頁 PDF、Health Connect、備份。

### 5-4. 付費牆 UX(長輩導向)

單頁、大字、兩張價格卡(年繳標「最划算」+ 7 天試用字樣)、**恢復購買**按鈕(重跑 `queryPurchasesAsync`)、**管理訂閱** deep link(`https://play.google.com/store/account/subscriptions?sku=silverbp_premium&package=com.silverbp.android`)。

文案以**家人付費**視角寫(「為爸媽解鎖全家健康管理」)——點購買的多半是子女,因此付費牆也要能從 Settings 直達(子女幫忙操作時找得到),不只藏在閘點後。

### 5-5. 封測者過渡(grandfathering)

1. **PDF 拆層**(§1-1 已裁定):免費版保留單頁統計摘要(現 `PdfReportRenderer` 封面頁:區間統計+分類佔比),付費版才是完整多頁。免費使用者沒有損失感、付費差異又清楚。
2. **封測者促銷碼**:Play Console 訂閱 promo codes,給封閉測試 Google Group 全員發 **6 個月 Premium**,測試群組公告。成本趨近 0,買到首批評論與口碑。

### 5-6. Play Console 設定與測試矩陣

設定(checklist,風格同 RELEASE.md):

1. 建立付款商家檔案(設定 → 付款設定)。
2. 營利 → 訂閱 → 建立 `silverbp_premium` + 兩個 base plan + yearly 試用 offer → 台灣定價(+其他發行國)→ 啟用。
3. ⚠️ **App content 兩處要回頭改**:IARC 問卷「數位商品購買」從否改是(RELEASE.md C-4 的答案失效,需重跑問卷);商店資訊標示「提供應用程式內購買」。
4. 設定 → 授權測試(License testing):加入測試帳號,購買走測試卡(不實際扣款)。

測試矩陣(封閉軌道上跑):

- 購買 → 殺 app → 離線開啟(快取權益仍 Premium)
- 恢復購買(重裝後)
- 月↔年升降級(proration)
- acknowledge 逾時行為(沙盒可加速驗證)
- test 環境的 account hold / grace period
- 退訂後:資料不鎖原則(§3-8)生效,僅擋新增類動作

### 5-7. 字串與法務

- `values/strings_billing.xml` + `values-zh-rTW/strings_billing.xml` **雙語同步落地**(⚠️ 勿重蹈 M27 的中文-only 覆轍)。
- `docs/` 新增**服務條款**頁:自動續訂條款、透過 Google Play 管理/取消/退款、價格變更通知。
- `docs/privacy.html` 加一行:「付款由 Google Play 處理,本 app 不接觸任何金流資料」。
- **Data Safety 表不變**(無新資料收集——購買狀態由 Play 持有)。

---

## 6. 里程碑總表

| 里程碑 | 範圍 | 估時 | schema | 前置依賴 |
|---|---|---|---|---|
| v1.0 正式版 | B5 聲明過審、14 天封測、生產資格、分階段發布 | ~2 週(日曆主導) | v17 | 無 |
| v1.1 家人成員 | member 表、memberId 三族、切換器、成員管理、HC owner-only、B6 LWW、備份/同步格式 | 3–4 週 | **v18** | v1.0 已發布(§2 理由 4) |
| v1.2 血糖 | glucose_reading、OCR、DataHub 切換、HC 血糖鏡像、聊天/報告整合 | 3–4 週 | **v19** | v18 落地;**健康聲明重送第一週就送** |
| v1.3 訂閱 | Billing 8.x、EntitlementManager、四個閘點、付費牆、促銷碼 | 2–3 週 + 灰度 1 週 | 不動 | v1.1+v1.2 功能存在;生產資格(測試軌道) |

---

## 7. 依賴與風險

1. ⚠️ **封測期間 schema 遷移風險** → v18 延至 v1.0 正式發布後才推(§2 理由 4)。緩解:`RoomMigrationTest` 17→18、18→19 全覆蓋,含空 user_profile 分支。
2. ⚠️ **健康聲明兩度送審的等待時間**:B5 初次申報(Phase 0)與 WRITE_BLOOD_GLUCOSE 增補(Phase 2)各需數天到數週。緩解:都在各自階段第一週提交,與開發並行。
3. ⚠️ **B6 LWW + memberId wire format → 配對入口開放的先後序**(§3-7):兩者完成前,配對入口不得走出 `BuildConfig.DEBUG`。
4. ⚠️ **IARC/商店資訊因 IAP 回填**(§5-6):忘記改會在 v1.3 送審時被擋。
5. **Billing 8.x 最低版本時限**:2026-08-31 起生效,v1.3 時程(約 2026-09)正好壓線——直接整 8.x,不碰 7.x。
6. **生產資格申請時點**:14 天 + 12 位測試者是硬條件(RELEASE.md J 節);所有後續階段都壓在 v1.0 過審之後,Phase 0 的日曆時間不可壓縮。

## 8. 不做清單(non-goals)

| 項目 | 理由 / 去處 |
|---|---|
| 跨裝置成員同步(外公手機 → 孫子手機) | 複雜度高;schema 已預留(member.hlcUpdatedAt、wire format 含 memberId),未來可加 |
| 血糖量測提醒 | v1.4 backlog;複用 MedicationReminderScheduler 模式 |
| 血糖背景異常 watcher | 避免重蹈 M17;就地警示卡已覆蓋低血糖場景 |
| Play Integrity / server-side 驗證 | 無後端、盜版風險面 ≈ 0(§5-2);未來有內建雲端額度才需要 |
| 後端伺服器 / 帳號系統 | 離線優先是產品定位;Billing 無後端可運作 |
| per-member 提醒開關、第 6 個 bottom tab、血糖獨立 PDF | YAGNI / UX 否決(§3-6、§4-3、§4-5) |
