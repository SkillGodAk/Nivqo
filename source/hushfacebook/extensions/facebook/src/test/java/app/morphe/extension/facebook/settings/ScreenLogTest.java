/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.List;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.DiagnosticRedactor;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * Screens opened: with Debug logging on, each screen that comes to the front is kept by class,
 * action and the link's host and path, up to the last fifty; with it off nothing is kept.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ScreenLogTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        ScreenLog.clearForTests();
    }

    @After
    public void restore() {
        BaseSettings.DEBUG.resetToDefault();
        ScreenLog.clearForTests();
    }

    private static Activity resumedWith(Intent intent) {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class, intent).setup();
        return controller.get();
    }

    @Test
    public void offNothingIsKept() {
        assertTrue("Debug logging doesn't start off", !BaseSettings.DEBUG.get());
        ScreenLog.resumed(resumedWith(new Intent(Intent.ACTION_VIEW, Uri.parse("fb://profile/4"))));
        assertTrue(ScreenLog.REPORT.lines().isEmpty());
    }

    @Test
    public void aScreenIsKeptByClassActionHostAndPathOnly() {
        BaseSettings.DEBUG.save(true);
        Activity screen = resumedWith(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://user:secret@m.facebook.com/watch/live?v=123&token=abc#frag")));
        ScreenLog.resumed(screen);
        ScreenLog.resumed(screen);
        List<String> lines = ScreenLog.report(SystemClock.elapsedRealtime() + 3_000);
        assertEquals("coming back to the same screen was written again", 1, lines.size());
        assertEquals("3 s before this report: android.app.Activity android.intent.action.VIEW m.facebook.com/watch/live",
                lines.get(0));
        assertTrue(ScreenLog.REPORT.isAppState());
    }

    @Test
    public void aScreenWithNoActionOrLinkIsItsClass() {
        assertEquals("com.facebook.katana.LoginActivity", ScreenLog.describe("com.facebook.katana.LoginActivity", null, null));
        assertEquals("X fb_shorts/viewer", ScreenLog.describe("X", "", Uri.parse("fb://fb_shorts/viewer?ref=1")));
    }

    /**
     * An app link's ids never reach the report: the redaction cuts only ids of fifteen digits or
     * more, and an older account's or a Page's id is shorter, so "profile/1234567890" would have
     * said whose profile was opened in a report pasted on GitHub.
     */
    @Test
    public void anAppLinksIdsAreWrittenAsHashes() {
        assertEquals("X profile/#", ScreenLog.describe("X", null, Uri.parse("fb://profile/1234567890")));
        assertEquals("X group/#/permalink/#",
                ScreenLog.describe("X", null, Uri.parse("fb://group/123456789/permalink/98765")));
        assertEquals("X page/#", ScreenLog.describe("X", null, Uri.parse("FB://page/u2b3x9")));
        assertEquals("X messenger/share", ScreenLog.describe("X", null, Uri.parse("fb-messenger://messenger/share")));
        for (String line : new String[]{ScreenLog.describe("X", null, Uri.parse("fb://profile/4")),
                ScreenLog.describe("X", null, Uri.parse("fb://story/12345?story_fbid=1"))}) {
            assertFalse(line, line.matches(".*\\d.*"));
        }
    }

    /**
     * A web link to a Meta site is kept by host and path, so the report's redaction has to know
     * every one of those sites; one it didn't know would put whose page was opened in the report.
     */
    @Test
    public void everyMetaSiteKeptIsCutByTheRedaction() {
        for (String site : ScreenLog.META_SITES) {
            String line = ScreenLog.describe("X", null, Uri.parse("https://www." + site + "/janedoe"));
            assertEquals(site, "X [host omitted]", DiagnosticRedactor.redact(line));
        }
    }

    /** A link that isn't Meta's is written as an outside link and nothing more: what was opened there is nobody's business. */
    @Test
    public void anOutsideLinkKeepsNeitherHostNorPath() {
        assertEquals("X android.intent.action.VIEW (outside link)",
                ScreenLog.describe("X", "android.intent.action.VIEW", Uri.parse("https://example.com/private/page?x=1")));
        assertEquals("X (outside link)", ScreenLog.describe("X", null, Uri.parse("content://media/external/images/media/12")));
        assertEquals("X (outside link)", ScreenLog.describe("X", null, Uri.parse("file:///storage/emulated/0/Download/results.pdf")));
        assertEquals("a lookalike host counts as outside", "X (outside link)",
                ScreenLog.describe("X", null, Uri.parse("https://facebook.com.example.net/a")));
        assertEquals("X www.facebook.com/groups/42", ScreenLog.describe("X", null, Uri.parse("https://www.facebook.com/groups/42")));
        assertEquals("X fb.watch/abc", ScreenLog.describe("X", null, Uri.parse("HTTPS://FB.watch/abc")));
        assertEquals("X messenger/share", ScreenLog.describe("X", null, Uri.parse("fb-messenger://messenger/share")));
    }

    @Test
    public void onlyTheLastFiftyAreKept() {
        for (int i = 0; i < ScreenLog.LIMIT + 7; i++) ScreenLog.add(0, "screen " + i);
        List<String> lines = ScreenLog.report(0);
        assertEquals(ScreenLog.LIMIT, lines.size());
        assertEquals("0 s before this report: screen 7", lines.get(0));
        assertEquals("0 s before this report: screen " + (ScreenLog.LIMIT + 6), lines.get(lines.size() - 1));
    }
}
