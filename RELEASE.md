# SilverBP — 上架發布指南(逐步操作版)

建置、簽章、上架 Google Play 的完整操作手冊。本文件不含任何機密 —
所有密鑰都放在 `local.properties`(已被 git 忽略)。

> 開發人員帳戶於 2026-06-11 註冊(2023-11-13 之後建立的個人帳戶),
> 因此**適用封閉測試規定**:至少 12 位測試者連續加入滿 14 天,
> 才能申請生產(正式)發布資格。Google 帳號本身的建立日期無關。

---

## 時間軸總覽

| 時間 | 要做的事 | 對應章節 |
|---|---|---|
| 第 0 天(今天) | 帳戶身分驗證、建立 app、提交健康聲明 + 前景服務聲明、建 Google Group 開始募測試者 | A、B、C-8、C-9、G |
| 第 0–3 天 | 填完 App content 全部表單、商店資訊、建 AAB 上內部測試自測 | C、D、E、F |
| 第 3–17 天 | 封閉測試 14 天:12+ 位測試者保持加入、跑冒煙清單、盯報告 | G、H、I |
| 第 17 天起 | 申請生產發布資格 → 通過後分階段發布 10%→50%→100% | J、K |
| 上線後 | 監控 Crashes & ANRs、處理 v1.1 清單 | L、M |

---

## A. 開發人員帳戶(已繳費,完成剩餘驗證)

