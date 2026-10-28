/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The feed request's parameters reach the debug log once per distinct set, with words kept and
 * ids and other text replaced, and nothing at all with debug logging off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FeedRequestParamsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String CURSOR = "AQHRx9w/Lp+Zk==";
    private static final String VIEWER = "100012345678";

    /** Stands in for Facebook's parameter set: one public no-argument method answering a map. */
    public static final class ParamSet {
        final Map<String, Object> params = new TreeMap<>();

        public Map<String, Object> asMap() {
            return params;
        }
    }

    /** Stands in for a nested GraphQL input, read the same way. */
    static final class Input {
        public Map<String, Object> values() {
            Map<String, Object> values = new TreeMap<>();
            values.put("surface", "home");
            return values;
        }
    }

    /** An object answering two maps, which isn't guessed at. */
    public static final class TwoMaps {
        public Map<String, Object> one() {
            return new TreeMap<>();
        }

        public Map<String, Object> two() {
            return new TreeMap<>();
        }
    }

    @Before
    public void prepare() {
        FeedRequestParams.forget();
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
    }

    @After
    public void restore() {
        FeedRequestParams.forget();
        BaseSettings.DEBUG.resetToDefault();
        LogBufferManager.clearLogBuffer();
    }

    private static ParamSet friends() {
        ParamSet set = new ParamSet();
        set.params.put("feed_style", "MOST_RECENT_FRIENDS_FEED");
        set.params.put("orderby", Arrays.asList("most_recent"));
        set.params.put("skip_negative_feedback_actions", true);
        set.params.put("first", 10);
        set.params.put("client_time", 1760961600000L);
        set.params.put("viewer_id", VIEWER);
        set.params.put("after", CURSOR);
        set.params.put("render_location", new Input());
        set.params.put("unknown", new TwoMaps());
        return set;
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) found++;
        return found;
    }

    @Test
    public void aRequestsParametersAreLoggedWithWordsKeptAndTheRestReplaced() {
        FeedRequestParams.sent(friends());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains(FeedRequestParams.PREFIX + "1 part 1/1: {"));
        for (String kept : new String[] {
                "feed_style=MOST_RECENT_FRIENDS_FEED", "orderby=[most_recent]", "skip_negative_feedback_actions=true",
                "first=10", "client_time=<number>", "viewer_id=<id>", "after=<text>",
                "render_location={surface=home}", "unknown=<object>"}) {
            assertTrue(kept + " in " + report, report.contains(kept));
        }
        assertFalse("an id reached the log", report.contains(VIEWER));
        assertFalse("a cursor reached the log", report.contains(CURSOR));
    }

    @Test
    public void theSameParametersAreLoggedOnceAndANewSetAgain() {
        FeedRequestParams.sent(friends());
        FeedRequestParams.sent(friends());
        ParamSet home = friends();
        home.params.put("feed_style", "TOP_STORIES");
        FeedRequestParams.sent(home);
        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, count(report, "feed_style=MOST_RECENT_FRIENDS_FEED"));
        assertTrue(report, report.contains(FeedRequestParams.PREFIX + "2 part 1/1: "));
        assertTrue(report, report.contains("feed_style=TOP_STORIES"));
    }

    @Test
    public void setsThatDifferOnlyInNumbersAndTextAreOneShape() {
        FeedRequestParams.sent(friends());
        ParamSet nextPage = friends();
        nextPage.params.put("first", 25);
        nextPage.params.put("after", CURSOR + "x9QzR");
        FeedRequestParams.sent(nextPage);
        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, count(report, "feed_style=MOST_RECENT_FRIENDS_FEED"));
        assertFalse(report, report.contains("first=25"));
    }

    @Test
    public void wordsThatCouldBeIdsNamesOrTokensAreReplaced() {
        ParamSet set = new ParamSet();
        set.params.put("query_id", "a3f1c9e2-7b4d-4e1a-9c2f-0d8e6b5a4c3f");
        set.params.put("name", "Matt");
        set.params.put("token", "abc123def456");
        set.params.put("hex", "deadbeefcafe");
        set.params.put("count", "12345678");
        set.params.put("b4f2e9a1c7d3", "home");
        set.params.put("surface", "news_feed");
        FeedRequestParams.sent(set);
        String report = LogBufferManager.buildExportText();
        for (String replaced : new String[] {
                "query_id=<text>", "name=<text>", "token=<text>", "hex=<text>", "count=<id>", "<key>=home"}) {
            assertTrue(replaced + " in " + report, report.contains(replaced));
        }
        assertTrue(report, report.contains("surface=news_feed"));
        for (String secret : new String[] {"a3f1c9e2", "Matt", "abc123def456", "deadbeefcafe", "12345678", "b4f2e9a1c7d3"}) {
            assertFalse(secret + " reached the log", report.contains(secret));
        }
    }

    @Test
    public void onlyTheFirstShapesAreLogged() {
        for (int i = 0; i < FeedRequestParams.MAX_REQUESTS + 6; i++) {
            ParamSet set = new ParamSet();
            set.params.put("feed_style", "STYLE_" + (char) ('A' + i));
            FeedRequestParams.sent(set);
        }
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains(FeedRequestParams.PREFIX + FeedRequestParams.MAX_REQUESTS + " part 1/1: "));
        assertFalse(report, report.contains(FeedRequestParams.PREFIX + (FeedRequestParams.MAX_REQUESTS + 1) + " part"));
    }

    @Test
    public void aLongSetIsLoggedInParts() {
        ParamSet set = new ParamSet();
        for (int i = 0; i < 200; i++) {
            String word = "" + (char) ('a' + i / 26) + (char) ('a' + i % 26);
            set.params.put("param_" + word, "value_" + word);
        }
        FeedRequestParams.sent(set);
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains(FeedRequestParams.PREFIX + "1 part 1/"));
        assertTrue(report, report.contains(FeedRequestParams.PREFIX + "1 part 2/"));
        assertFalse("a part is longer than logcat keeps", report.contains(FeedRequestParams.PREFIX + "1 part 1/1:"));
    }

    @Test
    public void aSetWithoutOneMapSaysSo() {
        FeedRequestParams.sent(new TwoMaps());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("parameters unreadable from " + TwoMaps.class.getName()));
    }

    @Test
    public void nothingIsLoggedWithDebugLoggingOffOrNoSet() {
        BaseSettings.DEBUG.save(false);
        FeedRequestParams.sent(friends());
        BaseSettings.DEBUG.save(true);
        FeedRequestParams.sent(null);
        assertFalse(LogBufferManager.buildExportText().contains(FeedRequestParams.PREFIX));
    }
}
