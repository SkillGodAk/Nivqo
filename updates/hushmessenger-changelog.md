# 0.27.0-nivqo.1 (2026-10-28)

* **Messenger:** 繁體中文：同步正式 HushMessenger 0.27.0（66 個補丁、59 個控制項），適用 Messenger 584.1.0.61.98 的相容原版 APK。新增安靜來電、手動標記已讀、系統相簿選取、方形頭像、限制裝置資料分享等功能，並整合上游繁體中文介面；Manager 中文補丁說明 66／66。使用舊版本 APK 的使用者須重新取得相容原版並修補。
* **Messenger:** English: Formal upstream HushMessenger 0.27.0, with 66 patches and 59 controls targeting compatible stock Messenger 584.1.0.61.98 APKs. Adds quiet calls, manual mark-as-read, gallery selection, square avatars, sharing controls and upstream Traditional Chinese UI. Manager patch descriptions localized 66/66. Older APK bases require repatching.

# 0.24.0-nivqo.1 (2026-10-16)

* **Messenger:** 繁體中文：同步正式 HushMessenger 0.24.0，由 50 增至 57 個 Patch、51 個控制項。新增最多釘選 20 個聊天室、粗體／斜體文字、隱藏用戶／通知分頁、停止附近掃描與 MQTT 記錄，並改善訊息記錄、快速設定及重新啟動操作。新增／補齊 85 條介面文字及 14 條新控制項繁中翻譯，核心繁中涵蓋 399／399，Manager 中文補丁說明 57／57。**僅支援 Messenger 583.0.0.29.95 的六個 arm64 build；582 已移除，須使用相容的 583 APK 重新修補。**
* **Messenger:** English: Syncs formal HushMessenger 0.24.0 (57 patches, 51 controls), adding up to 20 pinned chats, bold/italic text, tab hiding, nearby-scan and MQTT logging controls, plus message-log and settings enhancements. Traditional Chinese coverage is 399/399 (85 new UI strings and 14 new control translations); all 57 Manager patch descriptions are localized. **Only six arm64 builds of Messenger 583.0.0.29.95 are supported; 582 is no longer supported, so re-patch a compatible 583 APK.**

# 0.23.0-nivqo.1 (2026-10-14)

* **Messenger:** 繁體中文：同步上游正式 HushMessenger v0.23.0（f5384dd62cc332dda82906d36c8a83f57f3e7906），由 43 增為 50 個 Patch、44 個控制項識別鍵。新增自動回覆、自訂字型、停止上滑誤啟動消失訊息、商家聊天廣告橫幅隱藏、廣告歸因與收件匣紀錄控制、xHE-AAC 影片分享修復；包含已讀隱藏與相機修正。保留 Nivqo「跟隨系統／繁體中文／English」語言選擇，補齊 48 條新增介面文字及 7 組新控制項的繁中名稱／說明，Manager Patch 中繼資料涵蓋 50／50。**相容性變更：只支援 Messenger 582.0.0.61.92，不再支援 580／581；更新後應以對應版本重新修補 APK。**
* **Messenger:** English: Syncs the formal HushMessenger v0.23.0 release (f5384dd), growing from 43 to 50 patches with 44 control keys. Adds auto replies, custom fonts, swipe-up disappearing-message protection, business-chat banner and ad-event controls, xHE-AAC sharing fixes, and camera/read-receipt improvements. Preserves Nivqo's system/Traditional Chinese/English selector, translates 48 new UI strings and the seven new controls, and covers all 50 Manager patch entries. Compatibility is **Messenger 582.0.0.61.92 only**; 580/581 builds are no longer supported.

# 0.22.0-nivqo.1 (2026-10-11)

* **Messenger:** 繁體中文：同步上游正式 HushMessenger v0.22.0（commit 4d54f76ff19d9a5d7ab4bd5fe96059b301cea68a），由 33 增至 43 個 Patch、37 個可切換控制項。新增純黑深色模式、加密訊息通知紀錄、原生相機、合格影片免重新編碼、解除 App 圖示限制、Emoji 面板選項及阻止分析上傳；修正系統 Emoji 造成讚圖示空白方框。保留 Nivqo 跟隨系統／繁中／English 選擇，Manager metadata 43 / 43。
* **Messenger:** English: Syncs formal upstream v0.22.0, increasing from 33 to 43 patches with 37 controls. Adds pure black dark mode, encrypted local notification message log, system camera, video passthrough, app icons and emoji drawer options, and analytics controls; fixes the system emoji Like-square regression. Preserves the Nivqo interface language selector and localized metadata.

# 0.21.0-nivqo.1 (2026-10-06)

