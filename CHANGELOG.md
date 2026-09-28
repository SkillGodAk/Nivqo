# Changelog

## 2026-09-29

首個 Nivqo 公開版本。

### Manager

- 正式品牌改為 Nivqo。
- 正式 package 改為 `app.nivqo.manager`。
- 內建 HushFacebook / HushMessenger patch bundle。
- 繁體中文 / English 使用流程。
- 不預置作者私人 patch signing key。
- 關閉原始 Manager 的官方自更新入口，避免不同品牌 / 簽名來源混用。

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