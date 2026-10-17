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
 * The "More from your friends" list under a post opened from a notification, for Hide suggested and
 * promoted posts (#163).
 *
 * <p>Facebook fetches a post opened from a notification on its own, and its query asks the server
 * whether to add a footer of more posts under it, a MobileConfig answer. The server builds that
 * footer as a Bloks list, ads among its cards, and it never passes the feed's edge filter. The patch
 * hands the answer here right before the query takes it. While the switch is on it's false, so the
 * server sends the post alone.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the answer is
 * Facebook's own.
 */
public final class NotificationFooter {
    /** Counted under the patch's name each time a post from a notification is asked for without the list. */
    static final String HIDDEN = "Notification post asked for without its list of more posts";

    private static final String FAMILY = FamilyNames.SUGGESTED_POSTS;

    private static final String HOOK = "notification post footer";

    private NotificationFooter() {
    }

    /**
     * Injection point, in the single-post query's builder, right before it boxes its answer for
     * {@code show_notification_permalink_footer}. Never throws.
     *
     * @param facebooks whether Facebook would ask for the list.
     * @return whether to ask for it.
     */
    public static boolean show(boolean facebooks) {
        try {
            HookStatus.invoked(FAMILY);
            if (!facebooks || !Utils.settingsReady() || !Settings.HIDE_NOTIFICATION_POST_FOOTER.get()) {
                Logger.printDebug(() -> "Notification post footer: kept Facebook's " + facebooks);
                return facebooks;
            }
            HookStatus.bound(FAMILY, HOOK);
            HookStatus.counted(FAMILY, HIDDEN);
            Logger.printDebug(() -> "Notification post footer: asked for the post alone");
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            Logger.printException(() -> "Notification post footer: could not read its switch", failure);
            return facebooks;
        }
    }
}
