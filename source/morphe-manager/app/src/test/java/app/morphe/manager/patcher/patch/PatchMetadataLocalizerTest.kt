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
