/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Upload Stories in HD: with its switch on, a Story video's upload quality goes to the uploader as
 * high and is counted, and a high or raw of Facebook's own stays. Off, paused, or before the
 * settings are ready, Facebook's value goes on untouched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StoryUploadTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.UPLOAD_STORIES_IN_HD.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.STORY_UPLOAD_HD + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOffAndBelongsToItsPatch() {
        assertFalse("Upload Stories in HD doesn't start off", Settings.UPLOAD_STORIES_IN_HD.defaultValue);
        assertEquals(FamilyNames.STORY_UPLOAD_HD, PatchFamily.STORY_UPLOAD_HD.patchName);
        assertTrue(PatchFamily.STORY_UPLOAD_HD.switches.contains(Settings.UPLOAD_STORIES_IN_HD));
    }

    @Test
    public void onAStoryVideoAsksForHighQualityAndIsCounted() {
        Settings.UPLOAD_STORIES_IN_HD.save(true);
        assertEquals("a standard Story upload stayed standard", "high", StoryUpload.quality("standard"));
        assertEquals("a Story upload with no quality got none", "high", StoryUpload.quality(null));
        assertEquals("a medium Story upload stayed medium", "high", StoryUpload.quality("medium"));
        assertEquals("Facebook's own high changed", "high", StoryUpload.quality("high"));
        assertEquals("Facebook's raw upload, which skips the encode, changed", "raw", StoryUpload.quality("raw"));
        assertEquals(FamilyNames.STORY_UPLOAD_HD + ": invoked 5, 1 found, 0 missing. Counted: " + StoryUpload.RAISED + " 3",
                statusLine());
    }

    @Test
    public void offPausedOrColdFacebooksQualityGoesOn() {
        assertEquals("off, a standard Story upload changed", "standard", StoryUpload.quality("standard"));
        assertNull("off, a Story upload with no quality got one", StoryUpload.quality(null));

        Settings.UPLOAD_STORIES_IN_HD.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertEquals("a Hushfacebook paused by " + reason + " raised a Story upload",
                    "standard", StoryUpload.quality("standard"));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertEquals(
                "a Story upload raised before the settings were ready", "standard", StoryUpload.quality("standard")));

        String line = statusLine();
        assertFalse("a quality left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertEquals("on again after the pause, a Story upload stayed standard", "high", StoryUpload.quality("standard"));
    }
}
