/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * Lets a new row into one of Facebook's lists when the only thing holding it back is the phone's
 * accessibility state changing while it was laid out.
 *
 * <p>Facebook's lists add rows that arrive while they're on screen in batches, and a batch goes in
 * only once every row in it has a layout. That check also wants the layout made with the
 * accessibility state the phone has now, but the code that would make a new layout skips the work
 * when the row already has one for the same size, whatever the accessibility state. So when the
 * state changes while a row waits (a screen reader turned on or off, a service connecting), the
 * row never gets the layout the check wants, its batch never goes in, and every row Facebook adds
 * after it queues behind it. On Home that's a list that keeps growing behind a screen that stopped
 * moving: more posts load, none show.
 *
 * <p>The list asks here when a row fails that check, with whether the row has a layout for its
 * size made in either accessibility state. Yes lets the row in, and when the list measures it,
 * Facebook lays it out again for the phone's current state, as it does for any row on screen after
 * such a change.
 *
 * <p>A layout started before the change and finished after it would leave the row with no layout
 * at all, as Facebook drops it rather than keep one made in the other state, and asks for no other.
 * So Facebook's layout step asks here too, with whether the layout fits, and yes keeps it for the
 * row's list to find. Paused, or before the settings are read, both decide as they ship.
 */
public final class HeldInserts {
    /** The diagnostic report's count of the rows this let in. */
    static final String LET_IN = "Let in a row Facebook held after an accessibility change";

    /** The diagnostic report's count of the layouts this kept. */
    static final String KEPT = "Kept a layout Facebook made before an accessibility change";

    private HeldInserts() {
    }

    /**
     * Injection point, where a list finds a new row without the layout it wants. Whether to let the
     * row in anyway. Never throws: false leaves the list's own answer.
     *
     * @param laidOut whether the row has a layout for its size, made in either accessibility state.
     */
    public static boolean letIn(boolean laidOut) {
        return agree(laidOut, LET_IN, "held rows");
    }

    /**
     * Injection point, where Facebook's layout step is about to drop a layout it finished because it
     * was made in the other accessibility state. Whether to keep it anyway. Runs on a layout thread
     * and never throws: false leaves the layout step's own answer.
     *
     * @param fits whether the layout fits the size it's for.
     */
    public static boolean keepLayout(boolean fits) {
        return agree(fits, KEPT, "held layouts");
    }

    private static boolean agree(boolean possible, String count, String hook) {
        try {
            if (!possible || !Utils.settingsReady() || Setting.isPaused()) return false;
            HookStatus.counted(TailFetch.family(), count);
            Logger.printDebug(() -> "Feed filter: " + count);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(TailFetch.family(), hook, failure);
            return false;
        }
    }
}
