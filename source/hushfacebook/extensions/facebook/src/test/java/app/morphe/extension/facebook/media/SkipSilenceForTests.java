/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

/**
 * Skip silent parts' menu row, for a test outside this package: a video started through Resume
 * long videos' start hook, the way the patched player reports one, and everything forgotten after.
 */
public final class SkipSilenceForTests {
    private SkipSilenceForTests() {
    }

    /** Starts [videoId] in a stand-in player 12 seconds in, and hands that player back. */
    public static ResumePlaybackForTests.Player playing(String videoId) {
        ResumePlaybackForTests.install();
        ResumePlaybackForTests.Player player =
                new ResumePlaybackForTests.Player(new ResumePlaybackForTests.Params(videoId, 60_000));
        player.position = 12_000;
        ResumePlayback.started(player, ResumePlaybackForTests.Trigger.BY_USER);
        return player;
    }

    /** Forgets the playing video, any row's pick and the stand-in player. */
    public static void forget() {
        SkipSilence.forget();
        ResumePlaybackForTests.forget();
    }
}
