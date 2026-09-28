# Nivqo

**繁體中文** | [English](README_EN.md)

Nivqo 是一個以 Facebook / Messenger 中文化與功能整合為主的第三方開源專案。專案基於 Morphe Manager、HushFacebook 與 HushMessenger 進行調整，重點是繁體中文介面、Facebook / Messenger 使用體驗整合，以及將 HushMessenger 設定直接整合到 Messenger 內。

這個專案成立的主要原因，是目前原專案尚未提供完整的中文支援，因此額外整理了繁體中文介面與相關使用體驗，讓中文使用者可以更方便地使用 Facebook / Messenger 的相關插件功能。

Nivqo 的目的不是取代原專案，而是補足目前中文使用者需要的功能與介面。**如果未來原專案正式支援中文插件內容，並且 HushMessenger 的設定與功能也正式內建在 Messenger 裡，本專案將停止維護並刪除／廢止。**

> Nivqo 是獨立第三方專案，並非 Meta、Facebook、Messenger、Morphe 或 SysAdminDoc 官方產品。Facebook、Messenger、Meta、Morphe 等名稱僅用於說明相容性與上游來源。

## 主要內容

### Facebook

- HushFacebook 繁體中文 / English 介面
- Facebook 影片、Reels、Stories 等媒體下載功能
- 隱藏贊助、推薦與部分干擾內容
- 可調整動態消息、通知、播放、外觀、下載等功能
- Facebook 設定頁內整合 HushFacebook 設定入口
- Facebook / Messenger 跳轉控制
- 保留 HushFacebook 原有功能並持續跟進相容版本

### Messenger

- HushMessenger 繁體中文 / English 介面
- HushMessenger 設定直接整合在 Messenger 內
- 長按 Messenger 首頁左上角 Messenger 標誌可開啟設定
- 去除 / 隱藏部分推薦、廣告與干擾內容
- Messenger 個人化與聊天相關控制
- 可與對應的 Facebook 修補版配合使用

## 重要：版本號必須完全正確

**請特別確認 APK 的 `versionCode`，不能只看 Facebook / Messenger 顯示的版本名稱。**

Meta 可能在同一個版本名稱下發布多個不同 build。它們的 DEX / 資源內容可能不同，所以即使畫面上看起來都是同一個版本，`versionCode` 不同仍可能造成：

- 補丁失敗
- 找不到補丁目標
- 部分功能失效
- 安裝後閃退或行為異常

請使用下列已支援的 build。

### Facebook

| 版本名稱 | 支援的 versionCode | 架構 / 備註 |
| --- | ---: | --- |
| 580.0.0.51.74 | `475019344` | arm64-v8a，原作者目前主要測試 build |
| 577.0.0.50.72 | `474426275` | arm64-v8a，相容 build |

### Messenger

| 版本名稱 | 支援的 versionCode | 架構 |
| --- | --- | --- |
| 580.0.0.49.91 | `346013387` / `346013440` / `346013442` | arm64-v8a |

> **versionName 一樣不代表一定能用。請以 versionCode 為準。**  
> 如果 Manager 顯示版本不支援，先確認你取得的原始 APK 是否正好是上表的 build，不要只看「580.0.0.xx.xx」。

## 使用方式

1. 安裝 Nivqo Manager。
2. 準備與上方 `versionCode` 完全相符的原始 Facebook 或 Messenger APK。
3. 在 Manager 中選擇 Facebook / Messenger 與要套用的功能。
4. 第一次修補時會建立你自己的 APK 簽署金鑰。
5. 之後要覆蓋更新同一個修補 App，必須繼續使用同一把簽署金鑰。

### 簽署金鑰

每位使用者的修補 APK 都應由自己的 Manager 金鑰簽署。

- Nivqo **不預置作者的私人修補金鑰**。
- 重裝 Manager 或清除資料前，請先匯出自己的 signing key。
- 遺失原本的 key 後，新簽出的 APK 通常無法直接覆蓋舊版本。

## 更新機制

從 Nivqo v1.32.0 開始，更新分成兩層：

- **HushFacebook / HushMessenger 插件核心**：由 Nivqo 自己的 GitHub 更新通道提供。之後原作者核心有更新時，會先拉回 Nivqo、合併繁體中文與本專案修改並測試，接著更新 GitHub 上的 patch bundle。已安裝的 Nivqo 可自動取得新版插件核心，不需要因為只有插件更新就重新安裝 Manager。
- **Nivqo Manager 本體**：只有 Manager 本身有修改時才發布新的 APK，更新來源為 `SkillGodAk/Nivqo` 的 GitHub Releases。

Manager APK 仍內建一份 HushFacebook / HushMessenger bundle 作為離線或第一次啟動的初始版本；**內建版本只在本機尚未有 bundle 時使用，不會覆蓋從 GitHub 更新過的新核心。**

## Release

GitHub Releases 只提供 **Nivqo Manager 正式 APK**。

本專案不在 GitHub Release 重新散布 Facebook 或 Messenger 的完整 APK。請自行取得與支援 `versionCode` 相符的原始應用程式，再透過 Nivqo 進行修補。

目前正式 Manager：

- App：Nivqo
- Package：`app.nivqo.manager`
- Manager base version：`1.32.0`

## 原始碼

主要 source 位於：

- `source/morphe-manager` — Nivqo Manager / Morphe Manager 衍生修改
- `source/hushfacebook` — Facebook patch 與中文化
- `source/hushmessenger` — Messenger patch、中文化與內建設定入口
- `source/morphe-patcher` — Morphe patcher
- `source/morphe-patches-gradle-plugin`
- `source/morphe-library`
- `source/jadb`


## 上游專案與感謝

Nivqo 建立在多個開源專案的工作成果之上。

特別感謝 **SysAdminDoc**：

- [SysAdminDoc](https://github.com/SysAdminDoc)
- [HushFacebook](https://github.com/SysAdminDoc/HushFacebook)
- [HushMessenger](https://github.com/SysAdminDoc/HushMessenger)

也感謝 Morphe 專案：

- [Morphe Manager](https://github.com/MorpheApp/morphe-manager)
- [Morphe organization](https://github.com/MorpheApp)

Nivqo 的中文化、整合與修改不代表任何上游作者對本專案提供官方支援或背書。各子專案保留其原始 LICENSE / NOTICE。

更完整的來源與修改說明請參考 [SOURCES.md](docs/SOURCES.md)。

## 贊助作者

覺得 Nivqo 好用，歡迎支持作者。

### 國外贊助

[Buy Me a Coffee](https://buymeacoffee.com/SkillGodAK)

### 銀行收款

<img src="assets/donate-bank.jpg" alt="銀行收款 QR Code" width="360">

### 微信收款

<img src="assets/donate-wechat.jpg" alt="微信收款 QR Code" width="360">

## 授權

Nivqo 自行修改的 GPL 衍生程式碼依其適用的 GPLv3 條款提供。第三方元件維持各自原始授權，請查看各 source 子目錄中的 `LICENSE` / `NOTICE`。

Nivqo 為獨立第三方專案，與 Meta、Facebook、Messenger、Morphe 或 SysAdminDoc 無官方隸屬關係。