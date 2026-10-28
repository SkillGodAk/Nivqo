/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.Context;
import android.content.Intent;

import app.morphe.extension.shared.L10n;

/**
 * An optional launcher entry for Marketplace's free listings, and the Free stuff row the Menu gets
 * beside the Hushfacebook settings row (Discussion #52). Facebook's app has no Free category in
 * Marketplace (583's category list runs from Antiques to Womenswear), but its link handler takes
 * the web's Free Stuff category link and opens Marketplace's results with the free filter set.
 * Never evicts another shortcut.
 */
public final class FreeStuffShortcut {
    static final String ID = "hushfacebook_free_stuff";

    /** The web's Free Stuff category, which Facebook's own link handler opens in Marketplace. */
    static final String ROUTE = "https://www.facebook.com/marketplace/category/free";

    private static final RouteShortcut SHORTCUT = new RouteShortcut(ID, "Free stuff", ROUTE,
            Settings.FREE_STUFF_SHORTCUT, context -> L10n.t(context, "Free stuff"),
            () -> L10n.t("Free stuff isn't available in this build."), android.R.drawable.ic_menu_search);

    private FreeStuffShortcut() { }

    static Intent intent(Context context) {
        return SHORTCUT.intent(context);
    }

    /** Whether the Free stuff row belongs in the Menu: the switch is on and this build opens the link itself. */
    public static boolean wanted(Context context) {
        return SHORTCUT.wanted(context);
    }

    /**
     * Opens Marketplace's free listings from [context], inside the current task when it's an
     * activity. False when this build doesn't take the link itself or Android refused to start it.
     */
    public static boolean open(Context context) {
        return SHORTCUT.open(context);
    }

    /** The toast for a row this build can't open. */
    public static String unavailable() {
        return SHORTCUT.unavailable();
    }

    static void refresh(Context context) {
        SHORTCUT.refresh(context);
    }

    /** Settings changes give immediate feedback; application starts quietly retry when needed. */
    static void changed(Context context) {
        SHORTCUT.changed(context);
    }

    static SavedShortcut.Result refreshNow(Context context) {
        return SHORTCUT.refreshNow(context);
    }
}
