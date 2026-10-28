/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.settings.Setting;

/**
 * A small dot beside a setting this build added since the settings were last opened, and beside
 * the home page row of the section it's in, so a long page shows what's new (issue #16).
 *
 * <p>There is no list of which release added what. The keys of the rows the person has already been
 * shown are kept in a file of their own, apart from the settings, so a backup neither exports nor
 * imports them. The first time the settings open with none stored, every row there now counts as
 * seen: a fresh install never shows a dot. A row whose key isn't in the set is new. Leaving a page
 * adds its rows, so a dot shows for the visit that reveals it and is gone by the next.
 *
 * <p>Only rows that are a saved setting count. A row that comes and goes on its own, such as a
 * running save or a font row, would otherwise read as new each time it came back. Anything that
 * goes wrong shows no dot.
 */
final class NewRowDots {
    static final String FILE = "hushfacebook_new_dots";
    static final String SEEN = "seen";
    /** Marks the dot's tag on a title, so a test can find it. */
    static final String TAG = "hushfacebook_new_dot";

    private final SharedPreferences store;
    private final Set<String> seen = new HashSet<>();

    /** Reads what's been seen, or marks everything in [pages] seen when nothing is stored yet. */
    NewRowDots(Context context, List<PreferenceCategory> pages) {
        store = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Set<String> stored = null;
        try {
            stored = store.getStringSet(SEEN, null);
        } catch (RuntimeException unreadable) {
            // A value of another kind is no set: start again, as on a first open.
        }
        if (stored != null) {
            seen.addAll(stored);
            return;
        }
        for (PreferenceCategory page : pages) addKeys(page);
        save();
    }

    private static boolean counts(Preference row) {
        String key = row.getKey();
        return key != null && Setting.getSettingFromPath(key) != null;
    }

    private void addKeys(PreferenceCategory page) {
        for (int i = 0; i < page.getPreferenceCount(); i++) {
            Preference row = page.getPreference(i);
            if (counts(row)) seen.add(row.getKey());
        }
    }

    private void save() {
        try {
            store.edit().putStringSet(SEEN, new HashSet<>(seen)).apply();
        } catch (RuntimeException ignored) {
            // The dot comes back next time.
        }
    }

    boolean isNew(Preference row) {
        return counts(row) && !seen.contains(row.getKey());
    }

    boolean anyNew(PreferenceCategory page) {
        for (int i = 0; i < page.getPreferenceCount(); i++) {
            if (isNew(page.getPreference(i))) return true;
        }
        return false;
    }

    /** The person has been through [page]: its rows are no longer new. */
    void left(PreferenceCategory page) {
        int before = seen.size();
        addKeys(page);
        if (seen.size() != before) save();
    }

    /**
     * Puts the dot after [row]'s title when [fresh], and takes any earlier one off. The dot is part
     * of the title's text line, so it follows the reading direction, wraps with the title at a large
     * text size and can't leave the row.
     */
    static void paint(View row, boolean fresh, int accent) {
        try {
            TextView title = row.findViewById(android.R.id.title);
            if (title == null) return;
            boolean had = TAG.equals(title.getTag());
            if (!fresh) {
                if (had) {
                    title.setCompoundDrawablesRelative(null, null, null, null);
                    title.setTag(null);
                    title.setContentDescription(null);
                }
                return;
            }
            float density = row.getResources().getDisplayMetrics().density;
            int size = Math.round(8 * density);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(accent);
            dot.setBounds(0, 0, size, size);
            title.setCompoundDrawablesRelative(null, null, dot, null);
            title.setCompoundDrawablePadding(Math.round(8 * density));
            title.setTag(TAG);
            title.setContentDescription(title.getText() + ", " + L10n.t("New"));
        } catch (RuntimeException ignored) {
            // No dot.
        }
    }

    @Nullable
    static NewRowDots create(Context context, List<PreferenceCategory> pages) {
        try {
            return new NewRowDots(context, pages);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
