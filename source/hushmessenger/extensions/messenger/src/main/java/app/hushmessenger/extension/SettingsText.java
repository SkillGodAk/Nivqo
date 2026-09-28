package app.hushmessenger.extension;

import android.app.LocaleManager;
import android.content.Context;
import android.os.Build;
import android.os.LocaleList;
import android.text.TextUtils;
import java.util.Locale;

/** Text owned by the extension; no IDs from Messenger's resource table are used. */
final class SettingsText {
    static final String LANGUAGE_SYSTEM = "system";
    static final String LANGUAGE_TRADITIONAL_CHINESE = "zh-TW";
    static final String LANGUAGE_ENGLISH = "en";

    private static final String[][] TRADITIONAL = {
        {"Controls", "控制項"}, {"App", "應用程式"}, {"All", "全部"}, {"Inbox", "收件匣"},
        {"Chats", "聊天"}, {"More", "其他"}, {"Navigation", "導覽"}, {"Stickers", "貼圖"},
        {"Conversations", "對話"}, {"Links and bubbles", "連結與聊天泡泡"},
        {"HushMessenger settings", "HushMessenger 設定"}, {"HushMessenger UI preview", "HushMessenger 介面預覽"},
        {"UI preview. These switches don't change Messenger.", "介面預覽。這些開關不會修改 Messenger。"},
        {"Make Messenger yours.", "讓 Messenger 更符合你的使用方式。"}, {"Open", "開啟"},
        {"Open Messenger", "開啟 Messenger"}, {"Apply inbox changes with App > Restart Messenger.", "收件匣變更會在「應用程式」>「重新啟動 Messenger」後套用。"},
        {"QUICK ACCESS", "快速操作"}, {"Open HushMessenger settings from Messenger settings. Use Restart Messenger after changing inbox controls.", "請從 Messenger 設定開啟 HushMessenger 設定。修改收件匣控制項後，請使用「重新啟動 Messenger」。"},
        {"Restart Messenger", "重新啟動 Messenger"}, {"Restarting Messenger...", "正在重新啟動 Messenger…"},
        {"Couldn't restart. Close Messenger, then open it again.", "無法重新啟動。請關閉 Messenger，然後重新開啟。"},
        {"Couldn't save your choices. Messenger wasn't restarted. Try again.", "無法儲存選擇，因此沒有重新啟動 Messenger。請再試一次。"},
        {"YOUR SETUP", "目前設定"}, {"Pause all changes", "暫停所有變更"}, {"Find a control", "尋找控制項"},
        {"Find the controls you need", "尋找需要的控制項"}, {"Try a different search or category. Only patches included in this installation appear here.", "請改用其他搜尋字詞或分類。這裡只會顯示本次安裝包含的補丁。"},
        {"Clear filters", "清除篩選"}, {"Unavailable on this Android version. Your choice is kept.", "此 Android 版本無法使用。你的選擇會保留。"},
        {"Experimental", "實驗性"}, {"%s on", "%s：開啟"}, {"%s off", "%s：關閉"},
        {"APPEARANCE", "外觀"}, {"Light theme", "淺色主題"}, {"Use a light background in settings.", "在設定頁使用淺色背景。"},
        {"Dark by default. Your choice stays saved.", "預設使用深色主題。你的選擇會儲存。"}, {"ABOUT HUSHMESSENGER", "關於 HUSHMESSENGER"},
        {"Version", "版本"}, {"Installed controls", "已安裝控制項"}, {"Copy setup", "複製設定"},
        {"Copies app versions and control choices. No account or chat details. Nothing is sent.", "複製應用程式版本與控制項選擇。不包含帳號或聊天內容，也不會傳送任何資料。"},
        {"USING YOUR CONTROLS", "使用控制項"}, {"Changes save as you go. Use Restart Messenger after changing inbox controls. Your account stays signed in.", "變更會自動儲存。修改收件匣控制項後，請使用「重新啟動 Messenger」。帳號會保持登入。"},
        {"Pause keeps your choices and temporarily restores stock behavior.", "暫停會保留你的選擇，並暫時恢復原始行為。"},
        {"Your choices apply to every Messenger account in this installation.", "你的選擇會套用到此安裝中的所有 Messenger 帳號。"},
        {"Missing a control?", "找不到控制項？"}, {"Select it in Morphe, then rebuild Messenger. Updating the source alone doesn't install new controls.", "請在 Morphe 選取後重新建置 Messenger。只更新來源不會安裝新的控制項。"},
        {"Source and licenses", "來源與授權"}, {"No browser is available", "沒有可用的瀏覽器"},
        {"GPL-3.0. Includes work from De-Vanced, ReVanced, Doom and Messenger Cleaner.", "GPL-3.0。包含 De-Vanced、ReVanced、Doom 與 Messenger Cleaner 的工作。"},
        {"Independent of Meta and Morphe.", "獨立於 Meta 與 Morphe。"}, {"HushMessenger setup", "HushMessenger 設定"},
        {"Setup copied", "設定已複製"}, {"Couldn't copy setup. Try again.", "無法複製設定。請再試一次。"},
        {"Changes paused", "變更已暫停"}, {"%d control enabled", "已啟用 %d 個控制項"}, {"%d controls enabled", "已啟用 %d 個控制項"},
        {"%d saved choice. Turn pause off to resume.", "已儲存 %d 個選擇。關閉暫停即可恢復。"}, {"%d saved choices. Turn pause off to resume.", "已儲存 %d 個選擇。關閉暫停即可恢復。"},
        {"Your choices are saved automatically.", "你的選擇會自動儲存。"}, {"No optional controls installed. Select patches in Morphe and rebuild Messenger.", "尚未安裝選用控制項。請在 Morphe 選取補丁並重新建置 Messenger。"},
        {"No matching controls. Try another search.", "找不到符合的控制項。請換個搜尋字詞。"}, {"%d of %d installed control", "已安裝控制項：%d / %d"},
        {"%d of %d installed controls", "已安裝控制項：%d / %d"}, {"Open Messenger", "開啟 Messenger"},
        {"Language", "語言"}, {"Follow system", "跟隨系統"}, {"English", "English"},
        {"Choose the language used by HushMessenger. Tap the button to cycle Follow system, 繁體中文 and English.", "選擇 HushMessenger 使用的語言。點擊按鈕可在「跟隨系統」、「繁體中文」與 English 之間切換。"},
        {"Hide inbox ads", "隱藏收件匣廣告"}, {"Supported inbox ad cards. Live removal isn't verified yet.", "支援收件匣廣告卡片；即時移除效果尚未驗證。"},
        {"Hide People You May Know", "隱藏「你可能認識的人」"}, {"Removes suggested people from chats and Notifications.", "從聊天與通知分頁移除好友建議。"},
        {"Hide friend request cards", "隱藏交友邀請卡片"}, {"Hides cards without accepting or rejecting requests.", "隱藏交友邀請卡片，不會接受或拒絕邀請。"},
        {"Hide growth prompts", "隱藏成長推廣提示"}, {"Removes add-more-people prompts.", "移除邀請加入更多聯絡人的提示。"},
        {"Hide inbox promotions", "隱藏收件匣推廣"}, {"Hides Messenger's quick-promotion banners in the chat list.", "隱藏聊天列表中的 Messenger 快速推廣橫幅。"},
        {"Hide stories and notes", "隱藏限時動態與便利貼"}, {"Removes the horizontal tray above your chats.", "移除聊天上方的橫向列。"},
        {"Hide inbox tabs", "隱藏收件匣分頁"}, {"Hides the Home and Channels tabs inside the inbox.", "隱藏收件匣中的首頁與頻道分頁。"},
        {"Hide Facebook shortcuts", "隱藏 Facebook 捷徑"}, {"Hides Facebook buttons, profile shortcuts and sharing shortcuts.", "隱藏 Facebook 按鈕、個人檔案與分享捷徑。"},
        {"Hide Meta AI buttons", "隱藏 Meta AI 按鈕"}, {"Hides the floating button, toolbar button and AI menu entries. Search and existing AI chats stay available.", "隱藏浮動按鈕、工具列按鈕與 AI 選單項目；搜尋與既有 AI 聊天仍可使用。"},
        {"Hide Chat Moments", "隱藏 Chat Moments"}, {"Hides Chat Moments from the menu.", "隱藏選單中的 Chat Moments。"},
        {"Hide Reels badge", "隱藏 Reels 徽章"}, {"Hides the Reels notification badge.", "隱藏 Reels 通知徽章。"},
        {"Hide AI sticker tools", "隱藏 AI 貼圖工具"}, {"Hides the generated-sticker tab and AI sticker suggestions.", "隱藏產生貼圖分頁與 AI 貼圖建議。"},
        {"Hide avatar stickers", "隱藏虛擬替身貼圖"}, {"Hides the avatar tab in the sticker keyboard.", "隱藏貼圖鍵盤中的虛擬替身分頁。"},
        {"Hide chat promotions", "隱藏聊天推廣"}, {"Hides Messenger's quick-promotion banners inside conversations.", "隱藏對話中的 Messenger 快速推廣橫幅。"},
        {"Hide business reply suggestions", "隱藏商家回覆建議"}, {"Hides suggested replies in business conversations.", "隱藏商家對話中的建議回覆。"},
        {"Hide business typing suggestions", "隱藏商家輸入建議"}, {"Hides business suggestions as you type.", "輸入文字時隱藏商家相關建議。"},
        {"Hide event prompts", "隱藏活動提示"}, {"Hides event quick-promotion prompts inside chats.", "隱藏聊天中的活動快速推廣提示。"},
        {"Hide typing indicator", "隱藏輸入中狀態"}, {"Stops your outgoing active-typing signal. Messages and read receipts are separate.", "停止傳送你的「正在輸入」狀態；訊息與已讀回條不受影響。"},
        {"Open web links externally", "使用外部瀏覽器開啟網頁連結"}, {"Uses your default browser for HTTP and HTTPS links. Other link types keep their original behavior.", "HTTP 與 HTTPS 連結使用預設瀏覽器；其他連結維持原本行為。"},
        {"Allow chat bubbles", "允許聊天泡泡"}, {"Removes the low-memory restriction on Android 11 or newer. Enable bubbles in Android notification settings too.", "移除 Android 11 以上的低記憶體限制；也請在 Android 通知設定中啟用泡泡。"},
    };

