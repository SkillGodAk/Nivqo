/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.graphservice.tree.TreeJNI;

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
 * The hook after the Menu's shortcut section reads its bookmarks: with the switch on, the bookmark
 * named Meta AI leaves the list and every other one stays in Facebook's order. Off, paused, before
 * the settings are ready, or for a list without it, the list handed in comes back as it is.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MenuShortcutsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static TreeJNI bookmark(String name) {
        return new TreeJNI().with("name", name);
    }

    /** The shortcut rows as a phone showed them on 2026-10-15, with two of See more's after them. */
    private static List<Object> menu() {
        return new ArrayList<>(Arrays.asList(bookmark("Feeds"), bookmark("Marketplace"), bookmark("Reels"),
                bookmark("Groups"), bookmark("Meta AI"), bookmark("Events"), bookmark("Memories")));
    }

    private static List<String> names(List<?> bookmarks) {
        List<String> names = new ArrayList<>();
        for (Object bookmark : bookmarks) names.add(((TreeJNI) bookmark).getString(MenuShortcuts.NAME));
        return names;
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
        Settings.HIDE_MENU_META_AI.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_MENU_META_AI.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOff() {
        Settings.HIDE_MENU_META_AI.resetToDefault();
        assertFalse("the Meta AI shortcut switch starts on", Settings.HIDE_MENU_META_AI.get());
        List<Object> bookmarks = menu();
        assertSame(bookmarks, MenuShortcuts.kept(bookmarks));
    }

    /** The rest keep their order, so the first of See more's moves up into the fifth row. */
    @Test
    public void onTheMetaAiShortcutLeavesAndTheRestKeepTheirOrder() {
        assertEquals(Arrays.asList("Feeds", "Marketplace", "Reels", "Groups", "Events", "Memories"),
                names(MenuShortcuts.kept(menu())));
    }

    @Test
    public void aListWithoutItComesBackAsItIs() {
        List<Object> bookmarks = new ArrayList<>(Arrays.asList(bookmark("Feeds"), bookmark("Meta AI Studio fans"),
                bookmark("Groups")));
        assertSame(bookmarks, MenuShortcuts.kept(bookmarks));
        List<Object> empty = Collections.emptyList();
        assertSame(empty, MenuShortcuts.kept(empty));
        assertNull(MenuShortcuts.kept(null));
    }

    @Test
    public void theNameIsMatchedWholeWhateverItsCase() {
        assertTrue(MenuShortcuts.isMetaAiName("Meta AI"));
        assertTrue(MenuShortcuts.isMetaAiName(" meta ai "));
        assertFalse(MenuShortcuts.isMetaAiName("Meta AI Studio"));
        assertFalse(MenuShortcuts.isMetaAiName("Meta"));
        assertFalse(MenuShortcuts.isMetaAiName(""));
        assertFalse(MenuShortcuts.isMetaAiName(null));
    }

    @Test
    public void offOrPausedTheListIsFacebooks() {
        Settings.HIDE_MENU_META_AI.save(false);
        List<Object> bookmarks = menu();
        assertSame(bookmarks, MenuShortcuts.kept(bookmarks));
        Settings.HIDE_MENU_META_AI.save(true);

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertSame(bookmarks, MenuShortcuts.kept(bookmarks));
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        assertSame(bookmarks, MenuShortcuts.kept(bookmarks));
        PauseForTests.resume();
        assertEquals(6, MenuShortcuts.kept(bookmarks).size());
    }

    @Test
    public void untilTheSettingsAreReadyTheListIsFacebooks() {
        List<Object> bookmarks = menu();
        Object[] kept = new Object[2];
        SettingsContextRule.withoutContext(() -> kept[0] = MenuShortcuts.kept(bookmarks));
        SettingsContextRule.beforeThePauseIsDecided(() -> kept[1] = MenuShortcuts.kept(bookmarks));
        assertSame(bookmarks, kept[0]);
        assertSame(bookmarks, kept[1]);
    }

    /** A released model is never read, and whatever isn't a model is left where it is. */
    @Test
    public void aReleasedModelOrAnythingElseStays() {
        TreeJNI released = bookmark("Meta AI").releasedTree();
        List<Object> bookmarks = new ArrayList<>(Arrays.asList(bookmark("Feeds"), released, "Meta AI", null));
        assertSame(bookmarks, MenuShortcuts.kept(bookmarks));
        assertFalse("a released model was read", released.readAfterRelease);
    }

    @Test
    public void theReportCountsTheBookmarksAskedAboutAndTheShortcutHidden() {
        MenuShortcuts.kept(menu());
        String counters = line(FeedFilterCounters.report(), MenuShortcuts.ROUTE);
        assertNotNull(FeedFilterCounters.report().toString(), counters);
        assertTrue(counters, counters.startsWith(MenuShortcuts.ROUTE + ": 1 lists, 7 items, 1 removed."));
        assertTrue(counters, counters.contains("Removed: " + MenuShortcuts.HIDDEN + " 1"));
        assertEquals(FamilyNames.MENU_PROMOTIONS + ": invoked 1, 1 found, 0 missing",
                line(HookStatus.report(), FamilyNames.MENU_PROMOTIONS));
    }
}
