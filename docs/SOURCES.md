# Sources and Credits

Nivqo 是第三方衍生與整合專案。以下列出主要上游來源。

## SysAdminDoc

特別感謝 [SysAdminDoc](https://github.com/SysAdminDoc) 對 Facebook / Messenger patch 的開源工作。

### HushFacebook

- Upstream: https://github.com/SysAdminDoc/HushFacebook
- Nivqo 主要修改：繁體中文化、Facebook / Messenger 整合與本專案所需的相容調整
- 原始授權與 NOTICE 保留於 `source/hushfacebook`

### HushMessenger

- Upstream: https://github.com/SysAdminDoc/HushMessenger
- Nivqo 主要修改：繁體中文化、將設定入口整合到 Messenger 內、與 Facebook 配合的使用流程
- 原始授權與 NOTICE 保留於 `source/hushmessenger`

目前 Nivqo 同步的 Messenger 支援 build：

- `346013387`
- `346013440`
- `346013442`

## Morphe

Nivqo Manager 基於 Morphe Manager 與其相關元件。

- Morphe Manager: https://github.com/MorpheApp/morphe-manager
- Morphe organization: https://github.com/MorpheApp
- Morphe Patcher: `source/morphe-patcher`
- Morphe Library: `source/morphe-library`
- Morphe patches Gradle plugin: `source/morphe-patches-gradle-plugin`
- JADB: `source/jadb`

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