## [0.5.0](https://github.com/SkillGodAk/Nivqo/commit/1d49a8224be6895070fc08a6ff5a90c93ffc0706) (2026-10-08)

### New Features

* **Facebook:** Nivqo Patches updates HushFacebook to 0.7.2-nivqo.1 with 70 patches. New controls include screenshots/screenshot detection, Instant Games ads, selectable hidden tabs, analytics uploads, Reels picture-in-picture, Marketplace seller profile access, HDR brightness, haptics and screen transitions, together with the formal v0.7.2 AI, ad, download/transcode, navigation, settings and compatibility changes. Nivqo Traditional Chinese core coverage is 609 / 609 and Manager Facebook metadata is 70 / 70.
* **Messenger:** Nivqo Patches keeps HushMessenger 0.21.0-nivqo.1 with 33 patches and 30 switchable controls, including the existing 580 / 581 support and Nivqo Traditional Chinese integration.
* **General:** Nivqo Patches 0.5.0 contains 103 total patch entries (Facebook 70 + Messenger 33). The combined build namespaces four duplicate Facebook/Messenger patch names without changing either standalone core.

## [0.4.0](https://github.com/SkillGodAk/Nivqo/commit/7598bc18d9bb8f6313a4a5c21e7d4ad51433a1c7) (2026-10-06)

### New Features

* **Facebook:** Nivqo Patches keeps HushFacebook 0.7.1-nivqo.1 with 60 patches and the existing Nivqo Traditional Chinese integration and Facebook / Messenger routing.
* **Messenger:** Nivqo Patches updates HushMessenger to 0.21.0-nivqo.1 with 33 patches and 30 switchable controls, adds Messenger 581.0.0.49.91 support alongside 580.0.0.49.91 for 37 arm64 builds total, adds Hide joined community chats, and expands AI sticker hiding and protected-media screenshot support. Nivqo Traditional Chinese settings coverage is 213 / 213 and Manager patch metadata coverage is 33 / 33.
* **General:** Nivqo Patches 0.4.0 contains 93 total patch entries (Facebook 60 + Messenger 33) and builds against Morphe Patcher 1.15.1 for Messenger compatibility.

## [0.3.0](https://github.com/SkillGodAk/Nivqo/commit/f627612ea08191d32a58f01addc28c27d32c4ce5) (2026-10-04)

### New Features

* **Facebook:** Nivqo Patches updates HushFacebook to 0.7.1-nivqo.1 with 60 patches, adds Facebook 581.0.0.45.58 / versionCode 475215365 support, and includes Force dark mode plus the v0.7.x Stories, Saved shortcut, supported-links, filtering, theme and save-flow improvements while preserving Nivqo Traditional Chinese and two-way Messenger routing.
* **Messenger:** Nivqo Patches keeps HushMessenger 0.14.0-nivqo.1 with 32 patches and its existing Nivqo Traditional Chinese integration.
* **General:** Nivqo Patches 0.3.0 contains 92 total patch entries (Facebook 60 + Messenger 32).

## [0.2.0](https://github.com/SkillGodAk/Nivqo/commit/e0e3d7c1b136dbfb792e24430d9885b6a60152f0) (2026-10-03)

### ✨ New Features

* **Facebook:** Nivqo Patches keeps HushFacebook 0.6.0-nivqo.1 with 59 patches.
* **Messenger:** Nivqo Patches updates HushMessenger to 0.14.0-nivqo.1 with 32 patches, including Native Bubbles, the optional chat slide animation, side-menu settings access, phone-native emoji rendering, and Nivqo Traditional Chinese settings/localization.
* **General:** The original Morphe Manager combined source is now Nivqo Patches 0.2.0 with 91 total patch entries (Facebook 59 + Messenger 32).

## [0.1.0](https://github.com/SkillGodAk/Nivqo/commit/3ab029fac57ce070fcf1aea6fc76a92db6aa9025) (2026-10-02)

### ✨ New Features

* **Facebook:** Nivqo Patches includes HushFacebook 0.6.0-nivqo.1 with 59 patches.
* **Messenger:** Nivqo Patches includes HushMessenger 0.7.0-nivqo.1 with 31 patches.
* **General:** Original Morphe Manager can add the combined Nivqo source with one repository URL: `https://github.com/SkillGodAk/Nivqo`.

