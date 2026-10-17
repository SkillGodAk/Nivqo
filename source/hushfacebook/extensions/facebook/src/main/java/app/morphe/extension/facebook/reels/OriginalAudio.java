/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Keeps reels and videos in the audio they were made with, for Clean up Reels (#148).
 *
 * <p>Facebook dubs some reels and videos into the viewer's language with Meta AI and serves the
 * dubbed tracks beside the original. One getter of its video player answers which audio language
 * to play: a language picked in the player, the reels dubbing language saved in its preferences, or
 * else the app's or the phone's language. The player asks it as it reads a video's manifest, where
 * it keeps the dubbed track in that language and drops the rest, and when it sets the language its
 * track selection prefers and the languages a prefetch asks for. The patch hands each answer here.
 * While the switch is on an answer comes back empty, the value the player already treats as no
 * language asked for: the manifest keeps the video's main audio track, which is the original.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the answer is
 * Facebook's own.
 */
public final class OriginalAudio {
    /** Counted under the patch's name each time the player is told to keep the original audio. */
    static final String KEPT = "Video kept in its original audio";

    private static final String FAMILY = FamilyNames.REEL_DECLUTTER;

    private static final String HOOK = "original audio";

    private OriginalAudio() {
    }

    /**
     * Injection point, in the player's audio language getter, right after it reads its answer and
     * while it still holds its lock, so this stays quick and never calls back into the player.
     * Never throws.
     *
     * @param facebook the language Facebook's getter answered, empty for none.
     * @return the language for the player to pick its audio by.
     */
    @Nullable
    public static String language(@Nullable String facebook) {
        try {
            HookStatus.invoked(FAMILY);
            if (facebook == null || facebook.isEmpty() || !Utils.settingsReady() || !Settings.ORIGINAL_AUDIO.get()) {
                return facebook;
            }
            HookStatus.bound(FAMILY, HOOK);
            HookStatus.counted(FAMILY, KEPT);
            return "";
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            Logger.printException(() -> "Original audio: could not read its switch", failure);
            return facebook;
        }
    }
}
