/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

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
 * Hide suggested and promoted posts' Find friends card switch (#187): on, the end of the feed is told
 * it has no card; off, paused or before the settings are ready, Facebook's string goes through
 * untouched, and no card stays no card.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FindFriendsCardTest {
    private static final String CARD = "friends_ci_unit";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_FIND_FRIENDS_CARD.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.SUGGESTED_POSTS + ":")) return line;
        }
        return "";
    }

    @Test
    public void theSwitchStartsOffAndOffKeepsFacebooksCard() {
        assertFalse(Settings.HIDE_FIND_FRIENDS_CARD.defaultValue);
        assertTrue(PatchFamily.SUGGESTED_POSTS.switches.contains(Settings.HIDE_FIND_FRIENDS_CARD));
        assertEquals(CARD, FindFriendsCard.unit(CARD));
        assertNull(FindFriendsCard.unit(null));
        assertFalse(statusLine().contains(FindFriendsCard.HIDDEN));
    }

    @Test
    public void onLeavesTheCardOut() {
        Settings.HIDE_FIND_FRIENDS_CARD.save(true);
        assertNull(FindFriendsCard.unit(CARD));
        String line = statusLine();
        assertTrue(line, line.contains(FindFriendsCard.HIDDEN + " 1"));
    }

    @Test
    public void noCardIsNeverCounted() {
        Settings.HIDE_FIND_FRIENDS_CARD.save(true);
        assertNull(FindFriendsCard.unit(null));
        assertFalse(statusLine().contains(FindFriendsCard.HIDDEN));
    }

    @Test
    public void pausedKeepsFacebooksCard() {
        Settings.HIDE_FIND_FRIENDS_CARD.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals(CARD, FindFriendsCard.unit(CARD));
        PauseForTests.resume();
        assertNull(FindFriendsCard.unit(CARD));
    }

    @Test
    public void beforeTheSettingsAreReadyKeepsFacebooksCard() {
        Settings.HIDE_FIND_FRIENDS_CARD.save(true);
        SettingsContextRule.withoutContext(() -> assertEquals(CARD, FindFriendsCard.unit(CARD)));
    }
}
