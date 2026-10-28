/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

/** Hands the reels loading page hook a feed reel's viewer, for tests in any package. */
public final class FeedVideoAloneForTests {
    private FeedVideoAloneForTests() {
    }

    /**
     * A reels viewer opened from the home feed reads that more reels are coming. True when the hook
     * said none are, which is the switch changing what Facebook would have done.
     */
    public static boolean dropsTheLoadingPage() {
        FeedVideoAlone.originForTests = params -> "newsfeed::feed_story";
        try {
            return !FeedVideoAlone.moreReelsComing(true, new Object());
        } finally {
            FeedVideoAlone.forgetForTests();
        }
    }
}
