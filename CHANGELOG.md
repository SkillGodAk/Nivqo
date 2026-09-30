# Changelog

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