# Changelog

## 2026-10-08 — v1.34.0 replacement / stable core 更新提示修正

### Manager replacement

- 修正 `0.7.2-nivqo.1`、`0.21.0-nivqo.1` 這類 Nivqo 正式 revision 被 `ChangelogParser` 誤判為 prerelease，導致 stable channel 把 core changelog 濾掉、首頁不顯示「更新／重新修補」的問題。
- `-nivqo.N` 現在正確視為 stable revision；`dev` / `beta` / `rc` 等仍維持 prerelease。
- 已驗證 HushFacebook `0.7.1-nivqo.1 -> 0.7.2-nivqo.1` 會被判定為新版，且 Facebook scoped changelog 可觸發重新修補提示。
- Manager 顯示版本維持 `1.34.0`；update transport 提高至 `1.34.2`，VersionCode 提高至 `39866739`。
- APK SHA-256：`AB7EEDBD404985532948A9800C9771B1094AC471262E3D7C533208A6E21064F6`。
- Signer SHA-256 維持：`FDEC7E04562314AB6CB90981AA7C88F5AFC145F65264D2D359E331F7A22E9D29`。

## 2026-10-08 — v1.34.0 replacement / HushFacebook 0.7.2

### Facebook 核心

- HushFacebook 更新到 `0.7.2-nivqo.1`，只同步上游正式 `v0.7.2` / commit `4c102f39fd80e41c9f14b346885e8d7959d0b930`。
- Facebook 核心由 60 增至 70 patches；核心繁中覆蓋 `609 / 609`，Manager patch metadata `70 / 70`。
- 保留 Nivqo 語言選擇與 Facebook 頂部 Messenger 圖示雙向路由。
- HushMessenger 維持 `0.21.0-nivqo.1` / 33 patches。
- Nivqo Patches 更新到 `0.5.0`，Facebook 70 + Messenger 33 = `103 patches`。

### Manager replacement

- Manager 顯示版本維持 `1.34.0`，更新 transport 為 `1.34.1`，VersionCode 提高到 `39866700`。
- 修正離線首次啟動 Messenger seed 路徑仍指向 `0.14.0` 的歷史問題；內建 seed 現為 HushFacebook 0.7.2 + HushMessenger 0.21.0。
- APK SHA-256：`542DD647947EEEFF58E5854091A85026F1CE3BF3210E79C51592A00CC4B938F1`。
- Signer SHA-256 維持：`FDEC7E04562314AB6CB90981AA7C88F5AFC145F65264D2D359E331F7A22E9D29`。


## 2026-10-06 — v1.34.0

### Manager 同步

- Nivqo Manager 基底由 Morphe Manager `1.33.0` 同步到正式 `1.34.0` / commit `a0e19e5e2d3c2cdc5172d760a2699a079d08b430`。
- Morphe Patcher 同步到正式 `1.15.1` / commit `812e96eaac173cd058ba49e242325371959befae`。
- 保留 Nivqo package `app.nivqo.manager`、GitHub Manager 更新通道、HushFacebook / HushMessenger 遠端核心、自有繁中 metadata、WorkManager 背景更新與 Firebase / Google Services 移除。
- 跟進 1.34.0 的內建更新流程、私人 GitHub patch source + PAT、重新簽章 App Links 引導／還原、首頁卡片與 changelog/update badge 修正，以及 patch source / patcher / installer 效能與穩定性改善。

### 核心

- HushFacebook 維持 `0.7.1-nivqo.1` / 60 patches。
- HushMessenger 更新為 `0.21.0-nivqo.1` / 33 patches，支援 Messenger 580 / 581 共 37 個 arm64 builds，新增「隱藏已加入的社群聊天」，並擴充 AI 貼圖隱藏與受保護媒體截圖。
- HushMessenger 核心設定繁中覆蓋 `213 / 213`；Manager patch metadata `33 / 33`。
- Nivqo Patches 更新為 `0.4.0`，Facebook 60 + Messenger 33 = 93 patches。

### APK

- `Nivqo-Manager-1.34.0-20261006-release.apk`
- Version code：`39864046`
- APK SHA-256：`846A424CB72A31A9CBE2FD6D66FC9AC92571BFB1DBED467D39D1CD7F7DB44DEB`
- Signer SHA-256：`FDEC7E04562314AB6CB90981AA7C88F5AFC145F65264D2D359E331F7A22E9D29`


