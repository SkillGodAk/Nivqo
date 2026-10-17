/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * Skip silent parts: with its switch on, each audio sink's skip-silence flag reaches the processor
 * chain as yes and is counted. Off, paused, or before the settings are ready, the chain gets
 * Facebook's flag, and a yes of Facebook's own always stays yes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SkipSilenceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.SKIP_SILENT_PARTS.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.SKIP_SILENCE + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOffAndBelongsToItsPatch() {
        assertFalse("Skip silent parts doesn't start off", Settings.SKIP_SILENT_PARTS.defaultValue);
        assertEquals(FamilyNames.SKIP_SILENCE, PatchFamily.SKIP_SILENCE.patchName);
        assertTrue(PatchFamily.SKIP_SILENCE.switches.contains(Settings.SKIP_SILENT_PARTS));
    }

    @Test
    public void onEachSinksSoundSkipsSilentPartsAndIsCounted() {
        Settings.SKIP_SILENT_PARTS.save(true);
        assertTrue("a sink's sound kept its silent parts", SkipSilence.skipSilence(false));
        assertTrue("a yes of Facebook's own became a no", SkipSilence.skipSilence(true));
        assertTrue("the next sink's sound kept its silent parts", SkipSilence.skipSilence(false));
        assertEquals(FamilyNames.SKIP_SILENCE + ": invoked 3, 1 found, 0 missing. Counted: " + SkipSilence.SKIPPING + " 2",
                statusLine());
    }

    @Test
    public void offPausedOrColdTheChainGetsFacebooksFlag() {
        assertFalse("off, a sink's sound skipped its silent parts", SkipSilence.skipSilence(false));
        assertTrue("off, a yes of Facebook's own became a no", SkipSilence.skipSilence(true));

        Settings.SKIP_SILENT_PARTS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " skipped silent parts", SkipSilence.skipSilence(false));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertFalse(
                "silent parts skipped before the settings were ready", SkipSilence.skipSilence(false)));

        String line = statusLine();
        assertFalse("a flag left to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, a sink's sound kept its silent parts", SkipSilence.skipSilence(false));
    }
}
