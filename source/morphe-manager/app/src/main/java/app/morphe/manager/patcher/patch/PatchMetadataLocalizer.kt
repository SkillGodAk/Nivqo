package app.morphe.manager.patcher.patch

import app.morphe.manager.util.AppLocale
import java.util.Locale

/**
 * Display-only Traditional Chinese translations for Morphe, HushFacebook and HushMessenger patch metadata.
 *
 * Patch keys stay exactly as declared by the bundle. Unknown/new patches fall back to the bundle's
 * English metadata, so source updates remain usable before this table is refreshed.
 */
object PatchMetadataLocalizer {
    private data class Zh(val name: String, val description: String)

    private val categories = mapOf(
        "Interface" to "介面",
        "Privacy" to "隱私",
        "Ads" to "廣告",
        "Feed" to "動態消息",
        "Downloads" to "下載",
        "Settings" to "設定",
        "Fixes" to "修正",
        "Links and bubbles" to "連結與聊天泡泡",
        "Stickers" to "貼圖",
        "Navigation" to "導覽",
        "Inbox" to "收件匣",
        "Conversations" to "對話",
    )

    private val zh = mapOf(
        // Morphe universal patches
        "Clone app" to Zh(
            "複製應用程式",
            "變更應用程式套件名稱，讓同一個 App 可同時安裝多份。預設會在套件名稱後加上 .morphe；每份複製版本必須使用不同套件名稱。並非所有 App 都支援，可能造成當機或其他非預期行為。"
        ),
        "Enable Android debugging" to Zh(
            "啟用 Android 偵錯",
            "啟用 Android 開發者偵錯功能。套用此補丁可能讓 App 執行速度變慢。"
        ),
        "Remove link verification" to Zh(
            "移除連結驗證",
            "移除 App 的網頁連結驗證設定，讓支援的連結可改由其他 App 開啟。"
        ),
        "Change installer source" to Zh(
            "變更安裝來源",
            "偽裝安裝來源，讓 App 看起來像是從應用程式商店安裝。"
        ),
        "Override certificate pinning" to Zh(
            "覆寫憑證綁定",
            "覆寫憑證綁定，允許透過 Proxy 檢查網路流量。"
        ),
        "Disable Play Store updates" to Zh(
            "停用 Play 商店更新",
            "將版本代碼設為允許的最大值，避免 Play 商店更新此 App。部分 App 可能出現非預期問題，而且使用 Root Mount 安裝時無效。"
        ),

        // HushFacebook
        "AMOLED black theme" to Zh(
            "AMOLED 純黑主題",
            "將 Facebook 深色模式的深灰背景改為純黑。請先在 Facebook 開啟深色模式。"
        ),
        "Block ad telemetry" to Zh(
            "封鎖廣告遙測",
            "阻止 Facebook 偵測廣告截圖，以及回報你安裝哪些 App 來進行廣告歸因。"
        ),
        "Block background ad prefetch" to Zh(
            "封鎖背景廣告預載",
            "阻止 Facebook 在背景下載廣告與廣告模型，可節省流量、電量與儲存空間。"
        ),
        "Block background-return feed refresh" to Zh(
            "返回時保留動態消息位置",
            "在十分鐘內返回 Facebook 時保留原本的動態消息位置。下拉重新整理與重新啟動 App 仍會正常刷新。"
        ),
        "Block promotional notifications" to Zh(
            "封鎖推廣通知",
            "阻擋你選擇的推廣通知類型，例如熱門影片、回顧與生日。各類型都有獨立開關且預設關閉；訊息、交友邀請、留言、提及與登入警示永遠保留。"
        ),
        "Clean up Reels" to Zh(
            "清理 Reels 介面",
            "隱藏 Reels 的追蹤按鈕、留言與表情回應預覽，以及 Remix、使用範本、輪到你了、Stars 等按鈕；各項都有獨立開關。"
        ),
        "Default comment order" to Zh(
            "預設留言排序",
            "以 Hushfacebook 設定中選擇的「最相關／最新／所有留言」順序開啟留言，而不是使用 Facebook 自行選擇的排序。"
        ),
        "Disable Audience Network" to Zh(
            "停用 Audience Network",
            "阻止 Facebook 向其他 App 提供廣告。其他 App 可能改顯示自己的廣告或不顯示廣告，獎勵式廣告也可能無法使用。"
        ),
        "Don't send reel watch history" to Zh(
            "不要傳送 Reel 觀看紀錄",
            "停止把你看過的 Reels 清單傳給 Facebook。此資料會用於排序 Reels；已看過的 Reel 可能因此再次出現。"
        ),
        "Download any reel" to Zh(
            "下載任何 Reel",
            "在每個 Reel 旁加入下載按鈕。影片會依你設定的下載畫質儲存，預設為最佳畫質。"
        ),
        "Download any story" to Zh(
            "下載任何限時動態",
            "在任何限時動態選單加入儲存功能，包括含音樂的限時動態。影片會依你設定的下載畫質儲存。"
        ),
        "Download any video" to Zh(
            "下載任何影片",
            "在動態消息與 Watch 的影片選單加入「下載到手機」。影片會依你設定的下載畫質儲存。"
        ),
        "Force dark mode" to Zh(
            "強制深色模式",
            "讓 Facebook 強制保持深色模式，不受 Facebook 自身設定影響，適用於設定中沒有深色模式選項的平板。此功能預設關閉，可在 Hushfacebook「外觀」中開啟，變更後請重新啟動 Facebook。"
        ),
        "Hide AI-detected posts" to Zh(
            "隱藏 AI 偵測內容",
            "移除 Facebook 自行偵測為 AI 產生的貼文、Reels 與 Watch 影片；另有開關可移除作者自行標註為 AI 的貼文。所有開關預設關閉。"
        ),
        "Hide Menu promotions" to Zh(
            "隱藏功能表推廣內容",
            "隱藏 Facebook 功能表中的「升級」與「Meta 旗下其他產品」區段。設定、使用說明、捷徑及其他功能表內容仍會保留。"
        ),
        "Hide Meta AI in search" to Zh(
            "在搜尋中隱藏 Meta AI",
            "移除搜尋結果中的 Meta AI 回答與提示，並阻止搜尋建議自行開啟 Meta AI。人物、社團、粉絲專頁、貼文與 Meta AI 按鈕仍保留。"
        ),
        "Hide Meta AI questions under posts" to Zh(
            "隱藏貼文下方的 Meta AI 問題",
            "移除 Facebook 加在部分貼文下方的 Meta AI 問題列。貼文本身、連結卡片與按鈕仍會保留。"
        ),
        "Hide the Feeds header" to Zh(
            "隱藏 Feeds 標題列",
            "移除 Feeds 分頁頂端的標題列，以及「全部、最愛、朋友、社團、粉絲專頁」篩選器，讓分頁直接顯示貼文。此開關預設關閉，請在「動態消息」中開啟後重新啟動 Facebook。"
        ),
        "Keep post dates" to Zh(
            "保留貼文日期",
            "保留發佈者名稱下方的貼文日期。新版 Facebook 可能在貼文顯示後把該行換成輪替資訊，部分手機甚至會變成空白；開啟後日期會保持不變。"
        ),
        "Hide Reels in the feed" to Zh(
            "隱藏動態消息中的 Reels",
            "移除動態消息貼文之間與末尾加入的 Reels。好友直接發布的 Reel 仍會保留。"
        ),
        "Hide Stories tray" to Zh(
            "隱藏限時動態列",
            "移除動態消息頂端的限時動態列，包括「建立限時動態」。"
        ),
        "Hide posts by words" to Zh(
            "依指定文字隱藏貼文",
            "隱藏文字中包含你指定單字或片語的動態消息貼文；若同時包含保留清單中的文字則保留。你的文字清單不會傳送出去。"
        ),
        "Hide sponsored Marketplace listings" to Zh(
            "隱藏 Marketplace 贊助刊登",
            "移除 Marketplace 動態中的廣告與付費推廣刊登，並阻止只用於抓取廣告的請求。一般刊登仍會保留。"
        ),
        "Hide sponsored posts" to Zh(
            "隱藏贊助貼文",
            "移除動態消息中的贊助與推廣貼文，不留下空白間隔。"
        ),
        "Hide sponsored profile posts" to Zh(
            "隱藏個人檔案贊助貼文",
            "移除個人檔案或粉絲專頁貼文之間的廣告，原本貼文仍會保留。"
        ),
        "Hide sponsored reels" to Zh(
            "隱藏贊助 Reels",
            "移除 Reels 與 Watch 中的廣告，包括覆蓋在 Reel 上的商品橫幅與影片內廣告。"
        ),
        "Hide sponsored search results" to Zh(
            "隱藏贊助搜尋結果",
            "移除 Facebook 搜尋結果中的贊助貼文與廣告卡片，真正的搜尋結果仍會保留。"
        ),
        "Hide sponsored stories" to Zh(
            "隱藏贊助限時動態",
            "移除限時動態檢視器中的廣告卡片，滑動時只顯示使用者發布的限時動態。"
        ),
        "Hide suggested and promoted posts" to Zh(
            "隱藏建議與推廣內容",
            "移除 Facebook 加入動態消息中的非廣告推薦，例如「為你推薦」、「你可能認識的人」、建議社團、建議限時動態、粉絲專頁推薦、自家推廣與問卷；各類型都有獨立開關。"
        ),
        "Hide suggested stories" to Zh(
            "隱藏建議限時動態",
            "移除限時動態列中來自你未追蹤人物或粉絲專頁的建議限時動態。好友、已追蹤粉絲專頁與「建立限時動態」仍會保留。"
        ),
        "Hide the Get Messenger card" to Zh(
            "隱藏「取得 Messenger」卡片",
            "安裝 Messenger 後隱藏聊天室頂端的「取得 Messenger App」卡片。重新簽章 Facebook 也能正確辨識 Messenger；另提供可選開關，讓 Facebook 聊天室直接跳轉到已安裝的 Messenger。"
        ),
        "Hushfacebook in the Menu" to Zh(
            "在功能表加入 Hushfacebook",
            "在 Facebook「設定和隱私」底部加入 Hushfacebook 設定入口；長按 Facebook 標誌與啟動器捷徑仍可開啟設定。"
        ),
        "Hushfacebook settings" to Zh(
            "Hushfacebook 設定",
            "在 Facebook 中加入完整 Hushfacebook 設定，可開關功能、暫停、匯入／匯出設定與診斷資訊，並查看授權資訊。"
        ),
        "Install beside Meta's apps" to Zh(
            "與 Meta App 共存",
            "讓官方 Messenger、Facebook Lite、Business Suite 與 Workplace 可與重新簽章的 Facebook 同時安裝。此補丁會重新命名 Facebook 共用的兩個簽章權限。Root Mount 不需要此補丁。"
        ),
        "Marketplace only" to Zh(
            "僅保留 Marketplace",
            "分頁列只保留 Marketplace、通知與個人檔案／功能表，並直接從 Marketplace 啟動 Facebook。通知與連結仍會開啟原本目的地。"
        ),
        "Material You theme" to Zh(
            "Material You 主題",
            "Android 12 以上讓 Facebook 深色模式使用桌布配色；Android 11 使用固定藍色調。淺色模式不變。請先開啟 Facebook 深色模式。"
        ),
        "Open links in external browser" to Zh(
            "使用外部瀏覽器開啟連結",
            "用預設瀏覽器開啟網頁連結，而不是 Facebook 內建瀏覽器，並移除 Facebook 點擊追蹤與 fbclid。Facebook 自己的頁面仍在 App 內開啟。"
        ),
        "Open on a chosen tab" to Zh(
            "從指定分頁開啟",
            "從 Facebook 圖示啟動時開啟你在 Hushfacebook 設定中選擇的分頁，預設為 Marketplace。通知與連結仍會開啟原本目的地。"
        ),
        "Restore screens on re-signed builds" to Zh(
            "修復重新簽章版本畫面",
            "讓重新簽章版本中的個人檔案與部分設定頁面恢復正常開啟。Root Mount 不需要此補丁。"
        ),
        "Resume long videos" to Zh(
            "續播長影片",
            "超過兩分鐘的影片下次播放時會從上次離開的位置繼續。Reels、直播與廣告仍照原本方式開始播放。此開關預設關閉。"
        ),
        "Sanitize sharing links" to Zh(
            "清除分享連結追蹤",
            "移除分享或複製連結中的 mibextid 等 Facebook 追蹤參數，連結本身指向的貼文或 Reel 不變。"
        ),
        "Stop Story auto-advance" to Zh(
            "停止限時動態自動切換",
            "讓每則限時動態停留在畫面，直到你點擊或滑動。關閉開關即可恢復 Facebook 原本的自動切換時間。"
        ),
        "Stop update prompts" to Zh(
            "停止更新提示",
            "停止重新簽章版本中的 Facebook 更新提示、Meta App Manager 更新推廣及要求檢查更新的推播，也移除針對舊版本的聊天升級推廣。"
        ),
        "Start on x86 devices" to Zh(
            "支援 x86 裝置啟動",
            "避免 Facebook 在透過轉譯執行 ARM 程式碼的 x86 裝置（例如模擬器或 x86 Chromebook）啟動時當機或卡住，方法是略過會出問題的啟動步驟。ARM 手機與平板維持原本啟動流程。"
        ),
        "Tab bar at the bottom" to Zh(
            "分頁列置底",
            "在目前把分頁列顯示於頂部的帳號上，將 Facebook 分頁列移到畫面底部。此開關預設關閉，請在「外觀」中開啟後重新啟動 Facebook。"
        ),
        "Tag suggestions only after @" to Zh(
            "只有輸入 @ 後才顯示標註建議",
            "在貼文與留言輸入一般文字時不再自動推薦標註對象；輸入 @ 仍會顯示建議清單。相片標註與文字內容不受影響。"
        ),
        "Tap to play" to Zh(
            "點擊播放",
            "影片、Reels、限時動態與音樂會等待你點擊後才播放。開啟時 Facebook 的自動播放設定會顯示為關閉。"
        ),
        "Use the phone's emoji" to Zh(
            "使用手機 Emoji",
            "使用手機系統 Emoji 字型取代 Meta Emoji，貼文、留言與聊天會更接近鍵盤顯示；表情回應與貼圖不變。變更後需重新啟動 Facebook。"
        ),
        "Use the system font" to Zh(
            "使用系統字型",
            "使用手機系統字型取代 Meta 的 Optimistic 字型，或使用 Hushfacebook 設定中選擇的 TrueType／OpenType 字型。圖示、Emoji 與限時動態文字不受影響。"
        ),


        "Default playback quality" to Zh(
            "預設播放畫質",
            "依 Hushfacebook 設定中選擇的畫質播放影片、Reels 與影片限時動態，例如節省數據或最高 720p，而不是每次交給 Facebook 自動決定。若在單一影片自己的選單另外選擇畫質，該影片仍以你的選擇為準。"
        ),
        "Hide affiliate product links" to Zh(
            "隱藏聯盟商品連結",
            "移除 Reels、動態貼文與留言面板中的聯盟商店商品卡片；「可獲得佣金」標示仍會保留。"
        ),
        "Hide post prompts" to Zh(
            "隱藏貼文提示",
            "移除 Facebook 加在部分貼文上的提示列，例如「你對這則貼文有興趣嗎？」、「少顯示這類內容」、最近留言者，以及追蹤或聊天建議；不留下空白，貼文本身仍會保留。"
        ),
        "Hide reel interest prompts" to Zh(
            "隱藏 Reel 興趣提示",
            "移除 Reels 上的「你對這則 Reel 有興趣嗎？」提示；Reel 仍照常播放。"
        ),
        "Hide the Reels tab" to Zh(
            "隱藏 Reels 分頁",
            "從分頁列移除 Reels（部分帳號顯示為影片）分頁，也移除 Facebook 圖示長按選單中的 Reels 捷徑。Reel 連結與動態消息中的 Reels 仍可開啟；Facebook 自己的分頁隱藏設定仍有效，變更此開關後需重新啟動 Facebook。"
        ),
        "Hide the Reels tab dot" to Zh(
            "隱藏 Reels 分頁提示點",
            "移除 Reels（部分帳號顯示為影片）分頁上的新項目提示點與數量；其他分頁的提示不受影響。"
        ),
        "Hold a reel for 2x" to Zh(
            "長按 Reel 以 2 倍速播放",
            "長按 Reel 時以 2 倍速播放，放開後恢復。長按操作會取代 Facebook 原本的長按選單，但 Reel 的「更多」按鈕仍可開啟該選單。"
        ),
        "Keep the reel speed" to Zh(
            "保留 Reel 播放速度",
            "你在 Reel 選單中選擇的播放速度會沿用到後續 Reels，直到選擇其他速度或重新啟動 Facebook。"
        ),
        "Open Messenger from the top bar" to Zh(
            "從頂部列開啟 Messenger",
            "Facebook 頂部的 Messenger 圖示可在兩種路徑間切換：開啟此選項時直接開啟 Messenger App；關閉時由 Nivqo 明確開啟 Facebook 內建聊天，略過 Meta 的 Messenger 跳轉詢問。長按圖示仍保留 Facebook 原本行為。此開關預設關閉。"
        ),
        "Turn off double tap to like" to Zh(
            "關閉雙擊按讚",
            "雙擊 Reel 或影片時不再按讚，也不會顯示愛心。單擊仍可播放或暫停，「讚」按鈕仍可正常使用。"
        ),
        "View stories anonymously" to Zh(
            "匿名觀看限時動態",
            "不把你看過哪些限時動態回報給 Facebook，因此不會出現在觀看者名單中。若回覆或傳送表情回應仍會顯示你的身分，而且看過的限時動態仍會保留未觀看外框。"
        ),

        // HushMessenger
        "Allow chat bubbles" to Zh(
            "允許聊天泡泡",
            "提供原始模式、Chat Heads 與原生泡泡三種模式。原生泡泡需要 Android 11 以上、帳號支援與通知權限。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide joined community chats" to Zh(
            "隱藏已加入的社群聊天",
            "從主要收件匣隱藏已加入的社群聊天；搜尋與社群資料夾仍保留，訊息傳遞與未讀計數不受影響。下次重新顯示收件匣時套用。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide AI sticker tools" to Zh(
            "隱藏 AI 貼圖工具",
            "隱藏 AI 貼圖的「產生」按鈕、產生貼圖分頁與 AI 貼圖建議。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide Chat Moments" to Zh(
            "隱藏 Chat Moments",
            "隱藏功能表中的 Chat Moments 項目。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide Facebook shortcuts" to Zh(
            "隱藏 Facebook 捷徑",
            "隱藏 Facebook 工具列、個人檔案與分享捷徑，以及「選單」分頁中的「Also from Meta」區段。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide Meta AI buttons" to Zh(
            "隱藏 Meta AI 按鈕",
            "隱藏浮動按鈕、工具列按鈕與 AI 功能表項目；搜尋仍可使用。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide People You May Know" to Zh(
            "隱藏「你可能認識的人」",
            "隱藏聊天、搜尋、限時動態，以及「聯絡人」與通知分頁中的好友建議。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide Reels badge" to Zh(
            "隱藏 Reels 徽章",
            "隱藏 Reels 通知徽章。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide avatar stickers" to Zh(
            "隱藏虛擬替身貼圖",
            "隱藏貼圖鍵盤中的虛擬替身分頁。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide business reply suggestions" to Zh(
            "隱藏商家回覆建議",
            "隱藏商家對話中的建議回覆。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide business typing suggestions" to Zh(
            "隱藏商家輸入建議",
            "輸入文字時隱藏商家相關建議。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide chat promotions" to Zh(
            "隱藏聊天推廣",
            "隱藏對話中的 Messenger 快速推廣橫幅。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide event prompts" to Zh(
            "隱藏活動提示",
            "隱藏聊天中的活動快速推廣提示。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide friend request cards" to Zh(
            "隱藏交友邀請卡片",
            "隱藏收件匣中的交友邀請卡片。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide growth prompts" to Zh(
            "隱藏成長推廣提示",
            "隱藏收件匣中鼓勵加入更多聯絡人的推廣單元，也隱藏便利貼中的提示面板（例如「公開我的便利貼」）與看完他人限時動態後的「分享你自己的限時動態」卡片。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide inbox ads" to Zh(
            "隱藏收件匣廣告",
            "過濾收件匣中的廣告項目。仍需要有實際廣告帳號進一步驗證即時移除效果。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide inbox promotions" to Zh(
            "隱藏收件匣推廣",
            "隱藏聊天列表中的 Messenger 快速推廣橫幅。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide inbox tabs" to Zh(
            "隱藏收件匣分頁",
            "隱藏首頁與頻道子分頁。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide stories and notes" to Zh(
            "隱藏限時動態與便利貼",
            "隱藏聊天上方的橫向限時動態／便利貼列。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide typing indicator" to Zh(
            "隱藏輸入中狀態",
            "停止傳送你的「正在輸入」狀態，包括端對端加密聊天。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Install beside Meta apps" to Zh(
            "與 Meta App 共存",
            "在已驗證的 Messenger 580 build 中重新命名兩個共用簽章權限。作者目前標示：S25 的簽署版本曾出現聊天無法開啟，使用前請保留可復原方式。"
        ),
        "Open web links externally" to Zh(
            "使用外部瀏覽器開啟網頁連結",
            "HTTP 與 HTTPS 連結使用 Messenger 原生的外部瀏覽器分支。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),

        "Allow screenshots" to Zh(
            "允許截圖",
            "允許截取受保護的聊天媒體，包括僅限查看一次的媒體與 Quicksnap，並停止截圖通知；不會新增重新播放或儲存功能。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide Meta AI" to Zh(
            "隱藏 Meta AI",
            "隱藏 Meta AI 浮動按鈕、工具列按鈕、Meta AI 分頁、選單項目與搜尋 AI。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Hide read receipts" to Zh(
            "隱藏已讀回條",
            "停止傳送你的已讀回條。已開啟的端對端加密聊天在此手機上可能維持未讀；回覆訊息或關閉此功能可能會通知對方。群組聊天支援尚未驗證。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Keep unsent messages" to Zh(
            "保留已收回訊息",
            "在已驗證的舊式收回訊息路徑保留被收回的訊息。不支援端對端加密聊天，群組聊天支援尚未驗證；活動紀錄代表攔截到舊式收回事件，不代表該聊天一定受支援。啟用時你自己的收回功能可能受限。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Open settings from menu" to Zh(
            "從選單開啟設定",
            "在 Messenger 的「選單」分頁與側邊選單加入 HushMessenger 設定入口。此補丁固定啟用。"
        ),
        "Save any story" to Zh(
            "儲存任何限時動態",
            "在其他人的限時動態「更多」選單加入「儲存」。相片或影片會使用 Messenger 原本儲存自己限時動態的方式存到手機。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Send photos at original quality" to Zh(
            "以原始畫質傳送照片",
            "開啟 HD 時，直接傳送 JPEG 照片本身的影像資料，不使用重新編碼的副本；位置、相機資訊等中繼資料會移除，只保留旋轉標記。影片與超過 20 MB 的照片仍會壓縮。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Slide chats in and out" to Zh(
            "聊天滑入滑出動畫",
            "從聊天列表或搜尋開啟聊天時，讓聊天畫面從側邊滑入；返回時滑出，底下畫面保持不動。Chat Heads 與泡泡保留自己的動畫。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
        "Use system emoji" to Zh(
            "使用系統 Emoji",
            "使用手機自己的 Emoji 字型，而不是 Messenger 內建字型。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。"
        ),
    )

    private val messengerZhDescriptions = mapOf(
        "Material You theme" to
            "Android 12 以上讓 Messenger 深色模式使用桌布配色；Android 11 使用固定藍色調。淺色模式不變。請先在 Messenger 開啟深色模式。",
        "View stories anonymously" to
            "開啟其他人的限時動態時，不會把你加入對方的觀看名單；在你這一端仍會標記為已看。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。",
    )

    fun name(original: String): String =
        if (isTraditionalChinese()) zh[original]?.name ?: original else original

    fun description(name: String, original: String?): String? {
        if (!isTraditionalChinese()) return original
        if (isMessengerVariant(name, original)) {
            return messengerZhDescriptions[name] ?: zh[name]?.description ?: original
        }
        return zh[name]?.description ?: original
    }

    private fun isMessengerVariant(name: String, original: String?): Boolean = when (name) {
        "Material You theme" -> original?.contains("Messenger", ignoreCase = true) == true
        "View stories anonymously" ->
            original?.contains("marked as seen on your side", ignoreCase = true) == true
        else -> false
    }

    fun category(original: String?): String? =
        if (isTraditionalChinese() && original != null) categories[original] ?: original else original

    /** True when [text] is one of Nivqo's already-localized Traditional Chinese descriptions. */
    fun isPrelocalized(text: String): Boolean =
        isTraditionalChinese() && zh.values.any { it.description == text }

    private fun isTraditionalChinese(): Boolean {
        val selected = AppLocale.selected.value
        val locale = AppLocale.toLocale(selected) ?: Locale.getDefault()
        if (!locale.language.equals("zh", ignoreCase = true)) return false
        val script = locale.script
        val country = locale.country
        return script.equals("Hant", ignoreCase = true) ||
            country.equals("TW", ignoreCase = true) ||
            country.equals("HK", ignoreCase = true) ||
            country.equals("MO", ignoreCase = true)
    }
}
