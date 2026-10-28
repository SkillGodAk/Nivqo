/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Clean up Facebook's chat list patch asks while Facebook's own Chats builds its list.
 *
 * <p>Five hooks for three switches. {@link #notesTiles} sits where the state behind the row of notes and
 * active friends above the chats stores its tiles, and hands back no tiles while Hide the notes and active now row
 * is on. {@link #hidesNotesRow} sits first in the section that draws that row, and answers yes with the same
 * switch, which the patch turns into the section's own empty result, so your own note tile goes too.
 * {@link #hidesPromotion} sits first in the show question of each promotion banner at the
 * top of Chats, and answers yes while Hide chat list promotions is on, which the patch turns into
 * a no for Facebook. {@link #metaAiButton} takes the Meta AI button the search row just built and
 * hands back none while Hide the Meta AI button in Chats is on, and {@link #showsSearchMetaAiButton}
 * does the same for the search screen's button by answering its row's flag. All of them fail open: with the
 * switch off, a pause, settings that aren't ready or any failure in here, Facebook gets exactly
 * what it would have built.
 */
public final class ChatList {
    /** The diagnostic counter routes: each time Chats built the piece, and what was taken out. */
    static final String NOTES_ROUTE = "Chat notes tray";
    static final String NOTES_ROW_ROUTE = "Chat notes row";
    static final String PROMOTIONS_ROUTE = "Chat list promotions";
    static final String META_AI_ROUTE = "Chats Meta AI button";

    /** What a hidden piece is counted under. */
    static final String NOTES_HIDDEN = "Hide the notes and active now row";
    static final String PROMOTIONS_HIDDEN = "Hide chat list promotions";
    static final String META_AI_HIDDEN = "Hide the Meta AI button in Chats";

    private ChatList() {
    }

    /**
     * Injection point, right before the tile state stores its list. The tiles Facebook built, or
     * an empty list while the notes switch is on. Never throws.
     */
    @Nullable
    public static List<?> notesTiles(@Nullable List<?> tiles) {
        try {
            HookStatus.invoked(FamilyNames.CHAT_LIST);
            if (tiles == null || tiles.isEmpty()) return tiles;
            FeedFilterCounters.sawList(NOTES_ROUTE, tiles.size());
            if (!Utils.settingsReady() || !Settings.HIDE_CHAT_NOTES_TRAY.get()) return tiles;
            FeedFilterCounters.removed(NOTES_ROUTE, tiles.size(), NOTES_HIDDEN);
            return Collections.emptyList();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_LIST, "notes tray tiles", failure);
            return tiles;
        }
    }

    /**
     * Injection point, first thing in the render of the section that draws the notes row. True has
     * the section hand back its own empty result, so the row isn't drawn and leaves no gap. Never throws.
     */
    public static boolean hidesNotesRow() {
        try {
            HookStatus.invoked(FamilyNames.CHAT_LIST);
            FeedFilterCounters.sawList(NOTES_ROW_ROUTE, 1);
            if (!Utils.settingsReady() || !Settings.HIDE_CHAT_NOTES_TRAY.get()) return false;
            FeedFilterCounters.removed(NOTES_ROW_ROUTE, 1, NOTES_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_LIST, "notes row", failure);
            return false;
        }
    }

    /**
     * Injection point, first thing in a promotion banner's show question. True answers no for
     * Facebook, so the banner isn't shown. Never throws.
     */
    public static boolean hidesPromotion() {
        try {
            HookStatus.invoked(FamilyNames.CHAT_LIST);
            FeedFilterCounters.sawList(PROMOTIONS_ROUTE, 1);
            if (!Utils.settingsReady() || !Settings.HIDE_CHAT_PROMOTIONS.get()) return false;
            FeedFilterCounters.removed(PROMOTIONS_ROUTE, 1, PROMOTIONS_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_LIST, "promotion banner", failure);
            return false;
        }
    }

    /**
     * Injection point, right after the search row at the top of Chats builds its Meta AI button,
     * itself or through a helper it hands the button's builder to (584).
     * The button Facebook built, or null while the Meta AI switch is on, which the row already
     * takes as no button and leaves the search box alone. Never throws.
     */
    @Nullable
    public static Object metaAiButton(@Nullable Object button) {
        try {
            HookStatus.invoked(FamilyNames.CHAT_LIST);
            if (button == null) return null;
            FeedFilterCounters.sawList(META_AI_ROUTE, 1);
            if (!Utils.settingsReady() || !Settings.HIDE_CHAT_META_AI_BUTTON.get()) return button;
            FeedFilterCounters.removed(META_AI_ROUTE, 1, META_AI_HIDDEN);
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_LIST, "Meta AI button", failure);
            return button;
        }
    }

    /**
     * Injection point, right before the Chats search screen's row tests its Meta AI flag to build
     * the button, on every way there, so {@code shows} is often already false. False while the Meta
     * AI switch is on, so the button isn't built. The row has already given the search box the end
     * margin it keeps beside the button, so the box still stops short of the screen's edge. Never throws.
     */
    public static boolean showsSearchMetaAiButton(boolean shows) {
        try {
            HookStatus.invoked(FamilyNames.CHAT_LIST);
            if (!shows) return false;
            FeedFilterCounters.sawList(META_AI_ROUTE, 1);
            if (!Utils.settingsReady() || !Settings.HIDE_CHAT_META_AI_BUTTON.get()) return true;
            FeedFilterCounters.removed(META_AI_ROUTE, 1, META_AI_HIDDEN);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_LIST, "search screen Meta AI button", failure);
            return shows;
        }
    }
}
