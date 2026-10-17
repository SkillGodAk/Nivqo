/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Upload Stories in HD patch asks before a Story's upload is set up.
 *
 * <p>When a post goes only to your Story, Facebook's publisher copies each video's upload quality
 * into the upload's settings under {@code video_upload_quality}. The video uploader takes high,
 * low, medium, standard or raw from there, but by the time it asks the upload server how to
 * encode the video it keeps only high and drops the rest, so a Story video is set up at the
 * server's standard quality. While the switch is on, the quality handed over is high. A raw
 * upload is left alone: it skips the encode altogether.
 *
 * <p>Off, paused, settings that aren't ready yet or a failure in here, and Facebook's own value
 * goes on.
 */
public final class StoryUpload {
    /** The quality the upload server is asked for. */
    static final String HIGH = "high";

    /** Facebook's own way to skip the encode, which stays when it's what Facebook picked. */
    static final String RAW = "raw";

    /** Counted each time a Story video's upload asks for high quality instead of Facebook's pick. */
    static final String RAISED = "story upload set to high quality";

    private StoryUpload() {
    }

    /**
     * Injection point, right before the publisher puts a Story video's upload quality into the
     * upload's settings: the quality to put there. Never throws.
     */
    @Nullable
    public static String quality(@Nullable String facebooks) {
        try {
            HookStatus.invoked(FamilyNames.STORY_UPLOAD_HD);
            HookStatus.bound(FamilyNames.STORY_UPLOAD_HD, "story upload");
            if (!Utils.settingsReady() || !Settings.UPLOAD_STORIES_IN_HD.get()) return facebooks;
            if (HIGH.equals(facebooks) || RAW.equals(facebooks)) return facebooks;
            HookStatus.counted(FamilyNames.STORY_UPLOAD_HD, RAISED);
            Logger.printInfo(() -> "Upload Stories in HD: a Story video asks for high quality instead of "
                    + (facebooks == null ? "Facebook's default" : facebooks));
            return HIGH;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_UPLOAD_HD, "story upload quality", failure);
            return facebooks;
        }
    }
}
