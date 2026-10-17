/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
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
 * Clean up Reels' Keep the original audio switch: on, the player's audio language comes back empty,
 * so a video's manifest keeps its main audio rather than a dubbed one; off, paused or before the
 * settings are ready, Facebook's language goes through untouched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class OriginalAudioTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.ORIGINAL_AUDIO.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_DECLUTTER + ":")) return line;
        }
        return "";
    }

    @Test
    public void theSwitchStartsOffAndOffKeepsFacebooksLanguage() {
        assertFalse(Settings.ORIGINAL_AUDIO.defaultValue);
        assertTrue(PatchFamily.REEL_DECLUTTER.switches.contains(Settings.ORIGINAL_AUDIO));
        assertEquals("es", OriginalAudio.language("es"));
        assertFalse(statusLine().contains(OriginalAudio.KEPT));
    }

    @Test
    public void onAsksForNoLanguage() {
        Settings.ORIGINAL_AUDIO.save(true);
        assertEquals("", OriginalAudio.language("es"));
        String line = statusLine();
        assertTrue(line, line.contains(OriginalAudio.KEPT + " 1"));
        assertTrue(line, line.contains("invoked 1, 1 found, 0 missing"));
    }

    /** Facebook's code reads the answer's length, so none of these may turn into null. */
    @Test
    public void noLanguageStaysAsItCame() {
        Settings.ORIGINAL_AUDIO.save(true);
        assertEquals("", OriginalAudio.language(""));
        assertNull(OriginalAudio.language(null));
        assertFalse(statusLine().contains(OriginalAudio.KEPT));
    }

    @Test
    public void pausedKeepsFacebooksLanguage() {
        Settings.ORIGINAL_AUDIO.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("pt", OriginalAudio.language("pt"));
        PauseForTests.resume();
        assertEquals("", OriginalAudio.language("pt"));
    }

    @Test
    public void beforeTheSettingsAreReadyKeepsFacebooksLanguage() {
        Settings.ORIGINAL_AUDIO.save(true);
        SettingsContextRule.withoutContext(() -> assertEquals("de", OriginalAudio.language("de")));
    }
}
