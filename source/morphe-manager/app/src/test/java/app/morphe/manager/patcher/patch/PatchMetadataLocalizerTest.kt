package app.morphe.manager.patcher.patch

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PatchMetadataLocalizerTest {
    private lateinit var original: Locale

    @BeforeTest
    fun rememberLocale() {
        original = Locale.getDefault()
    }

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `traditional chinese localizes known Hush patches`() {
        Locale.setDefault(Locale.forLanguageTag("zh-TW"))

        assertEquals("隱藏贊助貼文", PatchMetadataLocalizer.name("Hide sponsored posts"))
        assertEquals("廣告", PatchMetadataLocalizer.category("Ads"))
        assertEquals(
            "移除動態消息中的贊助與推廣貼文，不留下空白間隔。",
            PatchMetadataLocalizer.description(
                "Hide sponsored posts",
                "Removes sponsored and promoted posts from the news feed, with no gap left behind."
            )
        )

        assertEquals("隱藏收件匣廣告", PatchMetadataLocalizer.name("Hide inbox ads"))
        assertEquals("收件匣", PatchMetadataLocalizer.category("Inbox"))

        assertEquals("隱藏貼文下方的 Meta AI 問題",
            PatchMetadataLocalizer.name("Hide Meta AI questions under posts"))
        assertEquals("隱藏 Feeds 標題列", PatchMetadataLocalizer.name("Hide the Feeds header"))
        assertEquals("保留貼文日期", PatchMetadataLocalizer.name("Keep post dates"))
        assertEquals("支援 x86 裝置啟動", PatchMetadataLocalizer.name("Start on x86 devices"))
        assertEquals("分頁列置底", PatchMetadataLocalizer.name("Tab bar at the bottom"))
        assertEquals("阻擋 Instant Games 廣告", PatchMetadataLocalizer.name("Block Instant Games ads"))
        assertEquals("阻擋截圖偵測", PatchMetadataLocalizer.name("Block screenshot detection"))
        assertEquals("隱藏分頁", PatchMetadataLocalizer.name("Hide tabs"))
        assertEquals("阻止分析資料上傳", PatchMetadataLocalizer.name("Hold back analytics uploads"))
        assertEquals("子母畫面", PatchMetadataLocalizer.name("Picture-in-picture"))
        assertEquals("Marketplace 賣家顯示「查看個人檔案」",
            PatchMetadataLocalizer.name("Show View profile on Marketplace sellers"))
        assertEquals("關閉 HDR 自動增亮", PatchMetadataLocalizer.name("Turn off HDR brightness"))
        assertEquals("關閉觸覺回饋", PatchMetadataLocalizer.name("Turn off haptics"))
        assertEquals("關閉畫面轉場", PatchMetadataLocalizer.name("Turn off screen transitions"))
        assertEquals(
            "讓 Facebook 原本禁止截圖或螢幕錄影的頁面也能正常出現在截圖、錄影與最近使用的 App 畫面中。選取補丁後開關預設開啟，位於「隱私」。",
            PatchMetadataLocalizer.description(
                "Allow screenshots",
                "Lets you take screenshots and record the screen on the pages Facebook blocks them on. Its switch starts on, under Privacy."
            )
        )
    }

    @Test
    fun `traditional chinese localizes Morphe universal patches`() {
        Locale.setDefault(Locale.forLanguageTag("zh-TW"))

        assertEquals("複製應用程式", PatchMetadataLocalizer.name("Clone app"))
        assertEquals("啟用 Android 偵錯", PatchMetadataLocalizer.name("Enable Android debugging"))
        assertEquals("移除連結驗證", PatchMetadataLocalizer.name("Remove link verification"))
        assertEquals("變更安裝來源", PatchMetadataLocalizer.name("Change installer source"))
        assertEquals("覆寫憑證綁定", PatchMetadataLocalizer.name("Override certificate pinning"))
        assertEquals("停用 Play 商店更新", PatchMetadataLocalizer.name("Disable Play Store updates"))
        assertEquals(
            "偽裝安裝來源，讓 App 看起來像是從應用程式商店安裝。",
            PatchMetadataLocalizer.description("Change installer source", null)
        )
    }

    @Test
    fun `hant locales also use traditional chinese`() {
        Locale.setDefault(Locale.Builder().setLanguage("zh").setScript("Hant").build())
        assertEquals("使用外部瀏覽器開啟網頁連結",
            PatchMetadataLocalizer.name("Open web links externally"))
    }

    @Test
    fun `messenger duplicate patch names keep messenger wording`() {
        Locale.setDefault(Locale.forLanguageTag("zh-TW"))

        assertEquals(
            "允許截取受保護的聊天媒體，包括僅限查看一次的媒體與 Quicksnap，並停止截圖通知；不會新增重新播放或儲存功能。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。",
            PatchMetadataLocalizer.description(
                "Allow screenshots",
                "Lets you screenshot protected chat media, including view-once media and Quicksnap, and stops screenshot notices. This doesn't add replay or saving. Long-press Messenger's home screen icon > Patch controls. Starts off."
            )
        )
        assertEquals(
            "Android 12 以上讓 Messenger 深色模式使用桌布配色；Android 11 使用固定藍色調。淺色模式不變。請先在 Messenger 開啟深色模式。",
            PatchMetadataLocalizer.description(
                "Material You theme",
                "Gives Messenger's dark mode the colors of your wallpaper on Android 12 and newer, and a fixed blue palette on Android 11. Light mode stays as it is. Turn on dark mode in Messenger first."
            )
        )
        assertEquals(
            "開啟其他人的限時動態時，不會把你加入對方的觀看名單；在你這一端仍會標記為已看。長按主畫面的 Messenger 圖示 → Patch controls 可設定。預設關閉。",
            PatchMetadataLocalizer.description(
                "View stories anonymously",
                "Opens other people's stories without adding you to their viewer list. Stories you open this way are marked as seen on your side. Long-press Messenger > Patch controls. Starts off."
            )
        )
        assertEquals("儲存任何限時動態", PatchMetadataLocalizer.name("Save any story"))
        assertEquals("聊天滑入滑出動畫", PatchMetadataLocalizer.name("Slide chats in and out"))
    }

    @Test
    fun `english keeps bundle metadata untouched`() {
        Locale.setDefault(Locale.US)
        assertEquals("Hide sponsored posts", PatchMetadataLocalizer.name("Hide sponsored posts"))
        assertEquals("Ads", PatchMetadataLocalizer.category("Ads"))
        assertEquals("original", PatchMetadataLocalizer.description("Hide sponsored posts", "original"))
    }

    @Test
    fun `unknown future patches safely fall back`() {
        Locale.setDefault(Locale.forLanguageTag("zh-TW"))
        assertEquals("Future patch", PatchMetadataLocalizer.name("Future patch"))
        assertEquals("Future category", PatchMetadataLocalizer.category("Future category"))
        assertEquals("Future description",
            PatchMetadataLocalizer.description("Future patch", "Future description"))
    }
}