* **Messenger:** 繁體中文：同步正式 HushMessenger v0.21.0（commit d5b196f45f400c3d97eff888ea0464b270731323），不納入發版後的 main commit。核心由 32 個 patch 更新為 33 個、30 個可切換控制項；新增 Messenger 581.0.0.49.91 支援，與既有 580.0.0.49.91 合計涵蓋 37 個 arm64 builds。新增「隱藏已加入的社群聊天」，「隱藏 AI 貼圖工具」擴充到新版貼圖鍵盤的 Generate 按鈕，「允許截圖」擴充到僅限查看一次的媒體與 Quicksnap。工具鏈同步 Morphe Patcher 1.15.1 與 MorpheApp ARSCLib。Nivqo 保留「跟隨系統／繁體中文／English」語言選擇，並補齊 0.21.0 新增與改寫文字，核心設定繁中覆蓋 213 / 213，Manager patch metadata 33 / 33。
* **Messenger:** English: Syncs only the formal HushMessenger v0.21.0 release (commit d5b196f45f400c3d97eff888ea0464b270731323) and excludes post-release main commits. The core grows from 32 to 33 patches with 30 switchable controls, adds Messenger 581.0.0.49.91 alongside 580.0.0.49.91 for 37 supported arm64 builds total, adds Hide joined community chats, expands Hide AI sticker tools to the newer Generate buttons, and extends Allow screenshots to view-once media and Quicksnap. The toolchain moves to Morphe Patcher 1.15.1 and the maintained MorpheApp ARSCLib. Nivqo preserves Follow system / Traditional Chinese / English selection, with 213 / 213 core settings strings and 33 / 33 Manager patch metadata localized in Traditional Chinese.

# 0.14.0-nivqo.1 (2026-10-03)

* **Messenger:** 繁體中文：同步正式 HushMessenger v0.14.0（commit c3d91c8200337cd826c2c92ef06e1f9c261f8e62），不納入發版後的 main commit。核心由 31 個 patch 更新為 32 個，新增「聊天滑入滑出動畫」；「允許聊天泡泡」擴充為原始模式、Chat Heads 與 Native Bubbles；HushMessenger 設定入口支援 Messenger 側邊選單；「使用系統 Emoji」改為使用手機自己的 Emoji。並納入 v0.8.0～v0.14.0 的選擇檔備份、Root Mount 快捷方式、更新檢查、安全模式與多項穩定性修正。Nivqo 保留「跟隨系統／繁體中文／English」語言選擇，核心設定文字繁中覆蓋為 203 / 203，Manager patch metadata 為 32 / 32。
* **Messenger:** English: Syncs only the formal HushMessenger v0.14.0 release (commit c3d91c8200337cd826c2c92ef06e1f9c261f8e62) and excludes post-release main commits. The core grows from 31 to 32 patches with the new Slide chats in and out control; Allow chat bubbles now offers Stock, Chat Heads and Native Bubbles; HushMessenger settings can open from Messenger's side menu; and Use system emoji now uses the phone's own emoji. It also includes the v0.8.0–v0.14.0 backup, Root Mount shortcut, update-check, safe-mode and reliability improvements. Nivqo preserves Follow system / Traditional Chinese / English language selection, with 203 / 203 core settings strings and 32 / 32 Manager patch metadata localized in Traditional Chinese.

# 0.7.0-nivqo.1 (2026-10-01)

* **Messenger:** Syncs only the formal upstream HushMessenger v0.7.0 release (commit `1756352d6884c88122f73d5e212180abdd75f517`), keeping 21 supported arm64 builds of Messenger 580.0.0.49.91. The bundle now contains 31 patches, including Material You theme, anonymous story viewing and saving other people's stories. Core settings have complete Traditional Chinese coverage. The Nivqo v1.33.0 replacement APK is also refreshed with all 31 Manager-side patch metadata translations and Messenger-specific wording for duplicate patch names. The post-release upstream `main` compatibility commit is intentionally not included.

# [0.6.0-nivqo.1](https://github.com/SkillGodAk/Nivqo/commit/03b27e42f3648ec4cd39ba6cbe71c4120dc777af) (2026-09-30)

* **Messenger:** Syncs HushMessenger 0.6.0, integrates HushMessenger settings into Messenger's Menu flow, adds launcher-icon hiding and privacy/media controls, expands supported 580.0.0.49.91 arm64 versionCodes, and completes Traditional Chinese patch metadata.

# [0.4.2-nivqo.1](https://github.com/SkillGodAk/Nivqo/commit/340f97b08a5b7fa61800a61db9417aa4a233ca32) (2026-09-29)

* **Messenger:** Initial Nivqo HushMessenger core channel with Traditional Chinese integration and Nivqo Manager remote updates.
