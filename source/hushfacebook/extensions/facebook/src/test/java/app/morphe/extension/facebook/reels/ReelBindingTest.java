/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.lang.reflect.Field;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Reel binding evidence: with Debug logging on, each binding of the Reels auto-advance controller
 * and each video end is written as a fixed label with a running count, judged by identity. Nothing
 * about the controller or the player gets out, repeats are sampled, and Debug off or settings not
 * ready keeps nothing at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelBindingTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private static final String PRIVATE = "PRIVATE_445566 https://private.invalid/reel";

    /** An object whose text is private and which equals every other one, so only identity tells them apart. */
    private static final class Opaque {
        @Override
        public boolean equals(Object other) {
            return other instanceof Opaque;
        }

        @Override
        public int hashCode() {
            return 1;
        }

        @Override
        public String toString() {
            return PRIVATE;
        }
    }

    @Before
    public void prepare() {
        BaseSettings.DEBUG.save(true);
        PauseForTests.resume();
        ReelBinding.resetForTests();
        ShadowLog.clear();
        LogBufferManager.clearLogBuffer();
    }

    @After
    public void restore() {
        BaseSettings.DEBUG.resetToDefault();
        PauseForTests.resume();
        ReelBinding.resetForTests();
        LogBufferManager.clearLogBuffer();
    }

    /** The exported report, checked first for anything private in it or in logcat. */
    private String evidence() {
        String result = LogBufferManager.buildExportText();
        assertFalse("an object's text escaped", result.contains("PRIVATE_445566"));
        for (ShadowLog.LogItem line : ShadowLog.getLogs()) {
            if (line.tag.contains(ReelBinding.SOURCE)) assertFalse(line.msg, line.msg.contains("PRIVATE_445566"));
        }
        return result;
    }

    private static String line(String label, int samples) {
        return ReelBinding.PREFIX + label + " samples=" + samples;
    }

    @Test
    public void aVideoEndingOnTheHeldPlayerReadsAsBound() {
        Object controller = new Opaque();
        Object player = new Opaque();
        ReelBinding.bound(controller, player);
        ReelBinding.ended(player);
        String logs = evidence();
        assertTrue(logs, logs.contains(line(ReelBinding.BOUND, 1)));
        assertTrue(logs, logs.contains(line(ReelBinding.ENDED_BOUND, 1)));
    }

    @Test
    public void aPlayerTheControllerLetGoReadsAsBoundBeforeAndAnEqualOneDoesNotCount() {
        Object controller = new Opaque();
        Object first = new Opaque();
        Object second = new Opaque();
        ReelBinding.bound(controller, first);
        ReelBinding.bound(controller, second);
        ReelBinding.ended(first);
        ReelBinding.ended(new Opaque());
        String logs = evidence();
        assertFalse("an equal player read as the held one", logs.contains(ReelBinding.REBOUND));
        assertTrue(logs, logs.contains(line(ReelBinding.ENDED_STALE, 1)));
        assertTrue(logs, logs.contains(line(ReelBinding.ENDED_UNBOUND, 1)));
        assertFalse(logs, logs.contains(ReelBinding.ENDED_BOUND));
    }

    @Test
    public void theSamePlayerAgainNoPlayerAndAMoveEachHaveTheirOwnLabel() {
        Object controller = new Opaque();
        Object other = new Opaque();
        Object player = new Opaque();
        ReelBinding.bound(controller, player);
        ReelBinding.bound(controller, player);
        ReelBinding.bound(other, player);
        ReelBinding.ended(player);
        ReelBinding.bound(other, null);
        ReelBinding.ended(player);
        String logs = evidence();
        assertTrue(logs, logs.contains(line(ReelBinding.REBOUND, 1)));
        assertTrue(logs, logs.contains(line(ReelBinding.MOVED, 1)));
        assertTrue("the controller it moved to holds it", logs.contains(line(ReelBinding.ENDED_BOUND, 1)));
        assertTrue(logs, logs.contains(line(ReelBinding.UNBOUND, 1)));
        assertTrue("let go by both", logs.contains(line(ReelBinding.ENDED_STALE, 1)));
    }

    /** The report files it under its own name with the other diagnostics, not under feed. */
    @Test
    public void theLineGoesUnderTheReportsOtherCategoryByItsOwnName() {
        ReelBinding.ended(new Opaque());
        boolean filed = false;
        for (String line : evidence().split("\n")) {
            if (!line.contains(ReelBinding.PREFIX)) continue;
            assertTrue(line, line.startsWith(DiagnosticCategory.OTHER.value + " | "));
            assertTrue(line, line.contains(" | " + ReelBinding.SOURCE + " | "));
            filed = true;
        }
        assertTrue("no binding line in the report", filed);
    }

    @Test
    public void repeatsAreWrittenOnceThenEveryFiftieth() {
        Object controller = new Opaque();
        for (int i = 0; i < 50; i++) ReelBinding.bound(controller, new Opaque());
        String logs = evidence();
        assertTrue(logs, logs.contains(line(ReelBinding.BOUND, 1)));
        assertFalse(logs, logs.contains(line(ReelBinding.BOUND, 2)));
        assertTrue(logs, logs.contains(line(ReelBinding.BOUND, 50)));
    }

    @Test
    public void debugOffKeepsNothingSoALaterEndReadsAsNeverBound() {
        BaseSettings.DEBUG.save(false);
        Object player = new Opaque();
        ReelBinding.bound(new Opaque(), player);
        ReelBinding.ended(player);
        assertFalse(evidence().contains(ReelBinding.PREFIX));
        BaseSettings.DEBUG.save(true);
        ReelBinding.ended(player);
        String logs = evidence();
        assertTrue(logs, logs.contains(line(ReelBinding.ENDED_UNBOUND, 1)));
        assertFalse(logs, logs.contains(line(ReelBinding.BOUND, 1)));
    }

    @Test
    public void beforeTheSettingsAreReadyNothingIsKept() throws Exception {
        Field ready = Utils.class.getDeclaredField("settingsReady");
        ready.setAccessible(true);
        boolean saved = ready.getBoolean(null);
        Object player = new Opaque();
        try {
            ready.setBoolean(null, false);
            ReelBinding.bound(new Opaque(), player);
            ReelBinding.ended(player);
            assertFalse(evidence().contains(ReelBinding.PREFIX));
        } finally {
            ready.setBoolean(null, saved);
        }
        ReelBinding.ended(player);
        assertTrue(evidence().contains(line(ReelBinding.ENDED_UNBOUND, 1)));
    }

    @Test
    public void nullsFromFacebookAreIgnored() {
        ReelBinding.bound(null, new Opaque());
        ReelBinding.ended(null);
        assertFalse(evidence().contains(ReelBinding.PREFIX));
    }
}
