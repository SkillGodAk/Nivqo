/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The Picture-in-picture row Picture-in-picture adds to a reel's More sheet, the one with Playback
 * speed, Captions and Clear mode, so the window opens when you pick it instead of only when you
 * leave Facebook (#44). It's built with Facebook's own row class, like the Download row there.
 *
 * <p>The row's click type is a Facebook interface whose name changes every build, so the patch makes
 * this class implement it while patching and adds its one method, which calls {@link #run}.
 */
public final class PictureInPictureRow {
    private static final String FAMILY = FamilyNames.PICTURE_IN_PICTURE;

    /** Counted each time a reel's More sheet gets the row. */
    static final String ROW_ADDED = "More sheet with Picture-in-picture";

    private final WeakReference<Activity> screen;

    private PictureInPictureRow(Activity screen) {
        this.screen = new WeakReference<>(screen);
    }

    /**
     * Injection point, asked each time a reel's More sheet is built, with the Context the sheet is
     * built in: the row's handler, or null to leave the sheet as Facebook built it. Off, paused,
     * before the settings are ready, with no window armed, or on a screen other than the armed one,
     * the answer is null. Never throws.
     */
    @Nullable
    public static PictureInPictureRow forSheet(@Nullable Context context) {
        try {
            Activity screen = PictureInPicture.menuScreen(context);
            if (screen == null) return null;
            HookStatus.bound(FAMILY, "More menu row");
            HookStatus.counted(FAMILY, ROW_ADDED);
            return new PictureInPictureRow(screen);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "More menu row", failure);
            return null;
        }
    }

    /** The row's title. */
    public static String label() {
        try {
            return L10n.t("Picture-in-picture");
        } catch (Throwable failure) {
            return "Picture-in-picture";
        }
    }

    /**
     * The row's tap, through the method the patch adds for Facebook's click interface. The window
     * opens once the tap's own dispatch is done, so the sheet the row was in has closed. Never throws.
     */
    public void run() {
        try {
            Activity target = screen.get();
            Utils.runOnMainThread(() -> PictureInPicture.enterFromMenu(target));
        } catch (Throwable failure) {
            // The sheet's click runs inside Facebook's own dispatch, so nothing may leave here.
            HookStatus.threw(FAMILY, "More menu row tap", failure);
            Logger.printDebug(() -> "Picture-in-picture: the More menu row's tap failed");
        }
    }
}
