/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The hooks after the Menu's native section gets its Help and support and Settings and privacy
 * rows (#153): a row picked in Menu tiles to hide leaves its own group's list by its id, ours
 * always stay, and every list read writes its rows down for the list by name.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MenuRowsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Shaped like 583's row item: two icon resources, the id, the title and two addresses. */
    static final class Row {
        final int icon;
        final int darkIcon;
        final long id;
        final CharSequence title;
        final String link;
        final String iconLink;

        Row(String title, long id) {
            this.icon = 1;
            this.darkIcon = 2;
            this.id = id;
            this.title = title;
            this.link = null;
            this.iconLink = null;
        }
    }

    /** A class with two titles, which the hook can't tell apart. */
    static final class TwoTitles {
        final CharSequence one = "a";
        final CharSequence other = "b";
        final long id = 7;
    }

    /** 583's Help and support rows, by the ids its builder gives them. */
    static final long ASSISTANT = 0x438c058b24a72L;
    static final long SCAM_CENTER = 0x302b2f9f70c13L;
    static final long SUPPORT = 0xd9f7173402caL;
    static final long REPORT = 0x21010ea56c728L;
    static final long TERMS = 0x1e06d6695ed26L;

    private static List<Object> help() {
        return new ArrayList<>(Arrays.asList(new Row("Meta AI support assistant", ASSISTANT),
                new Row("Scam Protection Center", SCAM_CENTER), new Row("Support", SUPPORT),
                new Row("Report a problem", REPORT), new Row("Terms and Policies", TERMS)));
    }

    /** Settings and privacy as a phone shows it, with our four rows at the end. */
    private static List<Object> settings() {
        return new ArrayList<>(Arrays.asList(new Row("Settings", 1001), new Row("Privacy Center", 1002),
                new Row("Orders and payments", 1003), new Row("Saved", MenuSettingsRow.SAVED_ROW_ID),
                new Row("Watch history", MenuSettingsRow.WATCH_HISTORY_ROW_ID),
                new Row("Free stuff", MenuSettingsRow.FREE_STUFF_ROW_ID),
                new Row("Hushfacebook settings", MenuSettingsRow.ROW_ID)));
    }

    private static List<String> titles(List<?> rows) {
        List<String> titles = new ArrayList<>();
        for (Object row : rows) titles.add(((Row) row).title.toString());
        return titles;
    }

    private static String line(List<String> report, String prefix) {
        for (String line : report) {
            if (line.startsWith(prefix + ":")) return line;
        }
        return null;
    }

    @Before
    public void startClean() {
        FeedFilterCounters.clear();
        HookStatus.clear();
        MenuTiles.forgetForTests();
        MenuRows.forgetForTests();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDDEN_MENU_TILES.resetToDefault();
        Settings.SEEN_MENU_TILES.resetToDefault();
        MenuTiles.forgetForTests();
        MenuRows.forgetForTests();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    /** #153: Scam Protection Center and Orders and payments leave their groups, and the rest keep their order. */
    @Test
    public void pickedRowsLeaveTheirGroupAndTheRestKeepTheirOrder() {
        MenuTiles.hide(Arrays.asList("help_" + SCAM_CENTER, "settings_1003"));
        assertEquals(Arrays.asList("Meta AI support assistant", "Support", "Report a problem", "Terms and Policies"),
                titles(MenuRows.help(help())));
        assertEquals(Arrays.asList("Settings", "Privacy Center", "Saved", "Watch history", "Free stuff",
                "Hushfacebook settings"), titles(MenuRows.settings(settings())));
    }

    /** A row's id counts only in its own group, so a Settings row never hides a Help row that shares it. */
    @Test
    public void anIdHidesOnlyInItsOwnGroup() {
        MenuTiles.hide(Collections.singletonList("settings_" + SUPPORT));
        List<Object> rows = help();
        assertSame(rows, MenuRows.help(rows));
        MenuTiles.hide(Collections.singletonList("help_1001"));
        List<Object> settings = settings();
        assertSame(settings, MenuRows.settings(settings));
    }

    /** Our rows never get a key, so they're never written down and can't be picked. Hiding every row keeps them. */
    @Test
    public void ourRowsAreNeverWrittenDownAndAlwaysStay() {
        MenuRows.settings(settings());
        assertEquals("settings_1001=Settings\nsettings_1002=Privacy Center\nsettings_1003=Orders and payments",
                Settings.SEEN_MENU_TILES.savedValue());
        MenuTiles.hide(Arrays.asList("settings_1001", "settings_1002", "settings_1003"));
        assertEquals(Arrays.asList("Saved", "Watch history", "Free stuff", "Hushfacebook settings"),
                titles(MenuRows.settings(settings())));
    }

    /** Reading both groups offers their rows after the tiles, in the Menu's order, named after their group. */
    @Test
    public void readRowsAreOfferedAfterTheTilesByTheirGroupAndName() {
        MenuRows.settings(settings());
        MenuRows.help(help());
        List<String> choices = MenuTiles.choices();
        assertEquals("the catalog's tiles still come first", MenuTiles.CATALOG, choices.subList(0, MenuTiles.CATALOG.size()));
        List<String> rows = choices.subList(MenuTiles.CATALOG.size(), choices.size());
        assertEquals(Arrays.asList("help_" + ASSISTANT, "help_" + SCAM_CENTER, "help_" + SUPPORT, "help_" + REPORT,
                "help_" + TERMS, "settings_1001", "settings_1002", "settings_1003"), rows);
        assertEquals("Help and support: Report a problem", MenuTiles.label("help_" + REPORT));
        assertEquals("Settings and privacy: Orders and payments", MenuTiles.label("settings_1003"));
    }

    /** Nothing picked, paused, before the settings are ready, or for an empty list, the list is Facebook's. */
    @Test
    public void withNothingToTakeOutTheListIsFacebooks() {
        List<Object> rows = help();
        assertSame(rows, MenuRows.help(rows));
        MenuTiles.hide(Collections.singletonList("help_" + SUPPORT));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(rows, MenuRows.help(rows));
        PauseForTests.resume();
        Object[] kept = new Object[2];
        SettingsContextRule.withoutContext(() -> kept[0] = MenuRows.help(rows));
        SettingsContextRule.beforeThePauseIsDecided(() -> kept[1] = MenuRows.help(rows));
        assertSame(rows, kept[0]);
        assertSame(rows, kept[1]);
        List<Object> empty = Collections.emptyList();
        assertSame(empty, MenuRows.help(empty));
        assertNull(MenuRows.help(null));
        assertEquals(4, MenuRows.help(rows).size());
    }

    /** A row the hook can't read stays where it is and is reported as a missing member, as does a null. */
    @Test
    public void aRowItCantReadStaysAndIsReported() {
        MenuTiles.hide(Collections.singletonList("help_7"));
        List<Object> rows = new ArrayList<>(Arrays.asList(new TwoTitles(), null, "Support"));
        assertSame(rows, MenuRows.help(rows));
        String status = line(HookStatus.report(), FamilyNames.MENU_PROMOTIONS);
        assertNotNull(HookStatus.report().toString(), status);
        assertTrue(status, status.contains("missing"));
        assertTrue(status, !status.contains(", 0 missing"));
    }

    @Test
    public void theReportCountsTheRowsAskedAboutAndTheOnesTakenOut() {
        MenuTiles.hide(Collections.singletonList("help_" + TERMS));
        MenuRows.help(help());
        String counters = line(FeedFilterCounters.report(), MenuRows.ROUTE);
        assertNotNull(FeedFilterCounters.report().toString(), counters);
        assertTrue(counters, counters.startsWith(MenuRows.ROUTE + ": 1 lists, 5 items, 1 removed."));
        assertTrue(counters, counters.contains("Removed: " + MenuRows.HIDDEN + " 1"));
        assertEquals(FamilyNames.MENU_PROMOTIONS + ": invoked 1, 1 found, 0 missing",
                line(HookStatus.report(), FamilyNames.MENU_PROMOTIONS));
    }
}
