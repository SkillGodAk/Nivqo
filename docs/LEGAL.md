# Nivqo 法律、授權與品牌說明

更新：2026-10-01

Nivqo 是獨立第三方開源專案，不是 Meta、Facebook、Messenger、Morphe、HushFacebook 或 HushMessenger 的官方產品，也未宣稱獲得上述專案或公司的授權、贊助或認可。

## Nivqo 發布什麼

Nivqo 發布：
- Nivqo Manager 與其開源修改；
- HushFacebook / HushMessenger 的開源 patch bundle 與 Nivqo 修改；
- 必要的更新 metadata、文件與建置資訊。

Nivqo 不在 GitHub Release、更新通道或專案儲存庫重新散布完整 Facebook / Messenger APK。使用者必須自行取得與支援 versionCode 相符、且其有權使用的原始 APK，再於本機執行修補。

## 上游開源授權

Nivqo Manager 是 Morphe Manager 的衍生版本。Morphe Manager 以 GPLv3 提供，並在 NOTICE 中加入 GPLv3 Section 7 條件，包括：
- 修改版本不得誤導其來源，必須合理標示為不同於原版；
- 未授予 Morphe 名稱、商標、服務標章或 logo 的商標權利。

因此 Nivqo：
- 使用自己的名稱、package 與圖示；
- About 的主要連結只指向 Nivqo 自己的 GitHub / Releases / Changelog / Issues；
- 在「鳴謝」與「開源授權」保留 Morphe、URV、ReVanced 與其他上游來源和授權資訊；
- 不把 Morphe 的網站、Reddit 或 Crowdin 當成 Nivqo 的官方入口。

HushFacebook、HushMessenger 及其他 source 子目錄仍依各自 LICENSE / NOTICE 辦理；不得因 Nivqo 包裝而移除原作者授權或必要 notice。

## Meta / Facebook / Messenger 名稱

「Meta」、「Facebook」、「Messenger」只用於說明：
- 相容的原始應用程式；
- patch 的目標；
- 使用者操作流程。

Nivqo 不使用 Meta 的名稱作為自己的品牌名稱，也不宣稱官方關係。

台灣智慧財產局說明，商標法第 36 條的合理使用可包含為說明相容性或用途而指示他人商品／服務，但是否成立仍須依誠實信用、必要性及是否造成授權／贊助關係的混淆等個案因素判斷。

參考：
- https://www.tipo.gov.tw/tw/trademarks/568-7607.html
- https://www.tipo.gov.tw/tw/trademarks/632.html

## 台灣著作權與技術保護措施

台灣著作權法把電腦程式列為受保護著作，著作權人原則上享有重製與改作等權利；合理使用依第 65 條須依利用目的與性質、著作性質、利用比例及對市場／價值的影響等因素個案判斷。

著作權法第 80 條之 2 另規範防盜拷措施，並列有安全測試、加密研究、還原工程、依法合理使用等例外。這些例外不是「任何 patch 都自動合法」的保證，仍須看具體功能、目的、散布方式與主管機關規定。

參考：
- https://law.moj.gov.tw/LawClass/LawAll.aspx?pcode=J0070017
- https://www.tipo.gov.tw/tw/copyright/694-17503.html

## 風險降低原則

Nivqo 的發布流程應持續遵守：
1. 不散布 Meta 完整或已修補 APK。
2. 不把 Meta 的 proprietary 程式碼、圖片、logo 或大量 UI 資源抽出後重新打包發布。
3. Facebook / Messenger / Meta 只作相容性說明，不用作 Nivqo 的品牌識別。
4. 保留 GPL / NOTICE / credits 與可取得對應原始碼。
5. 對涉及簽章檢查、anti-tamper、DRM、防盜拷或其他技術保護措施的 patch，應單獨檢視其法律風險，不因「本機 patch」就假定無風險。
6. 若收到權利人或主管機關的具體通知，應針對相關功能、素材或散布方式重新評估。

本文件是專案風險整理，不是律師針對個案出具的法律意見。
