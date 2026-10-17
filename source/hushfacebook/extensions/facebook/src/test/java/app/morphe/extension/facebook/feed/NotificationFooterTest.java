/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertFalse;
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
 * Hide suggested and promoted posts' notification post footer switch (#163): on, a post opened from
 * a notification is asked for without its "More from your friends" list; off, paused or before the
 * settings are ready, Facebook's answer goes through untouched, and a no stays a no.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class NotificationFooterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_NOTIFICATION_POST_FOOTER.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.SUGGESTED_POSTS + ":")) return line;
        }
        return "";
    }

    @Test
    public void theSwitchStartsOffAndOffKeepsFacebooksAnswer() {
        assertFalse(Settings.HIDE_NOTIFICATION_POST_FOOTER.defaultValue);
        assertTrue(PatchFamily.SUGGESTED_POSTS.switches.contains(Settings.HIDE_NOTIFICATION_POST_FOOTER));
        assertTrue(NotificationFooter.show(true));
        assertFalse(NotificationFooter.show(false));
        assertFalse(statusLine().contains(NotificationFooter.HIDDEN));
    }

    @Test
    public void onAsksForThePostAlone() {
        Settings.HIDE_NOTIFICATION_POST_FOOTER.save(true);
        assertFalse(NotificationFooter.show(true));
        String line = statusLine();
        assertTrue(line, line.contains(NotificationFooter.HIDDEN + " 1"));
    }

    @Test
    public void aNoIsNeverCountedOrTurnedIntoAYes() {
        Settings.HIDE_NOTIFICATION_POST_FOOTER.save(true);
        assertFalse(NotificationFooter.show(false));
        assertFalse(statusLine().contains(NotificationFooter.HIDDEN));
    }

    @Test
    public void pausedKeepsFacebooksAnswer() {
        Settings.HIDE_NOTIFICATION_POST_FOOTER.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue(NotificationFooter.show(true));
        PauseForTests.resume();
        assertFalse(NotificationFooter.show(true));
    }

    @Test
    public void beforeTheSettingsAreReadyKeepsFacebooksAnswer() {
        Settings.HIDE_NOTIFICATION_POST_FOOTER.save(true);
        SettingsContextRule.withoutContext(() -> assertTrue(NotificationFooter.show(true)));
    }
}
