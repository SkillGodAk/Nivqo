/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.api.feedtype.FeedType;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.navigation.FeedsSubtab;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Following feed on Home: with the switch on, Home's feed type comes back as the most recent
 * feed's, or the chosen filter's, counted, while every other feed type, the Feeds tab's filters and the Following feed's
 * among them, comes back as it was and is counted by name. Off, paused, or before the settings are
 * ready, Home keeps its own and nothing is counted.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FollowingHomeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.FOLLOWING_FEED_HOME.resetToDefault();
        Settings.HOME_FEED.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.FOLLOWING_HOME + ":")) return line;
        }
        return null;
    }

    @Test
    public void onHomeAsksForTheMostRecentFeedAndOtherFeedsStay() {
        Settings.FOLLOWING_FEED_HOME.save(true);
        assertSame("Home kept the ranked feed", FeedType.MOST_RECENT, FollowingHome.feedType(FeedType.TOP_STORIES));
        assertSame("a second request went out for the ranked feed",
                FeedType.MOST_RECENT, FollowingHome.feedType(FeedType.TOP_STORIES));
        for (FeedType filter : new FeedType[] {FeedType.FAVORITES, FeedType.MOST_RECENT, FeedType.MOST_RECENT_ALL,
                FeedType.MOST_RECENT_FRIEND, FeedType.FOLLOWING_FEED}) {
            assertSame("the " + filter + " feed was changed", filter, FollowingHome.feedType(filter));
        }
        assertNull("no feed type came back as one", FollowingHome.feedType(null));
        FeedType odd = new FeedType("Not A Feed Name!");
        assertSame("a feed type of another name was changed", odd, FollowingHome.feedType(odd));
        assertEquals(FamilyNames.FOLLOWING_HOME + ": invoked 9, 1 found, 0 missing. Counted: "
                + FollowingHome.SWAPPED + " 2, "
                + FollowingHome.LEFT_ALONE + "favorites 1, "
                + FollowingHome.LEFT_ALONE + "most_recent 1, "
                + FollowingHome.LEFT_ALONE + "most_recent_all 1, "
                + FollowingHome.LEFT_ALONE + "most_recent_friend 1, "
                + FollowingHome.LEFT_ALONE + "following_feed 1, "
                + FollowingHome.LEFT_ALONE + "another type 1", statusLine());
    }

    /**
     * A family's report keeps 16 named counts. Past eight feed types counted by name, the rest are
     * counted together, so Home's own count still finds room however many feeds a session asks for.
     */
    @Test
    public void manyOtherFeedsLeaveRoomForHomesOwnCount() {
        Settings.FOLLOWING_FEED_HOME.save(true);
        for (int i = 0; i < 12; i++) FollowingHome.feedType(new FeedType("other_feed_" + i));
        Settings.HOME_FEED.save(FeedsSubtab.GROUPS);
        assertSame(FeedType.MOST_RECENT_GROUP, FollowingHome.feedType(FeedType.TOP_STORIES));
        Settings.HOME_FEED.save(FeedsSubtab.ALL);
        assertSame(FeedType.MOST_RECENT, FollowingHome.feedType(FeedType.TOP_STORIES));

        String line = statusLine();
        assertTrue(line, line.contains(FollowingHome.LEFT_ALONE + "other_feed_7 1, "
                + FollowingHome.LEFT_ALONE + "another type 4, "
                + FollowingHome.SWAPPED_TO + "most_recent_group 1, "
                + FollowingHome.SWAPPED + " 1"));
        assertFalse(line, line.contains("other_feed_8"));

        // An emptied report starts its names over.
        HookStatus.clear();
        FollowingHome.feedType(new FeedType("other_feed_11"));
        assertTrue(statusLine(), statusLine().endsWith("Counted: " + FollowingHome.LEFT_ALONE + "other_feed_11 1"));
    }

    /**
     * With a filter chosen, Home's request carries that filter's most recent feed type, counted by
     * name, and the Feeds tab's own requests stay as they came. A choice changed between requests
     * is taken by the next one, and All is the most recent feed again.
     */
    @Test
    public void aChosenFilterIsWhatHomeAsksFor() {
        Settings.FOLLOWING_FEED_HOME.save(true);
        Settings.HOME_FEED.save(FeedsSubtab.FRIENDS);
        assertSame("Home didn't ask for the friends feed", FeedType.MOST_RECENT_FRIEND, FollowingHome.feedType(FeedType.TOP_STORIES));
        assertSame("the Feeds tab's Friends filter was changed", FeedType.MOST_RECENT_FRIEND,
                FollowingHome.feedType(FeedType.MOST_RECENT_FRIEND));
        assertSame("the Feeds tab's All was changed", FeedType.MOST_RECENT, FollowingHome.feedType(FeedType.MOST_RECENT));

        Settings.HOME_FEED.save(FeedsSubtab.FAVORITES);
        assertSame("Favorites didn't ask for the most recent list's name first",
                FeedType.MOST_RECENT_FAVORITES, FollowingHome.feedType(FeedType.TOP_STORIES));
        Settings.HOME_FEED.save(FeedsSubtab.GROUPS);
        assertSame(FeedType.MOST_RECENT_GROUP, FollowingHome.feedType(FeedType.TOP_STORIES));
        Settings.HOME_FEED.save(FeedsSubtab.PAGES);
        assertSame(FeedType.MOST_RECENT_PAGE, FollowingHome.feedType(FeedType.TOP_STORIES));
        Settings.HOME_FEED.save(FeedsSubtab.ALL);
        assertSame(FeedType.MOST_RECENT, FollowingHome.feedType(FeedType.TOP_STORIES));

        assertEquals(FamilyNames.FOLLOWING_HOME + ": invoked 7, 1 found, 0 missing. Counted: "
                + FollowingHome.SWAPPED_TO + "most_recent_friend 1, "
                + FollowingHome.LEFT_ALONE + "most_recent_friend 1, "
                + FollowingHome.LEFT_ALONE + "most_recent 1, "
                + FollowingHome.SWAPPED_TO + "most_recent_favorites 1, "
                + FollowingHome.SWAPPED_TO + "most_recent_group 1, "
                + FollowingHome.SWAPPED_TO + "most_recent_page 1, "
                + FollowingHome.SWAPPED + " 1", statusLine());

        Settings.FOLLOWING_FEED_HOME.save(false);
        Settings.HOME_FEED.save(FeedsSubtab.FRIENDS);
        assertSame("off, a chosen filter changed Home's feed", FeedType.TOP_STORIES, FollowingHome.feedType(FeedType.TOP_STORIES));
        Settings.FOLLOWING_FEED_HOME.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame("paused, a chosen filter changed Home's feed", FeedType.TOP_STORIES, FollowingHome.feedType(FeedType.TOP_STORIES));
    }

    /**
     * When the chosen filter's feed types aren't there, Home falls back to looking for the most
     * recent feed, as All does, and the report names the filter first. A Home feed type of a class
     * that isn't Facebook's has neither, so it comes back as it was, with both counted missing.
     */
    @Test
    public void aFilterThatIsntThereFallsBackToAll() {
        Settings.FOLLOWING_FEED_HOME.save(true);
        Settings.HOME_FEED.save(FeedsSubtab.FAVORITES);
        Object home = new Object() {
            @Override
            public String toString() {
                return "top_stories";
            }
        };
        assertSame("a feed type with nothing to swap to wasn't handed back", home, FollowingHome.feedType(home));
        String line = statusLine();
        assertTrue("the report doesn't count both misses and name the filter first: " + line,
                line.contains("2 missing. First missing: home feed " + FollowingHome.FEED_TYPE_CLASS
                        + "#most_recent_favorites or favorites"));
        assertFalse("a swap was counted: " + line, line.contains("Counted"));
    }

    @Test
    public void offOrPausedHomeKeepsItsOwnFeed() {
        assertFalse("the switch doesn't start off", Settings.FOLLOWING_FEED_HOME.get());
        assertSame("off, Home asked for the most recent feed", FeedType.TOP_STORIES, FollowingHome.feedType(FeedType.TOP_STORIES));

        Settings.FOLLOWING_FEED_HOME.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertSame("a Hushfacebook paused by " + reason + " changed Home's feed",
                    FeedType.TOP_STORIES, FollowingHome.feedType(FeedType.TOP_STORIES));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertSame("Home's feed changed before the settings were ready",
                FeedType.TOP_STORIES, FollowingHome.feedType(FeedType.TOP_STORIES)));

        Settings.FOLLOWING_FEED_HOME.save(false);
        assertSame(FeedType.FAVORITES, FollowingHome.feedType(FeedType.FAVORITES));
        Settings.FOLLOWING_FEED_HOME.save(true);
        String line = statusLine();
        assertFalse("a request left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertSame("on again after the pause, Home kept the ranked feed",
                FeedType.MOST_RECENT, FollowingHome.feedType(FeedType.TOP_STORIES));
    }

    @Test
    public void theSwitchNeedsNoRestartAndTravelsWithItsFamily() {
        assertFalse("Home asks for its feed on every load, so no restart is needed", Settings.FOLLOWING_FEED_HOME.rebootApp);
        assertNull("nothing asks before the switch changes", Settings.FOLLOWING_FEED_HOME.userDialogMessage);
        assertTrue("Pause and the report don't know the switch",
                PatchFamily.FOLLOWING_HOME.switches.contains(Settings.FOLLOWING_FEED_HOME));
    }
}
