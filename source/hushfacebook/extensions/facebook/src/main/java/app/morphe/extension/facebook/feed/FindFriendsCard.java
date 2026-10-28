/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The "Want to see more posts from friends? Find friends" card where the feed runs out of friends'
 * posts, for Hide suggested and promoted posts (#187).
 *
 * <p>It's no feed unit, so the edge filter never sees it. Facebook builds the end of the feed as one
 * section of parts, and adds this card only when the section's settings carry a string for it. The
 * patch hands that string here as it's read. While the switch is on the answer is null, the same
 * answer Facebook gives itself on feeds that skip the card, so the section ends without it.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the string is
 * Facebook's own.
 */
public final class FindFriendsCard {
    /** Counted under the patch's name each time the end of the feed is built without the card. */
    static final String HIDDEN = "End of the feed built without the Find friends card";

    private static final String FAMILY = FamilyNames.SUGGESTED_POSTS;

    private static final String HOOK = "end of feed friends card";

    private FindFriendsCard() {
    }

    /**
     * Injection point, in the end of feed section's builder, right after it reads the string its
     * friends card is built from. Never throws.
     *
     * @param facebooks the string Facebook would build the card from, null when it has no card.
     * @return the string to build the card from, null for no card.
     */
    public static String unit(String facebooks) {
        try {
            HookStatus.invoked(FAMILY);
            if (facebooks == null || !Utils.settingsReady() || !Settings.HIDE_FIND_FRIENDS_CARD.get()) {
                Logger.printDebug(() -> "Find friends card: kept Facebook's " + (facebooks == null ? "none" : "card"));
                return facebooks;
            }
            HookStatus.bound(FAMILY, HOOK);
            HookStatus.counted(FAMILY, HIDDEN);
            Logger.printDebug(() -> "Find friends card: left out of the end of the feed");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            Logger.printException(() -> "Find friends card: could not read its switch", failure);
            return facebooks;
        }
    }
}
