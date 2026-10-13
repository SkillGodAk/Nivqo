/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.preference.Preference;

import app.morphe.extension.facebook.settings.SettingsRows.Row;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Facebook's App icon page, where Unlock app icons' icons are picked (#122). Facebook lists it in
 * Settings and privacy only on some accounts, so on the others the switch had nowhere to show. The
 * page itself has no such check: 582's activity only reads its entry point and shows the picker
 * fragment, whose checks the patch answers. This row starts it by its explicit component, in
 * process, with the entry point Facebook's own Settings row passes.
 */
@SuppressWarnings("deprecation")
final class AppIconScreen {
    /** Kept class name, declared in 582's manifest (exported=false). */
    static final String ACTIVITY = "com.facebook.appicon.ChangeAppIconActivity";
    /** The extra the activity reads when its intent has no URI. */
    static final String ENTRY_POINT = "extra_entry_point";
    /** What Facebook's own row passes (582 `fb://change_app_icon?entry_point=settings_bookmark`). */
    static final String FROM_SETTINGS = "settings_bookmark";

    private AppIconScreen() {
    }

    /** The row under Unlock app icons. */
    static Preference row(Context context) {
        Preference row = new Row(context);
        row.setTitle(L10n.t("Pick an app icon"));
        row.setSummary(L10n.t("Opens Facebook's App icon page, even on accounts where Facebook doesn't list it in "
                + "Settings and privacy."));
        row.setPersistent(false);
        row.setOnPreferenceClickListener(p -> {
            open(p.getContext());
            return true;
        });
        return row;
    }

    /** The explicit, in-process intent for Facebook's page. */
    static Intent screenIntent(Context context) {
        return new Intent().setComponent(new ComponentName(context.getPackageName(), ACTIVITY))
                .putExtra(ENTRY_POINT, FROM_SETTINGS);
    }

    static void open(Context context) {
        try {
            context.startActivity(screenIntent(context));
        } catch (ActivityNotFoundException | SecurityException missing) {
            // A build without the page. Uncaught, Android's exception closed Facebook.
            Logger.printInfo(() -> "Nothing opened Facebook's App icon page: " + missing.getClass().getSimpleName());
            Utils.showToastLong(L10n.t("This Facebook build has no App icon page."));
        }
    }
}
