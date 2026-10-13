/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.app.Activity;
import android.preference.Preference;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.PauseForTests;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.EnumSet;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SettingsSearchIndexTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushfacebookPreferenceFragment page;
    private Preference row;

    @Before public void open() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        PauseForTests.resume();
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
        row = page.findPreference(Settings.TAP_TO_PLAY.key);
        assertNotNull(row);
    }

    @After public void close() {
        controller.close();
        ShadowLooper.idleMainLooper();
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        PauseForTests.resume();
    }

    @Test public void anAbsentSummaryDoesntBecomeASearchTerm() {
        row.setTitle("Résumé discovery");
        row.setSummary(null);
        page.navigation.search("null");
        assertFalse(containsRow());
        page.navigation.search("playback RESUME discovery");
        assertTrue("the category and accented title still match", containsRow());
    }

    @Test public void anAbsentTitleDoesntHideTheSummaryOrBecomeASearchTerm() {
        row.setTitle(null);
        row.setSummary("Résumé discovery");
        page.navigation.search("resume discovery");
        assertTrue(containsRow());
        page.navigation.search("null");
        assertFalse(containsRow());
    }

    /** Search can list No time limit without the switch it works under above it, so the row names it (#130). */
    @Test public void aGreyedChildNamesItsSwitchInSearch() {
        Preference noLimit = page.findPreference(Settings.RETURN_REFRESH_NO_LIMIT.key);
        assertNotNull(noLimit);
        row = noLimit;
        page.navigation.search("no time limit");
        assertTrue(containsRow());
        assertFalse("No time limit is open in search with its switch off", noLimit.isEnabled());
        assertTrue("the search result doesn't name the switch: " + noLimit.getSummary(),
                String.valueOf(noLimit.getSummary()).contains(SwitchLabels.title(Settings.BLOCK_RETURN_REFRESH)));
    }

    private boolean containsRow() {
        for (int i = 0; i < page.navigation.getCount(); i++) {
            if (page.navigation.getItem(i) == row) return true;
        }
        return false;
    }
}
