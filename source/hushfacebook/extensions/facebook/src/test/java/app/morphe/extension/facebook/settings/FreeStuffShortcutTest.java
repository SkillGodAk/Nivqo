/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The Free stuff shortcut (Discussion #52): off by default, a launcher entry for the web's Free
 * Stuff category link while on and this build's link handler takes it, never in place of another
 * entry, gone while paused or off, and side by side with the Watch history one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FreeStuffShortcutTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @After public void restore() {
        Settings.FREE_STUFF_SHORTCUT.resetToDefault();
        Settings.WATCH_HISTORY_SHORTCUT.resetToDefault();
        PauseForTests.resume();
    }

    static boolean aStartPublishes() {
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        manager.removeDynamicShortcuts(Collections.singletonList(FreeStuffShortcut.ID));
        addRoute(context, FreeStuffShortcut.intent(context));
        FreeStuffShortcut.refreshNow(context);
        return shortcut(manager, FreeStuffShortcut.ID) != null;
    }

    private static void addRoute(Context context, Intent intent) {
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = context.getPackageName();
        info.activityInfo.name = context.getPackageName() + ".IntentUriHandler";
        shadowOf(context.getPackageManager()).addResolveInfoForIntent(intent, info);
    }

    private static ShortcutInfo shortcut(ShortcutManager manager, String id) {
        for (ShortcutInfo shortcut : manager.getDynamicShortcuts()) {
            if (id.equals(shortcut.getId())) return shortcut;
        }
        return null;
    }

    private static ShortcutInfo stock(Context context, String id, int rank) {
        return new ShortcutInfo.Builder(context, id).setShortLabel(id)
                .setIntent(new Intent(Intent.ACTION_MAIN).setPackage(context.getPackageName()))
                .setRank(rank).build();
    }

    @Test public void defaultOffLeavesFacebooksEntriesAlone() {
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        ShortcutInfo entry = stock(context, "notifications", 2);
        manager.addDynamicShortcuts(Collections.singletonList(entry));
        assertFalse(Settings.FREE_STUFF_SHORTCUT.savedValue());
        assertEquals(SavedShortcut.Result.OFF, FreeStuffShortcut.refreshNow(context));
        assertEquals(Collections.singletonList(entry), manager.getDynamicShortcuts());
    }

    @Test public void publishedEntryOpensMarketplacesFreeListingsInThisInstall() {
        Settings.FREE_STUFF_SHORTCUT.save(true);
        assertTrue(aStartPublishes());
        Context context = RuntimeEnvironment.getApplication();
        ShortcutInfo free = shortcut(context.getSystemService(ShortcutManager.class), FreeStuffShortcut.ID);
        assertEquals("https://www.facebook.com/marketplace/category/free", free.getIntent().getDataString());
        assertEquals(Intent.ACTION_VIEW, free.getIntent().getAction());
        assertEquals("the link went to the browser", context.getPackageName(),
                free.getIntent().getComponent().getPackageName());
        assertEquals("Free stuff", String.valueOf(free.getShortLabel()));
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, free.getIntent().getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Test public void facebooksTwoLinkTakersStillPublishAndOpenTheRouter() {
        // Facebook takes the link in its catch-all alias as well as IntentUriHandler, and on the
        // phone resolveActivity then answers Android's chooser, so the entry never showed (#52).
        Context context = RuntimeEnvironment.getApplication();
        ResolveInfo alias = new ResolveInfo();
        alias.activityInfo = new ActivityInfo();
        alias.activityInfo.packageName = context.getPackageName();
        alias.activityInfo.name = "com.facebook.deeplinking.aliasactivity.CatchAllUriAliasActivity";
        alias.activityInfo.targetActivity = "com.facebook.silentapp.SilentAppActivity";
        shadowOf(context.getPackageManager()).addResolveInfoForIntent(FreeStuffShortcut.intent(context), alias);
        Settings.FREE_STUFF_SHORTCUT.save(true);
        assertTrue(aStartPublishes());
        String router = context.getPackageName() + ".IntentUriHandler";
        ShortcutInfo free = shortcut(context.getSystemService(ShortcutManager.class), FreeStuffShortcut.ID);
        assertEquals(router, free.getIntent().getComponent().getClassName());
        assertTrue(FreeStuffShortcut.wanted(context));
        assertTrue(FreeStuffShortcut.open(context));
        Intent started = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        assertEquals(router, started.getComponent().getClassName());
        assertEquals(FreeStuffShortcut.ROUTE, started.getDataString());
    }

    @Test public void itSitsBesideTheWatchHistoryEntryWithoutTakingItsPlace() {
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        Settings.WATCH_HISTORY_SHORTCUT.save(true);
        Settings.FREE_STUFF_SHORTCUT.save(true);
        addRoute(context, WatchHistoryShortcut.intent(context));
        assertEquals(SavedShortcut.Result.PUBLISHED, WatchHistoryShortcut.refreshNow(context));
        assertTrue(aStartPublishes());
        ShortcutInfo watched = shortcut(manager, WatchHistoryShortcut.ID);
        ShortcutInfo free = shortcut(manager, FreeStuffShortcut.ID);
        assertNotNull(watched);
        assertNotNull(free);
        assertNotEquals(watched.getRank(), free.getRank());
        Settings.FREE_STUFF_SHORTCUT.save(false);
        assertEquals(SavedShortcut.Result.OFF, FreeStuffShortcut.refreshNow(context));
        assertNull(shortcut(manager, FreeStuffShortcut.ID));
        assertNotNull("turning Free stuff off took Watch history with it", shortcut(manager, WatchHistoryShortcut.ID));
    }

    @Test public void itGoesAheadOfFacebooksEntriesWhereTheLauncherShowsIt() {
        // One UI shows four entries, and Facebook's three behind the settings entry filled them,
        // so a Free stuff entry ranked last was published but never seen (#52).
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        manager.addDynamicShortcuts(List.of(stock(context, SettingsEntry.SHORTCUT_ID, 0),
                stock(context, "notifications", 1), stock(context, "friends", 2), stock(context, "reels", 3)));
        Settings.FREE_STUFF_SHORTCUT.save(true);
        assertTrue(aStartPublishes());
        ShortcutInfo free = shortcut(manager, FreeStuffShortcut.ID);
        assertEquals("not given the first rank Facebook holds", 1, free.getRank());
        assertEquals(0, shortcut(manager, SettingsEntry.SHORTCUT_ID).getRank());
        for (String facebooks : new String[] {"notifications", "friends", "reels"}) {
            assertNotNull(facebooks + " was removed", shortcut(manager, facebooks));
        }

        // Facebook publishing its own again put them first, so the next refresh moves it back.
        manager.updateShortcuts(List.of(new ShortcutInfo.Builder(context, FreeStuffShortcut.ID)
                .setShortLabel(free.getShortLabel()).setIntent(free.getIntent()).setRank(4).build()));
        assertEquals(SavedShortcut.Result.PUBLISHED, FreeStuffShortcut.refreshNow(context));
        free = shortcut(manager, FreeStuffShortcut.ID);
        assertEquals(1, free.getRank());
        assertEquals(FreeStuffShortcut.ROUTE, free.getIntent().getDataString());
        assertEquals(5, manager.getDynamicShortcuts().size());
    }

    @Test public void fullLauncherNeverEvictsFacebooksEntries() {
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        List<ShortcutInfo> entries = new ArrayList<>();
        for (int i = 0; i < manager.getMaxShortcutCountPerActivity(); i++) entries.add(stock(context, "stock" + i, i));
        manager.addDynamicShortcuts(entries);
        List<ShortcutInfo> before = new ArrayList<>(manager.getDynamicShortcuts());
        addRoute(context, FreeStuffShortcut.intent(context));
        Settings.FREE_STUFF_SHORTCUT.save(true);
        assertEquals(SavedShortcut.Result.NO_ROOM, FreeStuffShortcut.refreshNow(context));
        assertEquals(before, manager.getDynamicShortcuts());
        assertNull(shortcut(manager, FreeStuffShortcut.ID));
    }

    @Test public void aLinkThisBuildDoesntTakeLeavesOtherShortcutsIntact() {
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        ShortcutInfo entry = stock(context, "notifications", 0);
        manager.addDynamicShortcuts(Collections.singletonList(entry));
        Settings.FREE_STUFF_SHORTCUT.save(true);
        assertEquals(SavedShortcut.Result.UNAVAILABLE, FreeStuffShortcut.refreshNow(context));
        assertEquals(Collections.singletonList(entry), manager.getDynamicShortcuts());
        assertFalse(FreeStuffShortcut.wanted(context));
        assertFalse(FreeStuffShortcut.open(context));
    }

    @Test public void pauseRemovesTheEntryAndResumeKeepsTheChoice() {
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        Settings.FREE_STUFF_SHORTCUT.save(true);
        assertTrue(aStartPublishes());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals(SavedShortcut.Result.OFF, FreeStuffShortcut.refreshNow(context));
        assertNull(shortcut(manager, FreeStuffShortcut.ID));
        assertTrue(Settings.FREE_STUFF_SHORTCUT.savedValue());
        PauseForTests.resume();
        assertTrue(aStartPublishes());
    }

    @Test public void languageChangeRelabelsTheEntry() {
        Context context = RuntimeEnvironment.getApplication();
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        Settings.FREE_STUFF_SHORTCUT.save(true);
        assertTrue(aStartPublishes());
        RuntimeEnvironment.setQualifiers("+de");
        assertEquals(SavedShortcut.Result.PUBLISHED, FreeStuffShortcut.refreshNow(context));
        assertEquals("Zu verschenken", String.valueOf(shortcut(manager, FreeStuffShortcut.ID).getShortLabel()));
        assertEquals(1, manager.getDynamicShortcuts().size());
    }
}
