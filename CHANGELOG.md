# Changelog

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
- HushMessenger 現行 31 個 patch entry：名稱／說明繁中覆蓋 `31 / 31`；核心設定文字繁中覆蓋也已補齊。
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