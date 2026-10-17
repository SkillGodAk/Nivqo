/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.download.PostDetails;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Takes the Meta AI shortcut out of Facebook's Menu, where it sits with Feeds, Marketplace, Reels
 * and Groups above See more.
 *
 * <p>Those rows are a bookmarks folder section's list of bookmarks, the same list See more opens
 * in full. The patch hands that list to {@link #kept} the moment the section reads it, before it
 * splits off the rows shown above See more, so a bookmark taken out here leaves no gap: the next
 * one moves up. Each bookmark is a GraphQL model, its name read through the tree's kept
 * {@code getString} by the hash of {@code name}, as {@link MuseCard} reads the Muse card's.
 *
 * <p>Every other bookmark stays, in Facebook's order. Off, paused, before the settings are ready,
 * for a list with nothing to take out, or when anything here fails, the list is Facebook's own.
 */
public final class MenuShortcuts {
    /** The bookmark's name. Meta AI is a product name, the same in every language. */
    static final String META_AI = "Meta AI";

    /** The bookmark's GraphQL name field, keyed the way the tree keys its fields. */
    static final int NAME = "name".hashCode();

    /** The Hook status name of the hook. */
    static final String HOOK = "Menu shortcut list";

    /** The counter route: every bookmark in a list asked about, and the ones taken out. */
    static final String ROUTE = "Menu shortcuts";

    /** What a hidden Meta AI shortcut is counted under. */
    static final String HIDDEN = "Meta AI shortcut";

    private MenuShortcuts() {
    }

    /**
     * Injection point, in the Menu's bookmarks folder section right after it read its list of
     * bookmarks. Answers the same bookmarks in the same order without Meta AI's while the switch is
     * on, and [bookmarks] itself otherwise. The patch copies the answer back into an ImmutableList.
     * Never throws.
     */
    @Nullable
    public static List<?> kept(@Nullable List<?> bookmarks) {
        try {
            HookStatus.invoked(FamilyNames.MENU_PROMOTIONS);
            if (bookmarks == null || bookmarks.isEmpty()) return bookmarks;
            // Ready first: Settings loads every switch, and it can't before the context is set.
            if (!Utils.settingsReady() || !Settings.HIDE_MENU_META_AI.get()) return bookmarks;
            HookStatus.bound(FamilyNames.MENU_PROMOTIONS, HOOK);
            FeedFilterCounters.sawList(ROUTE, bookmarks.size());
            List<Object> kept = null;
            for (int index = 0; index < bookmarks.size(); index++) {
                Object bookmark = bookmarks.get(index);
                if (isMetaAi(bookmark)) {
                    if (kept == null) kept = new ArrayList<>(bookmarks.subList(0, index));
                } else if (kept != null) {
                    kept.add(bookmark);
                }
            }
            if (kept == null) return bookmarks;
            FeedFilterCounters.removed(ROUTE, bookmarks.size() - kept.size(), HIDDEN);
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MENU_PROMOTIONS, HOOK, failure);
            return bookmarks;
        }
    }

    /** Whether [bookmark] is a live bookmark model named Meta AI. A released one is never read. */
    private static boolean isMetaAi(@Nullable Object bookmark) {
        return PostDetails.isLiveTree(bookmark) && isMetaAiName(PostDetails.string(bookmark, NAME));
    }

    /** Whether a bookmark named [name] is the Meta AI shortcut's. */
    static boolean isMetaAiName(@Nullable String name) {
        return name != null && META_AI.equalsIgnoreCase(name.trim());
    }
}
