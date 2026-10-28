/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Incoming comment typing (#170): with Hide who is typing in comments on, the count of people
 * typing that Facebook read is answered as zero, counted. Off, paused, before the settings are
 * ready, or for a count that is already zero, the count goes back unchanged.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class IncomingCommentTypingTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_INCOMING_COMMENT_TYPING.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.COMMENT_SHEET_OPTIONS + ":")) return line;
        }
        return null;
    }

    @Test
    public void startsOffAndLeavesTheCountAlone() {
        assertFalse(Settings.HIDE_INCOMING_COMMENT_TYPING.get());
        assertEquals(3, CommentSheetOptions.incomingTypers(3));
        String line = statusLine();
        assertNotNull(line);
        assertFalse("counted with the switch off: " + line, line.contains(CommentSheetOptions.TYPING_HIDDEN));
    }

    @Test
    public void onAnswersZeroAndCounts() {
        Settings.HIDE_INCOMING_COMMENT_TYPING.save(true);
        assertEquals(0, CommentSheetOptions.incomingTypers(1));
        assertEquals(0, CommentSheetOptions.incomingTypers(7));
        assertTrue(statusLine().contains(CommentSheetOptions.TYPING_HIDDEN));
    }

    @Test
    public void aCountOfZeroIsLeftAndNotCounted() {
        Settings.HIDE_INCOMING_COMMENT_TYPING.save(true);
        assertEquals(0, CommentSheetOptions.incomingTypers(0));
        assertFalse(statusLine().contains(CommentSheetOptions.TYPING_HIDDEN));
    }

    @Test
    public void pausedLeavesTheCountToFacebook() {
        Settings.HIDE_INCOMING_COMMENT_TYPING.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals(4, CommentSheetOptions.incomingTypers(4));
    }

    @Test
    public void beforeTheSettingsAreReadyTheCountStays() {
        Settings.HIDE_INCOMING_COMMENT_TYPING.save(true);
        SettingsContextRule.withoutContext(() -> assertEquals(2, CommentSheetOptions.incomingTypers(2)));
    }
}
