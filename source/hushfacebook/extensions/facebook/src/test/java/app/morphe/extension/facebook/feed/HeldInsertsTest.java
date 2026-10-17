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
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/** A list's held row: when it goes in although its layout was made in the other accessibility state. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class HeldInsertsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void startClean() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    /** A row with a layout for its size goes in, and the report counts it; one without waits. */
    @Test
    public void aLaidOutRowGoesInAndIsCounted() {
        assertFalse("no layout yet: the list waits for it", HeldInserts.letIn(false));
        assertTrue(HeldInserts.letIn(true));
        assertTrue(HeldInserts.letIn(true));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(HeldInserts.LET_IN + " 2"));
    }

    /** A finished layout that fits is kept, and the report counts it apart from the rows let in. */
    @Test
    public void aFittingLayoutIsKeptAndCounted() {
        assertFalse("a layout for another size: dropped as Facebook does", HeldInserts.keepLayout(false));
        assertTrue(HeldInserts.keepLayout(true));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(HeldInserts.KEPT + " 1"));
        assertFalse(report, report.contains(HeldInserts.LET_IN));
    }

    /** Paused, the list and the layout step decide as they ship, so a held row stays held. */
    @Test
    public void pausedLetsNothingIn() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(HeldInserts.letIn(true));
        assertFalse(HeldInserts.keepLayout(true));
        PauseForTests.resume();
        assertTrue(HeldInserts.letIn(true));
        assertTrue(HeldInserts.keepLayout(true));
    }
}
