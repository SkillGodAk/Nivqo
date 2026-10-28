/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Takes the rows picked in Menu tiles to hide out of the Menu's Help and support and Settings and
 * privacy groups (#153), such as Meta AI support assistant, Scam Protection Center or Orders and
 * payments.
 *
 * <p>The Menu's native group section gets each group's rows from a builder of Facebook's, which
 * keeps the list it built and hands it back on the next call. The patch hands the section's copy
 * to {@link #help} or {@link #settings} right after the call, before the section draws it, so the
 * kept list stays whole and a row taken out here leaves no gap. Each row is a plain item with a
 * title and an id: the one {@code CharSequence} field and the one {@code long} field its class
 * declares, read by reflection (the patch checks the item has exactly those).
 *
 * <p>A row is keyed by its group and id ({@link MenuTiles#rowKey}), never by its title, so a
 * translated Menu still matches. Rows with a negative id are ours (the Hushfacebook settings row,
 * Saved and Watch history) and always stay. Each list also writes its rows down
 * ({@link MenuTiles#remember}), so the list offers them in Facebook's words.
 *
 * <p>Off, before the settings are ready, for a list with nothing to take out, or when anything
 * here fails, the list is Facebook's own.
 */
public final class MenuRows {
    /** The Hook status name of the hook. */
    static final String HOOK = "Help and Settings rows";

    /** The counter route: every row in a list asked about, and the ones taken out. */
    static final String ROUTE = "Menu rows";

    /** What a row taken out is counted under. */
    static final String HIDDEN = "Menu row";

    /** The row item class the fields below were looked up on, and the fields, null when it has no such pair. */
    @Nullable
    private static volatile RowFields fields;

    private MenuRows() {
    }

    private static final class RowFields {
        final Class<?> type;
        @Nullable final Field title;
        @Nullable final Field id;

        RowFields(Class<?> type) {
            this.type = type;
            Field title = null;
            Field id = null;
            int titles = 0;
            int ids = 0;
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (field.getType() == CharSequence.class) {
                    title = field;
                    titles++;
                } else if (field.getType() == long.class) {
                    id = field;
                    ids++;
                }
            }
            boolean readable = titles == 1 && ids == 1;
            if (readable) {
                title.setAccessible(true);
                id.setAccessible(true);
            }
            this.title = readable ? title : null;
            this.id = readable ? id : null;
        }
    }

    private static RowFields fieldsFor(Class<?> type) {
        RowFields known = fields;
        if (known != null && known.type == type) return known;
        RowFields found = new RowFields(type);
        fields = found;
        if (found.id == null) {
            HookStatus.missingMember(FamilyNames.MENU_PROMOTIONS, "field", type.getName(),
                    "one CharSequence title and one long id");
        }
        return found;
    }

    /** Injection point, in the native group section right after it got the Help and support rows. Never throws. */
    @Nullable
    public static List<?> help(@Nullable List<?> rows) {
        return kept(rows, MenuTiles.HELP);
    }

    /** Injection point, in the native group section right after it got the Settings and privacy rows. Never throws. */
    @Nullable
    public static List<?> settings(@Nullable List<?> rows) {
        return kept(rows, MenuTiles.SETTINGS);
    }

    /**
     * [rows] without the ones picked from [group], in Facebook's order, or [rows] itself when none
     * are. The patch copies the answer back into an ImmutableList.
     */
    @Nullable
    static List<?> kept(@Nullable List<?> rows, String group) {
        try {
            HookStatus.invoked(FamilyNames.MENU_PROMOTIONS);
            if (rows == null || rows.isEmpty()) return rows;
            // Ready first: Settings loads every switch, and it can't before the context is set.
            if (!Utils.settingsReady()) return rows;
            List<String> keys = new ArrayList<>(rows.size());
            Map<String, String> shown = new LinkedHashMap<>();
            for (Object row : rows) {
                String key = null;
                RowFields found = row == null ? null : fieldsFor(row.getClass());
                if (found != null && found.id != null) {
                    key = MenuTiles.rowKey(group, found.id.getLong(row));
                    Object title = found.title.get(row);
                    if (key != null && title != null && !shown.containsKey(key)) shown.put(key, title.toString());
                }
                keys.add(key);
            }
            MenuTiles.remember(shown);
            Set<String> hidden = MenuTiles.hiddenNow();
            if (hidden.isEmpty()) return rows;
            HookStatus.bound(FamilyNames.MENU_PROMOTIONS, HOOK);
            FeedFilterCounters.sawList(ROUTE, rows.size());
            List<Object> kept = null;
            for (int index = 0; index < rows.size(); index++) {
                String key = keys.get(index);
                if (key != null && hidden.contains(key)) {
                    if (kept == null) kept = new ArrayList<>(rows.subList(0, index));
                } else if (kept != null) {
                    kept.add(rows.get(index));
                }
            }
            if (kept == null) return rows;
            FeedFilterCounters.removed(ROUTE, rows.size() - kept.size(), HIDDEN);
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MENU_PROMOTIONS, HOOK, failure);
            return rows;
        }
    }

    /** Forgets the row item class looked up, so a test's fake rows are read afresh. Tests only. */
    static void forgetForTests() {
        fields = null;
    }
}
