/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Skip silent parts (#144). Facebook plays videos, reels and stories through ExoPlayer, and each
 * of its audio sinks runs the sound through ExoPlayer's chain of audio processors, one of which
 * cuts out the silent stretches when it's told to (SilenceSkippingAudioProcessor). Facebook never
 * tells it to. Each time a sink starts sound, after a seek or a new track too, it hands the chain
 * its skip-silence flag, and that hand-off asks here first. While the switch is on the answer is
 * yes. The chain then counts the frames it cut, and the sink's clock, which the picture follows,
 * moves on by them, so the video keeps up with its sound.
 *
 * <p>ExoPlayer skips silence in plain PCM sound only. Sound handed to the phone undecoded
 * (passthrough) or through a tunneled decoder plays as Facebook sends it whatever this answers.
 * The sink keeps the flag Facebook gave it, so a change shows from the next seek or video. Nothing
 * here may throw into Facebook's player: off, paused, before the settings are ready or when
 * anything here fails, the chain gets Facebook's flag unchanged.
 */
public final class SkipSilence {
    /** Counted under the patch's name each time a sink's sound starts with silent parts skipped. */
    static final String SKIPPING = "Sound set to skip silent parts";

    private static final String FAMILY = FamilyNames.SKIP_SILENCE;

    private SkipSilence() {
    }

    /**
     * Injection point, right before each audio sink hands its processor chain the skip-silence
     * flag [asked]. Answers the flag the chain gets: yes while the switch is on, else [asked].
     */
    public static boolean skipSilence(boolean asked) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, "audio sink");
            if (asked || !Utils.settingsReady() || !Settings.SKIP_SILENT_PARTS.get()) return asked;
            HookStatus.counted(FAMILY, SKIPPING);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "audio sink", failure);
            return asked;
        }
    }
}
