/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The Skip silent parts row in a reel's More sheet, the one with Playback speed, Captions and Clear
 * mode (#144): one tap keeps the silent parts in that reel, another skips them again. It's built
 * with Facebook's own row class, like the Download and Picture-in-picture rows there.
 *
 * <p>The row's click type is a Facebook interface whose name changes every build, so the patch makes
 * this class implement it while patching and adds its one method, which calls {@link #run}.
 */
public final class SkipSilenceRow {
    private static final String FAMILY = FamilyNames.SKIP_SILENCE;

    private final String target;

    private SkipSilenceRow(String target) {
        this.target = target;
    }

    /**
     * Injection point, asked each time a reel's More sheet is built, with the reel's video id: the
     * row's handler, or null to leave the sheet as Facebook built it. Off, paused, before the
     * settings are ready or before any video started, the answer is null. Never throws.
     */
    @Nullable
    public static SkipSilenceRow forReel(@Nullable String videoId) {
        try {
            String target = SkipSilence.rowFor(videoId);
            if (target == null) return null;
            HookStatus.bound(FAMILY, "More menu row");
            return new SkipSilenceRow(target);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "More menu row", failure);
            return null;
        }
    }

    /** The row's title: what the reel's silent parts do now. */
    public String title() {
        try {
            return SkipSilence.rowTitle(target);
        } catch (Throwable failure) {
            return "Skip silent parts";
        }
    }

    /** The row's tap, through the method the patch adds for Facebook's click interface. Never throws. */
    public void run() {
        try {
            SkipSilence.flip(null, target);
        } catch (Throwable failure) {
            // The sheet's click runs inside Facebook's own dispatch, so nothing may leave here.
            HookStatus.threw(FAMILY, "More menu row tap", failure);
            Logger.printDebug(() -> "Skip silent parts: the More menu row's tap failed");
        }
    }
}
