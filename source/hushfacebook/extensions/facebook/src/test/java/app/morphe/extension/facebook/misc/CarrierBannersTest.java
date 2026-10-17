/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide carrier data banners (#70): each switch hides only its own mode's banners, every other
 * banner Facebook Flex shows stays, and off, paused or with no key, nothing is hidden.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CarrierBannersTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Banners neither switch is about: Wi-Fi, special pricing, the manual switcher, jumpstart, and one nobody knows. */
    private static final String[] OTHERS = {"wifi_banner", "special_pricing_free_photo_banner",
            "special_pricing_free_photo_video_banner", "manual_switcher_mode", "zero_state",
            "open_monetization_banner", "some_banner_from_a_later_build"};

    @Before
    public void clear() {
        FeedFilterCounters.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_DATA_MODE_BANNER.resetToDefault();
        Settings.HIDE_FREE_MODE_BANNER.resetToDefault();
        PauseForTests.resume();
        FeedFilterCounters.clear();
    }

    @Test
    public void bothStartOffAndHideNothing() {
        assertFalse(Settings.HIDE_DATA_MODE_BANNER.savedValue());
        assertFalse(Settings.HIDE_FREE_MODE_BANNER.savedValue());
        for (String key : CarrierBanners.DATA_MODE) assertFalse(key, CarrierBanners.hides(key));
        for (String key : CarrierBanners.FREE_MODE) assertFalse(key, CarrierBanners.hides(key));
    }

    @Test
    public void theDataModeSwitchHidesOnlyDataModeBanners() {
        Settings.HIDE_DATA_MODE_BANNER.save(true);
        for (String key : CarrierBanners.DATA_MODE) assertTrue(key, CarrierBanners.hides(key));
        for (String key : CarrierBanners.FREE_MODE) assertFalse("the Free mode bar is the way back: " + key, CarrierBanners.hides(key));
        for (String key : OTHERS) assertFalse(key, CarrierBanners.hides(key));
    }

    @Test
    public void theFreeModeSwitchHidesOnlyFreeModeBanners() {
        Settings.HIDE_FREE_MODE_BANNER.save(true);
        for (String key : CarrierBanners.FREE_MODE) assertTrue(key, CarrierBanners.hides(key));
        for (String key : CarrierBanners.DATA_MODE) assertFalse(key, CarrierBanners.hides(key));
        for (String key : OTHERS) assertFalse(key, CarrierBanners.hides(key));
    }

    @Test
    public void theTwoListsDontOverlap() {
        assertTrue(Collections.disjoint(CarrierBanners.DATA_MODE, CarrierBanners.FREE_MODE));
    }

    @Test
    public void noKeyIsNeverHidden() {
        Settings.HIDE_DATA_MODE_BANNER.save(true);
        Settings.HIDE_FREE_MODE_BANNER.save(true);
        assertFalse(CarrierBanners.hides(null));
    }

    @Test
    public void pausedEveryBannerShowsAndTheChoiceIsKept() {
        Settings.HIDE_DATA_MODE_BANNER.save(true);
        Settings.HIDE_FREE_MODE_BANNER.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(CarrierBanners.hides("zorp_paid_mode_banner"));
        assertFalse(CarrierBanners.hides("autoflex_banner"));
        assertTrue(Settings.HIDE_DATA_MODE_BANNER.savedValue());
    }

    @Test
    public void theReportCountsEachKeySeenAndEachHidden() {
        Settings.HIDE_DATA_MODE_BANNER.save(true);
        CarrierBanners.hides("autoflex_paid_mode_banner");
        CarrierBanners.hides("wifi_banner");
        String line = String.join("\n", FeedFilterCounters.report());
        assertTrue(line, line.contains(CarrierBanners.ROUTE));
        assertTrue(line, line.contains("autoflex_paid_mode_banner"));
        assertTrue(line, line.contains("wifi_banner"));
    }
}
