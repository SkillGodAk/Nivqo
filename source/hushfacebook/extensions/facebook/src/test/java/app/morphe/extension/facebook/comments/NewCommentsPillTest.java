/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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
 * The "new comments" pill (#185): with Hide the new comments pop-up on, the pill's text is answered
 * empty, counted, and Facebook skips a pill with no text. Off, paused, before the settings are
 * ready, or for a text that is already empty, the text goes back unchanged.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class NewCommentsPillTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_NEW_COMMENTS_PILL.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.COMMENT_SHEET_OPTIONS + ":")) return line;
        }
        return null;
    }

    @Test
    public void startsOffAndLeavesThePillAlone() {
        assertFalse(Settings.HIDE_NEW_COMMENTS_PILL.get());
        assertEquals("3 new comments", CommentSheetOptions.newCommentsPill("3 new comments"));
        String line = statusLine();
        assertNotNull(line);
        assertTrue("the hook didn't report itself bound: " + line, line.contains("1 found"));
        assertFalse("counted with the switch off: " + line, line.contains(CommentSheetOptions.PILL_HIDDEN));
    }

    @Test
    public void onAnswersAnEmptyTextAndCounts() {
        Settings.HIDE_NEW_COMMENTS_PILL.save(true);
        assertEquals("", CommentSheetOptions.newCommentsPill("1 new comment"));
        assertEquals("", CommentSheetOptions.newCommentsPill("12 bình luận mới"));
        assertTrue(statusLine().contains(CommentSheetOptions.PILL_HIDDEN));
    }

    @Test
    public void anEmptyOrMissingTextIsLeftAndNotCounted() {
        Settings.HIDE_NEW_COMMENTS_PILL.save(true);
        assertEquals("", CommentSheetOptions.newCommentsPill(""));
        assertNull(CommentSheetOptions.newCommentsPill(null));
        assertFalse(statusLine().contains(CommentSheetOptions.PILL_HIDDEN));
    }

    @Test
    public void pausedLeavesThePillToFacebook() {
        Settings.HIDE_NEW_COMMENTS_PILL.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("4 new comments", CommentSheetOptions.newCommentsPill("4 new comments"));
    }

    @Test
    public void beforeTheSettingsAreReadyThePillStays() {
        Settings.HIDE_NEW_COMMENTS_PILL.save(true);
        SettingsContextRule.withoutContext(
                () -> assertEquals("2 new comments", CommentSheetOptions.newCommentsPill("2 new comments")));
    }

    /** The newer pill's data (#185): off it goes back as built, on it's answered null and counted. */
    @Test
    public void theNewerPillsDataGoesBackOffAndIsDroppedOn() {
        Object data = new Object();
        assertSame(data, CommentSheetOptions.newCommentsPillData(data));
        String line = statusLine();
        assertNotNull(line);
        assertTrue("the hook didn't report itself bound: " + line, line.contains("1 found"));
        assertFalse("counted with the switch off: " + line, line.contains(CommentSheetOptions.PILL_HIDDEN));

        Settings.HIDE_NEW_COMMENTS_PILL.save(true);
        assertNull(CommentSheetOptions.newCommentsPillData(data));
        assertTrue(statusLine().contains(CommentSheetOptions.PILL_HIDDEN));
    }

    @Test
    public void aMissingNewerPillIsLeftAndNotCounted() {
        Settings.HIDE_NEW_COMMENTS_PILL.save(true);
        assertNull(CommentSheetOptions.newCommentsPillData(null));
        assertFalse(statusLine().contains(CommentSheetOptions.PILL_HIDDEN));
    }

    @Test
    public void pausedOrNotReadyLeavesTheNewerPillToFacebook() {
        Settings.HIDE_NEW_COMMENTS_PILL.save(true);
        Object data = new Object();
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(data, CommentSheetOptions.newCommentsPillData(data));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame(data, CommentSheetOptions.newCommentsPillData(data)));
        assertFalse(statusLine().contains(CommentSheetOptions.PILL_HIDDEN));
    }
}
