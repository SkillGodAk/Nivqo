/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Hide carrier data banners patch asks each time Facebook shows its carrier banner.
 *
 * <p>On carriers with Facebook Flex, free Facebook without photos and videos, Facebook puts a bar
 * under the top bar that says which mode you're in (#70). In Data mode it says so and offers to
 * buy mobile data; in Free mode it's the purple bar that offers a way back to Data mode. One
 * method of Facebook's picks the banner, sets it up and shows it, and every banner it shows
 * passes one point where its key, a name Facebook keeps for each kind, is in hand. The patch asks
 * here there, and a yes hides the banner the way Facebook hides it when it decides on none.
 *
 * <p>The keys are Facebook's own: the paid-mode ones are Data mode, the free ones are Free mode.
 * Every other banner, Wi-Fi, special pricing, the manual switcher and any key this class doesn't
 * know, stays. Off, paused, settings that aren't ready yet, or a failure in here, and the banner
 * shows as Facebook decided.
 */
public final class CarrierBanners {
    /** The diagnostic counter route: each banner Facebook showed, by key, and the ones hidden. */
    static final String ROUTE = "Carrier banners";

    /** Data mode: the redesigned banner's paid mode, and both variants of the autoflex paid banner. */
    static final Set<String> DATA_MODE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "zorp_paid_mode_banner", "autoflex_paid_mode_banner", "autoflex_paid_mode_banner_b4o")));

    /** Free mode: the autoflex free banner and the redesigned banner's free texts. */
    static final Set<String> FREE_MODE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "autoflex_banner", "freemium_models_banner", "freemium_models_fup_banner")));

    private CarrierBanners() {
    }

    /**
     * Injection point, where Facebook's banner method has set up the banner whose [key] it keeps,
     * before it logs it and adds its tooltips. True hides it. Never throws.
     */
    public static boolean hides(@Nullable String key) {
        try {
            HookStatus.invoked(FamilyNames.CARRIER_BANNERS);
            FeedFilterCounters.sawList(ROUTE, 1);
            FeedFilterCounters.sawKind(ROUTE, key == null ? "none" : key);
            if (key == null || !Utils.settingsReady()) return false;
            boolean hide = DATA_MODE.contains(key) ? Settings.HIDE_DATA_MODE_BANNER.get()
                    : FREE_MODE.contains(key) && Settings.HIDE_FREE_MODE_BANNER.get();
            if (!hide) return false;
            FeedFilterCounters.removed(ROUTE, 1, key);
            Logger.printDebug(() -> "Hide carrier data banners: hid the " + key + " banner");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CARRIER_BANNERS, "carrier banner", failure);
            return false;
        }
    }
}