## 2026-10-02 — HushFacebook 0.6.0 + v1.33.0 replacement

### HushFacebook

- 同步上游正式 Release v0.6.0 / commit 22ae40c9c4ab574c0b36caed28b31ae608359446；不納入 v0.6.0 發布後的 main commit。
- Nivqo 核心版本更新為 0.6.0-nivqo.1，bundle 共 59 個 patch。
- 相較 Nivqo 前一版新增 5 個 patch metadata：隱藏貼文下方的 Meta AI 問題、隱藏 Feeds 標題列、保留貼文日期、支援 x86 裝置啟動、分頁列置底。
- 保留 Nivqo 的繁體中文／English 介面、介面語言選擇，以及 Facebook 頂部 Messenger 圖示雙向路由。
- HushFacebook Manager patch metadata 繁中覆蓋更新為 59 / 59。
- 正式 bundle SHA-256：E6B35A3E1692C8A873ADD2BD67894FA051119D9E20B7B0EDAAF403F906B6E538。

### Manager replacement

- Manager 顯示版本維持 1.33.0，同版 replacement 內建 HushFacebook 0.6.0-nivqo.1 離線 seed。
- 修正 HushFacebook 更新 manifest 若帶 UTF-8 BOM 時，Manager 解析失敗並在「無法下載變更紀錄」畫面顯示整段 raw JSON 的問題。
- JSON 更新回應現在會在解析前移除 BOM；raw.githubusercontent.com 的 bundle manifest 與 changelog 請求也加入每分鐘 cache-buster，避免發版後仍讀到舊 CDN 快取。
- Repo 加入公開更新 JSON pre-push 驗證：BOM、無效 JSON 或兩份 app-release.json 不一致時拒絕 push。
- 修正首頁「重新修補」更新標記只比較 MPP 內部 base version 的問題；遠端核心現在會記錄完整 release signature，例如 `0.6.0-nivqo.1`。
- 同一上游 base 的 Nivqo revision 也會被視為更新，例如 `0.6.0 < 0.6.0-nivqo.1 < 0.6.0-nivqo.2`，因此 Facebook 與 Messenger 的首頁更新提示邏輯一致。
- 新 versionCode：39858618；可直接覆蓋既有 v1.32.0 與舊 v1.33.0。
- APK SHA-256：0FA12110549EA855F30FEE395A956791EF850506CAA18C3EE10E58F7B8978B62。
- APK 簽章憑證維持不變。

## 2026-10-01 — v1.33.0

### Manager 同步

- Nivqo Manager 基底由 Morphe Manager `1.32.0` 同步到正式 `1.33.0`。
- Morphe Patcher 同步到正式 `1.15.0`。
- 保留 Nivqo 自己的 package `app.nivqo.manager`、GitHub Manager 更新通道、HushFacebook / HushMessenger 遠端核心更新、WorkManager 背景更新與 Firebase / Google Services 移除。
- 跟進 Morphe 1.33.0 的新版 UI、App 語言系統、內容翻譯、patcher / split 合併效能與新版 patching flow。
- Nivqo 固定繁中 patch metadata 已調整為配合 Morphe 1.33.0 的 App 語言；其他語言仍使用 Morphe 1.33.0 原生內容翻譯流程。
- v1.33.0 覆蓋版完成 Manager 全 APK 品牌巡檢：使用者可見的 Manager 品牌統一為 Nivqo；Morphe 僅保留於上游來源標示、Credits、LICENSE／NOTICE 與必要技術相容識別。
- Launcher／About 圖示改為 Nivqo N，保留原 v1.33 的背景與藍綠配色；`Morphe Patches（上游）` 改用中性來源圖示。
- Manager 更新加入 `version_code` 握手；v1.32.0、舊 v1.33.0 與前一個 v1.33 replacement 均可升級到最新覆蓋版，最新覆蓋版本身不會重複提示自己更新。

### 繁體中文修正

- HushFacebook 現行 54 個 patch：名稱／說明繁中覆蓋 `54 / 54`。
- HushMessenger 現行 31 個 patch entry；核心設定文字繁中覆蓋已補齊，v1.33.0 replacement APK 也已更新，Manager 內名稱／說明繁中覆蓋 `31 / 31`。
- 修正先前更新 core 後，Manager 新增 patch 項目仍顯示英文的問題。