    private final boolean expanded, rtl, traditionalChinese;
    private final Locale locale;
    private final String languageMode;

    SettingsText(Context context) { this(requestedLocale(context), readLanguageMode(context)); }

    private static String readLanguageMode(Context context) {
        try {
            if (Settings.preferences != null) return Settings.preferences.getString("language_mode", LANGUAGE_SYSTEM);
        } catch (RuntimeException ignored) { }
        return context.getSharedPreferences("hushmessenger", Context.MODE_PRIVATE)
            .getString("language_mode", LANGUAGE_SYSTEM);
    }

    private static Locale requestedLocale(Context context) {
        // Follow system means the Android system language, not Messenger's own resource context
        // or an app-specific locale. Fall back to the context only if the system list is absent.
        try {
            LocaleList requested = LocaleList.getDefault();
            if (!requested.isEmpty()) return requested.get(0);
        } catch (Throwable ignored) { }
        return context.getResources().getConfiguration().getLocales().get(0);
    }

    SettingsText(Locale locale) { this(locale, LANGUAGE_SYSTEM); }

    SettingsText(Locale requested, String mode) {
        languageMode = LANGUAGE_ENGLISH.equals(mode) || LANGUAGE_TRADITIONAL_CHINESE.equals(mode) ? mode : LANGUAGE_SYSTEM;
        boolean systemChinese = LANGUAGE_SYSTEM.equals(languageMode) && "zh".equalsIgnoreCase(requested.getLanguage());
        traditionalChinese = LANGUAGE_TRADITIONAL_CHINESE.equals(languageMode) || systemChinese;
        locale = traditionalChinese ? Locale.TAIWAN : (LANGUAGE_ENGLISH.equals(languageMode) ? Locale.ENGLISH : requested);
        expanded = "en-XA".equalsIgnoreCase(requested.toLanguageTag()) && LANGUAGE_SYSTEM.equals(languageMode);
        rtl = "ar-XB".equalsIgnoreCase(requested.toLanguageTag()) && LANGUAGE_SYSTEM.equals(languageMode);
    }

