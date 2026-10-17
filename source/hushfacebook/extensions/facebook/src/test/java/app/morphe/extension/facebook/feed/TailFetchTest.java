/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/** Home's extra tail fetch: when it asks for the next page that the scroll fetcher wouldn't. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TailFetchTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for GraphQLFeedStoryCategory: only the constant names matter to the guard. */
    enum Category { ORGANIC, SPONSORED }

    @Before
    public void startClean() {
        TailFetch.resetForTests();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        TailFetch.resetForTests();
        FeedFilterCounters.clear();
    }

    /** Before the guard has hidden anything the fetcher decides as it ships, end of the list or not. */
    @Test
    public void nothingHiddenAsksNothing() {
        assertFalse(TailFetch.askAgain(9, 10, 1_000));
        assertFalse(FeedGuardForTests.hides(Category.ORGANIC, new Object()));
        assertFalse("a kept edge isn't a hidden one", TailFetch.askAgain(9, 10, 1_000));
    }

    /**
     * Once the guard hides an edge, the end of the list on screen asks again, and only the end: a row
     * above the last, or a list with no rows, leaves the fetcher's answer.
     */
    @Test
    public void aHiddenEdgeMakesTheEndOfTheListAsk() {
        assertTrue(FeedGuardForTests.hides(Category.SPONSORED, new Object()));
        assertFalse("not at the end", TailFetch.askAgain(7, 10, 1_000));
        assertFalse("no rows yet", TailFetch.askAgain(0, 0, 1_000));
        assertTrue("the loading row on screen", TailFetch.askAgain(9, 10, 1_000));
    }

    /** At most one ask every RETRY_MS, however often the fetcher runs at the end of the list. */
    @Test
    public void asksAreRateLimited() {
        TailFetch.hidEdge();
        assertTrue(TailFetch.askAgain(9, 10, 5_000));
        assertFalse(TailFetch.askAgain(9, 10, 5_000 + TailFetch.RETRY_MS - 1));
        assertFalse(TailFetch.askAgain(10, 11, 5_000 + TailFetch.RETRY_MS / 2));
        assertTrue(TailFetch.askAgain(9, 10, 5_000 + TailFetch.RETRY_MS));
    }

    /** Paused, the fetcher decides as it ships even after the guard hid something. */
    @Test
    public void pausedAsksNothing() {
        TailFetch.hidEdge();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(TailFetch.askAgain(9, 10, 1_000));
        PauseForTests.resume();
        assertTrue(TailFetch.askAgain(9, 10, 1_000));
    }
}