### 核心

- HushFacebook 現行核心為 `0.5.0-nivqo.2`，恢復「關閉外部 Messenger」時直接開啟 Facebook 內建 Chats / InboxActivity，略過 Meta 的 Messenger 跳轉詢問。
- HushMessenger 更新為 `0.7.0-nivqo.1`，只同步上游正式 Release `v0.7.0` / commit `1756352d6884c88122f73d5e212180abdd75f517`；未納入 v0.7.0 發布後的 `main` commit。新增 Material You 主題、匿名觀看限時動態、儲存任何限時動態，並擴充好友建議／成長提示隱藏範圍。
- 兩個核心的內建離線 seed 與公開更新 bundle SHA-256 已一致驗證。

## 2026-09-30 — Core channel update

Manager 版本維持 `v1.32.0`；本次只更新可由 Nivqo 自動取得的插件核心。

### HushFacebook

- 更新到 HushFacebook `0.5.0`，跟進上游 main `6d312196bc9ae8a7c9bd77f936c60b6e6de579d2`。
- 最新 bundle 共 54 個 patch。
- 移除 Nivqo 舊的 Facebook → Messenger 自製跳轉 hook，改用上游正式 `Open the Messenger app` 開關。
- 上游新增／改寫的可見設定文字已補齊繁體中文；繁中表涵蓋 490 個上游字串，另含 5 個 Nivqo 語言選擇字串。
- Nivqo 核心通道版本：`0.5.0-nivqo.1`。

### HushMessenger

- 更新到 HushMessenger `0.6.0`，跟進上游 main `4b259a712e1b3c90edb7d0262d2b7d23bf45b093`。
- 最新 `patches-list.json` 共 28 個 patch entry。
- 支援 Messenger `580.0.0.49.91` 的 21 個 arm64 `versionCode`。
- 移除 Nivqo 舊的 `SettingsEntry / SettingsEntryHooks`，改用上游正式的 Messenger「選單」列、Patch 控制快捷鍵與「隱藏應用程式清單圖示」。
- 語言切換改為選項式：先選「跟隨系統 / 繁體中文 / English」，按「套用」後才切換，不再點一下直接輪切。
- 最新設定文字繁中完整性：157 / 157 key，placeholder 0 錯誤。
- Nivqo 核心通道版本：`0.6.0-nivqo.1`。

## 2026-09-29 — v1.32.0

- HushFacebook / HushMessenger 改用 Nivqo GitHub 遠端更新通道。
- 插件核心預設啟用自動更新。
- APK 內建 `.mpp` 改為離線／首次啟動 seed，不再覆蓋遠端更新後的核心。
- Nivqo Manager 啟用自我更新，來源改為 `SkillGodAk/Nivqo` 的 `app-release.json` 與 GitHub Releases。
- 移除原 Morphe Firebase / Google Services 設定；Nivqo 背景更新通知改由 WorkManager 輪詢，不再包含或使用原專案的 Google API key。

## 初始公開內容

首個 Nivqo 公開版本。

### Manager

- 正式品牌改為 Nivqo。
- 正式 package 改為 `app.nivqo.manager`。
- 內建 HushFacebook / HushMessenger patch bundle。
- 繁體中文 / English 使用流程。
- 不預置作者私人 patch signing key。
- Manager 更新來源改為 Nivqo 自己的 GitHub 更新通道，避免與原始 Morphe 更新來源混用。

### Facebook

- HushFacebook 繁體中文化。
- 保留影片 / Reels / Stories 下載等上游功能。
- Facebook / Messenger 跳轉控制。
- Facebook 設定頁整合 HushFacebook 設定入口。

### Messenger

- HushMessenger 繁體中文化。
- HushMessenger 設定入口直接整合在 Messenger 內。
- 支援 Messenger 580.0.0.49.91 的 arm64 versionCode：
  - `346013387`
  - `346013440`
  - `346013442`

### 相容性

Facebook：

- 580.0.0.51.74 — `475019344`
- 577.0.0.50.72 — `474426275`

使用者必須確認 `versionCode`，不能只看 versionName。