    boolean isPseudo() { return expanded || rtl; }
    boolean isTraditionalChinese() { return traditionalChinese; }
    String languageMode() { return languageMode; }
    String languageLabel() {
        return LANGUAGE_TRADITIONAL_CHINESE.equals(languageMode) ? get("language_zh_tw") :
            LANGUAGE_ENGLISH.equals(languageMode) ? get("language_en") : get("language_system");
    }

    int layoutDirection() { return TextUtils.getLayoutDirectionFromLocale(locale); }

    String get(String id, Object... arguments) { return display(String.format(locale, base(id), arguments)); }
    String count(String id, int count) { return get(id + (count == 1 ? "_one" : "_many"), count); }
    String number(int count) { return String.format(locale, "%d", count); }

    String display(String english) {
        if (english.isEmpty()) return english;
        if (traditionalChinese) return traditionalize(english);
        if (rtl) return "\u202e" + english.replaceAll("\\p{N}+(?:[.,\u066b\u066c]\\p{N}+)*", "\u2066$0\u2069") + "\u202c";
        if (!expanded) return english;
        String plain = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String accented = "áḃçďéḟģĥíĵķĺḿńóṕqŕśţúṽẃẋýź";
        accented += accented.toUpperCase(Locale.ROOT);
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < english.length(); i++) {
            int index = plain.indexOf(english.charAt(i));
            result.append(index < 0 ? english.charAt(i) : accented.charAt(index));
        }
        int padding = Math.max(1, (english.length() + 11) / 12);
        for (int i = 0; i < padding; i++) result.append(" one");
        return result.append(']').toString();
    }

    private String traditionalize(String english) {
        String result = english;
        for (String[] pair : TRADITIONAL) result = result.replace(pair[0], pair[1]);
        return result;
    }

    String base(String id) {
        switch (id) {
            case "controls": return "Controls";
            case "app": return "App";
            case "all": return "All";
            case "inbox": return "Inbox";
            case "chats": return "Chats";
            case "more": return "More";
            case "navigation": return "Navigation";
            case "stickers": return "Stickers";
            case "conversations": return "Conversations";
            case "links_bubbles": return "Links and bubbles";
            case "settings": return "HushMessenger settings";
            case "preview_title": return "HushMessenger UI preview";
            case "preview_notice": return "UI preview. These switches don't change Messenger.";
            case "tagline": return "Make Messenger yours.";
            case "open": return "Open";
            case "open_messenger": return "Open Messenger";
            case "reopen": return "Apply inbox changes with App > Restart Messenger.";
            case "quick_access": return "QUICK ACCESS";
            case "access_help": return "Open HushMessenger settings from Messenger settings. Use Restart Messenger after changing inbox controls.";
            case "restart": return "Restart Messenger";
            case "restarting": return "Restarting Messenger...";
            case "restart_unavailable": return "Couldn't restart. Close Messenger, then open it again.";
            case "restart_save_failed": return "Couldn't save your choices. Messenger wasn't restarted. Try again.";
            case "setup": return "YOUR SETUP";
            case "paused": return "Pause all changes";
            case "search": return "Find a control";
            case "empty_title": return "Find the controls you need";
            case "empty_help": return "Try a different search or category. Only patches included in this installation appear here.";
            case "clear": return "Clear filters";
            case "unavailable": return "Unavailable on this Android version. Your choice is kept.";
            case "experimental": return "Experimental";
            case "choice_on": return "%s on";
            case "choice_off": return "%s off";
            case "appearance": return "APPEARANCE";
            case "light": return "Light theme";
            case "light_help": return "Use a light background in settings.";
            case "theme_help": return "Dark by default. Your choice stays saved.";
            case "about": return "ABOUT HUSHMESSENGER";
            case "version": return "Version";
            case "installed": return "Installed controls";
            case "copy": return "Copy setup";
            case "copy_help": return "Copies app versions and control choices. No account or chat details. Nothing is sent.";
            case "usage": return "USING YOUR CONTROLS";
            case "save_help": return "Changes save as you go. Use Restart Messenger after changing inbox controls. Your account stays signed in.";
            case "pause_help": return "Pause keeps your choices and temporarily restores stock behavior.";
            case "account_help": return "Your choices apply to every Messenger account in this installation.";
            case "missing": return "Missing a control?";
            case "missing_help": return "Select it in Morphe, then rebuild Messenger. Updating the source alone doesn't install new controls.";
            case "source": return "Source and licenses";
            case "no_browser": return "No browser is available";
            case "credits": return "GPL-3.0. Includes work from De-Vanced, ReVanced, Doom and Messenger Cleaner.";
            case "independent": return "Independent of Meta and Morphe.";
            case "clipboard": return "HushMessenger setup";
            case "copied": return "Setup copied";
            case "copy_failed": return "Couldn't copy setup. Try again.";
            case "changes_paused": return "Changes paused";
            case "enabled_one": return "%d control enabled";
            case "enabled_many": return "%d controls enabled";
            case "saved_one": return "%d saved choice. Turn pause off to resume.";
            case "saved_many": return "%d saved choices. Turn pause off to resume.";
            case "saved": return "Your choices are saved automatically.";
            case "none_installed": return "No optional controls installed. Select patches in Morphe and rebuild Messenger.";
            case "no_matches": return "No matching controls. Try another search.";
            case "results_one": return "%d of %d installed control";
            case "results_many": return "%d of %d installed controls";
            case "open_help": return "Open Messenger";
            case "language": return "Language";
            case "language_system": return "Follow system";
            case "language_zh_tw": return "繁體中文";
            case "language_en": return "English";
            case "language_help": return "Choose the language used by HushMessenger. Tap the button to cycle Follow system, 繁體中文 and English.";
            default: throw new IllegalArgumentException("Unknown settings text: " + id);
        }
    }
}