1. 開 [play.google.com/console](https://play.google.com/console) 登入繳費用的 Google 帳號。
2. 左側若出現「**驗證身分**」橫幅:準備**政府核發證件**(護照/身分證)照片與
   **地址證明**,照畫面指示上傳。個人帳戶通常 1–3 天過。
3. Console 首頁 → 右上角設定(齒輪)→「開發人員帳戶」→「帳戶詳細資料」:
   確認開發人員名稱(會顯示在商店上,例如 `Silver`)、聯絡 email、電話已驗證。
4. 注意:身分驗證沒過之前不能發布任何軌道,先把這關清掉。

## B. 建立應用程式

1. Play Console 首頁 →「**建立應用程式**」。
2. 逐欄填:
   - 應用程式名稱:`SilverBP`(之後可改)
   - 預設語言:`中文(繁體)– zh-TW`
   - 應用程式或遊戲:**應用程式**
   - 免費或付費:**免費**(⚠️ 發布後不能改成付費)
3. 勾兩個聲明(開發人員計畫政策、美國出口法),按「建立應用程式」。

## C. App content(應用程式內容)— 全部表單逐項

路徑:左側選單最下方 →「**政策**」→「**應用程式內容**」。每一項點「開始」或「管理」。

### C-1. 隱私權政策

1. 先確認 GitHub repo → Settings → Pages 已啟用(Source: `main` / `/docs`)。
2. 瀏覽器開 `https://<你的帳號>.github.io/<repo>/privacy.html` 確認看得到。
3. 把這個 URL 貼進「隱私權政策網址」→ 儲存。

### C-2. 應用程式存取權(App access)

1. SilverBP 的核心功能**不需登入**(Google 登入可「稍後再說」跳過),
   選「**所有功能均可在沒有特殊存取權的情況下使用**」。
2. 但審查員若要測 Drive 備份需要 Google 帳號 — 保險作法:選「全部或部分功能受限」,
   新增一組說明:「Google 登入僅用於選用的雲端備份,可點『稍後再說』跳過;
   核心血壓/運動記錄功能無需帳號」。兩種擇一,前者較簡單。

### C-3. 廣告

- 「您的應用程式是否包含廣告?」→ **否**。

### C-4. 內容分級(IARC 問卷)

1. 輸入聯絡 email → 類別選「**公用程式、生產力、通訊或其他**」。
2. 問卷全部據實答 **否**(無暴力、無性、無毒品、無賭博、無粗話、
   無使用者生成內容、不分享位置給其他使用者、無數位商品購買)。
3. 送出後立刻拿到各區分級(普遍級/3+),按「儲存」。

### C-5. 目標對象與內容

1. 目標年齡層:勾 **18 歲以上**(健康管理工具;**不要**勾任何未成年,
   勾了會觸發家庭政策審查)。
2. 「應用程式是否會吸引兒童?」→ 否。

### C-6. 新聞應用程式 / COVID-19 / 政府應用程式 / 金融功能

- 全部選 **否**(金融功能:「我的應用程式不提供任何金融功能」)。

### C-7. 資料安全(Data Safety)— 逐頁操作

1. 「概覽」頁按「開始」。
2. **資料收集與安全性**:
   - 「是否收集或分享必要的使用者資料?」→ **是**
   - 「資料是否在傳輸過程中加密?」→ **是**(HTTPS/Noise XK/加密備份)
   - 「是否提供刪除資料的方式?」→ **是**(app 內可刪記錄、解除連結可刪雲端備份)
3. **資料類型**頁,勾選以下並逐一設定(每項都填:已收集 ✓ / 未分享 /
   可選提供 / 用途「應用程式功能」):

   | 類別 → 資料類型 | 收集 | 分享 | 必要或可選 | 備註 |
   |---|---|---|---|---|
   | 健康與健身 → 健康資訊 | ✓ | ✗ | 必要(核心功能) | 血壓、運動、睡眠、營養 |
   | 位置 → 精確位置 | ✓ | ✗ | 可選(GPS 運動才用) | 僅裝置端 |
   | 相片和影片 → 相片 | ✓ | ✓* | 可選 | *僅當使用者自選 Gemini 雲端 OCR 時傳 Google API |
   | 個人資訊 → 電子郵件地址 | ✓ | ✗ | 可選(Drive 備份才用) | 可跳過登入 |
   | 應用程式活動 → 其他動作 | ✓ | ✗ | 必要 | 用藥、飲食打卡等記錄 |

   - 語音:**不勾**(系統語音辨識即時轉文字,app 不儲存音訊)。
4. 每種類型的「資料用途」勾「**應用程式功能**」即可,不勾廣告/分析。
5. 「資料是否由使用者主動提供或自動收集」:健康資料選兩者
   (手動輸入 + Health Connect 背景讀取)。
6. 預覽 → 提交。

### C-8. 健康應用程式聲明 ⚠️ 最優先,審核數天到數週

路徑:政策 → 應用程式內容 →「**健康應用程式**」→ 開始。

1. 「您的應用程式是否屬於健康應用程式?」→ **是**。
2. 功能類別:勾「**健康與健身**」(不要勾「醫療」— 會要求醫療器材認證)。
3. 出現 Health Connect 權限清單,**逐項填使用理由**(可直接用下面文字):
   - `WRITE_BLOOD_PRESSURE`:「使用者手動或拍照記錄的血壓值,經其同意鏡像寫入 Health Connect,供其他健康 app 共用。」
   - `WRITE_EXERCISE` / `WRITE_EXERCISE_ROUTE`:「使用者主動開始的運動(走路/跑步)及其 GPS 路線,經同意寫入 Health Connect。」
   - `WRITE_NUTRITION`:「使用者記錄的飲食(熱量、鈉等),經同意寫入 Health Connect。」
   - `READ_STEPS`:「讀取每日步數,用於步數目標獎章與連續達標統計。」
   - `READ_SLEEP`:「讀取睡眠時數,用於生活教練的睡眠目標追蹤。」
   - `READ_NUTRITION`:「讀取營養(鈉)資料,用於高血壓飲食教練的鈉攝取追蹤。」
   - `READ_HEALTH_DATA_IN_BACKGROUND`:「以排程工作在背景回補缺漏日的步數/睡眠/營養,
     讓趨勢保持最新;資料僅在裝置端處理與儲存,絕不上傳開發者伺服器。」
4. 隱私政策 URL 會被比對 — 我們的 `docs/privacy.html` 已含對應揭露,直接過。
5. 提交。狀態會顯示「審核中」;**通過前不能正式發布**(測試軌道通常可以)。

### C-9. 前景服務權限聲明 ⚠️ 需要影片

路徑:政策 → 應用程式內容 →「**前景服務權限**」(或在「敏感應用程式權限」內)。

1. **先錄影片**(30 秒,手機螢幕錄影即可):
   開 SilverBP → 運動頁 → 按「開始」→ 顯示前景通知與地圖軌跡 → 按「結束」。
2. 上傳 YouTube,權限設「**不公開**(unlisted)」,複製連結。
3. 表單中 `FOREGROUND_SERVICE_LOCATION` →
   - 用途說明:「使用者主動開始 GPS 運動(走路/跑步)時,前景服務持續記錄位置
     以繪製路線與計算距離;結束運動即停止。服務僅由使用者明確操作啟動。」
   - 影片連結:貼 YouTube URL。
4. 提交。

## D. 商店資訊(Store listing)

路徑:左側「**拓展使用者群**」→「商店發佈資訊」→「主要商店發佈資訊」。

1. **預設(zh-TW)**填:
   - 應用程式名稱(≤30 字):`SilverBP 銀髮血壓管家`(範例,自行決定)
   - 簡短說明(≤80 字):`拍照記血壓、GPS 運動、用藥提醒與 AI 健康教練,專為長輩設計的血壓管理 App。`
   - 完整說明(≤4000 字):列功能(拍血壓計自動辨識、運動追蹤、重訓、飲食條碼、
     用藥提醒、週報、加密備份)。**禁用字眼:治療、診斷、醫療**;
     結尾放免責:「本應用程式僅供記錄與參考,非醫療器材,不提供醫療診斷。」
2. 「**新增翻譯**」→ `英文 (美國) – en-US` → 填英文版三欄。
3. **圖像資源**(同頁下方):
   - 應用程式圖示:512×512 PNG(≤1MB)
   - Feature graphic(主題圖片):1024×500 PNG/JPG
   - 手機螢幕截圖:**至少 2 張**(建議 4–8 張:今日頁、拍血壓計、運動地圖、
     Coach、趨勢圖),9:16、每邊 320–3840px
   - 7 吋/10 吋平板截圖:可先略過(沒有平板版型就不傳)
4. 儲存。

## E. 建置 AAB 並上傳「內部測試」

### E-1. 建置

```sh
# local.properties 需有 KEYSTORE_PATH / KEYSTORE_PASS / KEY_ALIAS / KEY_PASS / MAPS_API_KEY
./gradlew :app:testDebugUnitTest          # 測試全綠
./gradlew :app:bundleRelease              # 產出 AAB
# 產出:app/build/outputs/bundle/release/app-release.aab
```

### E-2. 上傳到內部測試軌道

1. 左側「**測試與發布**」→「測試」→「**內部測試**」→「建立新版本」。
2. 第一次會問 **Play 應用程式簽署**:選「使用 Google 產生的金鑰」(預設)→ 繼續。
   你的 keystore 從此是「上傳金鑰」,Google 持有「應用程式簽署金鑰」。
3. 拖入 `app-release.aab` → 版本名稱自動帶 `1 (1.0)` → 填版本資訊(發布備註,
   zh-TW 隨意寫「首個內部測試版」)→「下一步」→「儲存並發布」。
4. 「**測試人員**」分頁 → 建立 email 名單(先放自己 + 家人 2–3 個)→ 儲存
   →「**複製連結**」把加入連結傳給自己 → 手機點開 → 「成為測試人員」→ 從 Play 商店安裝。
5. 自己先跑一輪第 I 節的冒煙清單。

## F. Maps API key 補 App signing SHA-1(第一次上傳後立刻做)

1. Play Console → 左側「**測試與發布**」→「設定」→「**應用程式簽署**」
   (Setup → App integrity)。
2. 複製「**應用程式簽署金鑰憑證**」的 **SHA-1**。
3. 開 [console.cloud.google.com](https://console.cloud.google.com) → API 和服務 → 憑證 →
   點 Maps 的 API key → Android 應用程式限制 → 「新增」→
   套件名稱 `com.silverbp.android` + 剛複製的 SHA-1 → 儲存。
4. 用內部測試裝的版本開運動地圖,**確認地圖有畫面**(灰白一片 = 指紋沒加對)。

## G. 封閉測試:建 Google Group + 開軌道

### G-1. 建立 Google Group(測試者管理最省力的方式)

1. 開 [groups.google.com](https://groups.google.com) →「**建立群組**」。
2. 填:
   - 群組名稱:`SilverBP Testers`
   - 群組電子郵件:`silverbp-testers`(會變成 `silverbp-testers@googlegroups.com`)
   - 群組說明:「SilverBP 封閉測試群組」
3. 「誰可以加入群組」→ 選「**任何人都可以要求加入**」
   (或「受邀使用者」然後你手動加 email — 前者讓測試者自助加入,較省事)。
4. 「誰可以查看對話」→ 群組成員。建立。

### G-2. 開封閉測試軌道

1. Play Console →「測試與發布」→「測試」→「**封閉測試**」→ 預設軌道
   「Alpha」→「管理軌道」→「建立新版本」。
2. 上傳同一個 AAB(內部測試用過的版本可直接「從程式庫新增」)→ 儲存。
3. 「**測試人員**」分頁 → 選「**Google 群組**」→ 貼 `silverbp-testers@googlegroups.com` → 儲存。
4. 「國家/地區」→ 至少勾 **台灣**(要含所有測試者所在地;可加美國/日本等)。
5. 「發布版本」→ 審核通過後(通常數小時~1 天),回到測試人員分頁:
   - 複製「**網頁加入連結**」:`https://play.google.com/apps/testing/com.silverbp.android`
   - Play 商店連結:`https://play.google.com/store/apps/details?id=com.silverbp.android`

### G-3. 測試者的加入流程(寫給測試者看的,三步)

1. 用自己的 Google 帳號加入群組:`https://groups.google.com/g/silverbp-testers`
   (點「要求加入群組」,群組主人核准)。
2. 點網頁加入連結 `https://play.google.com/apps/testing/com.silverbp.android`
   → 按「**成為測試人員**」。
3. 點 Play 商店連結安裝 app。**14 天內請不要退出測試或解除安裝。**

## H. 招募測試者(邀請文範本)

需要 **12 位**(建議湊 13–15 位留餘裕)。管道:家人朋友群組、FB 社團
(Android 台灣、app 互測社團)、PTT Android 板、Reddit r/TestMyApp、
「互測群」(注意:用互測群時自己也要幫別人測)。

可直接貼的邀請文(仿你看過的格式,自行調整):

> 【測試邀請】幫家裡長輩做的血壓管理 App,找 15 位測試者 🙏
>
> 大家好!我開發了一款專為長輩設計的血壓管理 App —— **SilverBP**。
>
> 不只是記血壓:
> 📷 **拿手機拍血壓計**,AI 自動辨識數值,長輩不用打字
> 🚶 GPS 散步/跑步追蹤 + 重訓記錄,運動前還會看血壓幫你把關
> 💊 用藥提醒、飲食鈉攝取追蹤(掃條碼就好)
> 🤖 內建 AI 健康教練,每週幫你排運動計畫、寫週報
> 🔒 資料全部存手機本機,可加密備份到自己的 Google Drive
>
> 目前正在進行 Google Play 封閉測試,需要真實使用者的回饋
> (尤其想知道:字夠不夠大?長輩操作會卡在哪?)。
>
> 👉 參與測試三步驟:
> 1️⃣ 加入 Google 群組(必要,才有測試權限):
> 🔗 https://groups.google.com/g/silverbp-testers
> 2️⃣ 點這個連結按「成為測試人員」:
> 🔗 https://play.google.com/apps/testing/com.silverbp.android
> 3️⃣ 從 Play 商店安裝:
> 🔗 https://play.google.com/store/apps/details?id=com.silverbp.android
>
> ⚠️ 小拜託:Google 規定測試者要**連續保持加入 14 天**,
> 安裝後請不要退出測試或解除安裝,偶爾打開記一筆血壓或散個步就超有幫助!
> 有任何 Bug 或建議請直接在群組留言或私訊我,感謝每一位幫忙的朋友 🙏

## I. 封測 14 天期間做什麼

1. **冒煙測試清單**(對應 2026-06 三波修復的高風險路徑,自己 + 請測試者抽測):
   - 全新安裝 → onboarding → **不登入 Google 按「稍後再說」** → 能進主畫面
   - 拍血壓計 → 旋轉螢幕 → 數值與照片還在 → 儲存(連點兩下)→ 歷史只有一筆
   - 開始 GPS 運動 → 殺掉 app → 重開 → 恢復卡 → 繼續
   - 結束運動 → 摘要頁 → 殺掉 app → 重開 → 直接進摘要可儲存
   - 重訓記幾組 → 按系統返回 → 再點開始 → 出現「繼續上次訓練」對話框
   - 掃只有每 100g 營養資料的條碼 → 確認頁出現換算/每 100g 提示
   - 設定 → 開啟 app 鎖(觸發加密遷移)→ 強制重開 → 正常解鎖、資料完整
   - 備份 → 匯出 → 「取代」匯入 → 中途按返回 → 資料完整(交易回滾)
   - 正式簽章版:運動地圖有顯示
2. **盯兩個報告**(每 2–3 天看一次):
   - 「測試與發布」→「Pre-launch report」:robo 自動爬出的 crash/無障礙問題
   - 「品質」→「Android vitals」→「Crashes & ANRs」
3. **維持測試者數量**:「封閉測試」頁會顯示目前 opted-in 人數,
   低於 12 立刻補人(退出再加入會重算該人的 14 天)。
4. 期間修 bug 可隨時上新版本到同軌道(versionCode +1),**不會重置 14 天**
   (14 天算的是測試者加入時長,不是版本)。

## J. 申請生產(正式)發布資格

1. 14 天 + 12 人達標後,「儀表板」會出現「**申請正式版存取權**
   (Apply for production)」按鈕。
2. 問卷誠實詳答(用我們的素材):
   - 「招募了哪些測試者?」→ 家人朋友 + Android 社群,共 N 位,涵蓋長輩實際使用情境
   - 「測試期間發現並修正了什麼?」→ 引用 `notes/prerelease-audit-2026-06-11.md`:
     多 agent 程式碼審查找出 87 項問題,上架前修復了相機權限崩潰、GPS 服務崩潰、
     備份還原原子性、資料庫加密遷移防護等(挑 3–5 個具體例子寫)
   - 「你的 app 已準備好的理由?」→ 單元測試 + 裝置冒煙清單 + pre-launch report 乾淨
3. 送出後 Google 審核(通常數天)。被拒會附原因,補測後可再申請。

## K. 正式發布(分階段)

1. 「測試與發布」→「**正式版**」→「建立新版本」→ 上傳 AAB(或從程式庫選)。
2. 確認 C 節所有表單都是「已完成」、健康聲明已**通過**。
3. 「發布版本」→ 選「**分階段發布**」→ 首階段 **10%**。
4. 觀察 1–2 天 Crashes & ANRs 無異常 → 回同頁把比例調 **50%** → 再 1–2 天 → **100%**。
5. 中途出大問題:「暫停階段發布」→ 修復 → versionCode+1 上新版接續。

## L. 發布之後

- 盯 Play Console →「品質」→ **Crashes & ANRs**;堆疊用 AAB 隨附的
  `mapping.txt` 自動反混淆(R8 已開;`-keepattributes SourceFile,LineNumberTable` 已設)。
- 回覆商店評論(高齡族群常把操作問題寫在評論)。
- 每次更新:versionCode+1 → 走內部 → 封閉 → 正式的同流程(已有生產資格,不用再 14 天)。

## M. 程式碼面剩餘事項(v1.1,詳見 notes/prerelease-audit-2026-06-11.md)

- [ ] LAN 同步補 LWW merge + tombstone 後重新開放配對入口(現藏於 `BuildConfig.DEBUG`)
- [ ] 本地化補齊:6 個中文-only 字串檔、ViewModel 內寫死的錯誤訊息
- [ ] Chat 模型下載入口、「去量血壓」按鈕導航、器材 OCR 欄位(卡路里/心率/樓層)顯示
- [ ] 重訓 process-death checkpoint、`HealthConnectBpBridge` 的 clientRecordVersion 修復、
      `EncryptedSharedPreferences` 棄用 API 遷移

---

## 附錄一:一次性設定 — 簽章 keystore

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

## 附錄二:一次性設定 — 限制 Maps API key

Google Cloud Console → 憑證 → 該 Maps key:

- 應用程式限制:**Android 應用程式**。
- 加入**套件名稱** `com.silverbp.android` + debug 與 release/upload 兩把金鑰的 **SHA-1**:
  ```sh
  keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey   # debug
  keytool -list -v -keystore ~/keystores/silverbp-upload.jks -alias upload        # release
  ```
  使用 Play App Signing 後,還要加 **App signing key** 的 SHA-1(見 F 節)。

`MAPS_API_KEY` 留空時 release 建置(`assembleRelease`/`bundleRelease`)會**直接失敗**
— 見 `app/build.gradle.kts`。

## 附錄三:每次發布前檢查

1. `app/build.gradle.kts` 調升 `versionCode`(每次上傳必 +1)與 `versionName`。
2. Room schema 有變動時:調升 `SilverBpDatabase.kt` 的 `version`、新增 `MIGRATION_*`,
   並確認新的 `app/schemas/.../<n>.json` 已提交。
3. 測試全綠:
   ```sh
   ./gradlew :app:testDebugUnitTest
   ./gradlew :app:connectedDebugAndroidTest   # 需要裝置/模擬器;跑 RoomMigrationTest、DbCipher…
   ```

## 附錄四:備註

- 開啟靜態加密時,`data_extraction_rules.xml` / `SilverBpBackupAgent` 會把
  SQLCipher 資料庫排除在雲端備份之外 — schema 變動後請再驗證一次。
- 隱私政策由 `/docs` 透過 GitHub Pages 提供;`BuildConfig.PRIVACY_POLICY_URL`
  注入網址,翻譯時不會弄壞連結。
- Play Console 介面文字隨版本微調,找不到選單時用左側搜尋框搜關鍵字
  (如「健康」「前景服務」「資料安全」)。
