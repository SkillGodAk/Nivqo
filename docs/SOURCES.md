# Sources and Credits

Nivqo 是第三方衍生與整合專案。以下列出主要上游來源。

## SysAdminDoc

特別感謝 [SysAdminDoc](https://github.com/SysAdminDoc) 對 Facebook / Messenger patch 的開源工作。

### HushFacebook

- Upstream: https://github.com/SysAdminDoc/HushFacebook
- Nivqo 主要修改：完整繁體中文化與介面語言選擇；Facebook → Messenger 跳轉改採上游正式 `Open the Messenger app` 實作
- 同步上游 main：`6d312196bc9ae8a7c9bd77f936c60b6e6de579d2`（HushFacebook 0.5.0 + 後續 main 修正）
- 原始授權與 NOTICE 保留於 `source/hushfacebook`

### HushMessenger

- Upstream: https://github.com/SysAdminDoc/HushMessenger
- Nivqo 主要修改：完整繁體中文化與介面語言選擇；設定入口改採上游正式 Menu 列 / Patch 控制 / 可隱藏 app drawer icon，不再保留舊 Nivqo 自製 SettingsEntry
- 同步上游 main：`4b259a712e1b3c90edb7d0262d2b7d23bf45b093`（HushMessenger 0.6.0）
- 原始授權與 NOTICE 保留於 `source/hushmessenger`

目前 Nivqo 同步的 Messenger `580.0.0.49.91` arm64 支援 build：

- `346013354`
- `346013355`
- `346013356`
- `346013357`
- `346013358`
- `346013359`
- `346013370`
- `346013372`
- `346013374`
- `346013375`
- `346013387`
- `346013391`
- `346013394`
- `346013423`
- `346013427`
- `346013440`
- `346013441`
- `346013442`
- `346013443`
- `346013444`
- `346013445`

## Morphe

Nivqo Manager 基於 Morphe Manager 與其相關元件。

- Morphe Manager: https://github.com/MorpheApp/morphe-manager
- Morphe organization: https://github.com/MorpheApp
- Morphe Patcher: `source/morphe-patcher`
- Morphe Library: `source/morphe-library`
- Morphe patches Gradle plugin: `source/morphe-patches-gradle-plugin`
- JADB: `source/jadb`


Current Nivqo Manager sync:

- Morphe Manager `v1.33.0` / upstream main `2d9e7f65af9e03370650d7840d20a47096def3be`
- Morphe Patcher `v1.15.0` / upstream `524b9d8de5c2ec8f8cea0022cbce9a026d098679`

Nivqo 使用獨立品牌與 package `app.nivqo.manager`，用來清楚區分本專案與原始 Morphe 版本。

## Meta / Facebook / Messenger

Nivqo 與 Meta 無官方關係。

Facebook、Messenger、Meta 名稱只用來描述：

- patch 的目標應用程式
- 相容版本
- 使用方式

GitHub Releases 不提供或重新散布完整 Facebook / Messenger APK。

## 授權

本倉庫包含多個上游專案，可能有不同授權條款。每個 source 子目錄內的原始 `LICENSE` / `NOTICE` 優先適用於該元件。

Nivqo 對 GPL 衍生元件所做的修改依適用的 GPLv3 條款提供。

Nivqo 的存在與修改不代表任何上游作者對本專案提供官方支援、認證或背書。