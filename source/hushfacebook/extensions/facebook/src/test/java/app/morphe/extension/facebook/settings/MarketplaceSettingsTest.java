/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import java.io.File;
import java.io.FileOutputStream;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.navigation.MarketplaceOnly;
import app.morphe.extension.facebook.navigation.MarketplaceOnlyForTests;
import app.morphe.extension.facebook.navigation.StartTab;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

/** The choices a person makes in Marketplace settings, including a restored settings file. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class MarketplaceSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void setUp() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.START_TAB, PatchFamily.MARKETPLACE_ONLY);
        MarketplaceOnlyForTests.inBuild(true);
        MarketplaceOnlyForTests.resetState();
        HookStatus.clear();
        Settings.MARKETPLACE_ONLY.resetToDefault();
        Settings.EVENTS_ONLY.resetToDefault();
        Settings.OPEN_ON_CHOSEN_TAB.save(true);
        Settings.START_TAB.save(StartTab.FRIENDS);
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        MarketplaceOnlyForTests.inBuild(null);
        MarketplaceOnlyForTests.resetState();
        HookStatus.clear();
        Settings.MARKETPLACE_ONLY.resetToDefault();
        Settings.EVENTS_ONLY.resetToDefault();
        Settings.OPEN_ON_CHOSEN_TAB.resetToDefault();
        Settings.START_TAB.resetToDefault();
        ScreenColors.shown = null;
    }

    private static HushfacebookPreferenceFragment open(Activity activity) {
        HushfacebookPreferenceFragment page = new HushfacebookPreferenceFragment();
        activity.getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        ShadowLooper.idleMainLooper();
        return page;
    }

    private static Preference titled(PreferenceGroup group, String title) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference row = group.getPreference(i);
            if (title.contentEquals(String.valueOf(row.getTitle()))) return row;
            if (row instanceof PreferenceGroup) {
                Preference found = titled((PreferenceGroup) row, title);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    public void includingThePatchDoesNotRemoveTheFeedUntilThePersonOptsIn() {
        assertFalse(Settings.MARKETPLACE_ONLY.defaultValue);
        assertFalse(Settings.OPEN_ON_CHOSEN_TAB.defaultValue);
        assertFalse(MarketplaceOnlyForTests.hidesHome());
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = open(controller.get());
            assertFalse(((SwitchPreference) page.findPreference(Settings.MARKETPLACE_ONLY.key)).isChecked());
            assertTrue(page.findPreference(Settings.START_TAB.key).isEnabled());
        }
    }

    @Test
    public void theModeExplainsItsOverrideAndRestoresTheSavedStartTab() throws Exception {
        Settings.MARKETPLACE_ONLY.save(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = open(controller.get());
            Preference chosen = page.findPreference(Settings.OPEN_ON_CHOSEN_TAB.key);
            Preference tab = page.findPreference(Settings.START_TAB.key);
            assertFalse(chosen.isEnabled());
            assertFalse(tab.isEnabled());
            assertTrue(String.valueOf(tab.getSummary()), tab.getSummary().toString().contains("Marketplace"));

            Setting.saveAll(Map.of(Settings.MARKETPLACE_ONLY, false));
            page.refreshSwitches();
            ShadowLooper.idleMainLooper();
            assertTrue(chosen.isEnabled());
            assertTrue(tab.isEnabled());
            assertEquals(StartTab.FRIENDS, Settings.START_TAB.savedValue());
            assertTrue(Settings.OPEN_ON_CHOSEN_TAB.savedValue());
            assertEquals(HushfacebookPreferenceFragment.startTabSummary(StartTab.FRIENDS), tab.getSummary());
        }
    }

    @Test
    public void returnToRegularFacebookKeepsEveryOtherChoice() {
        Settings.MARKETPLACE_ONLY.save(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = open(controller.get());
            Preference back = titled(page.getPreferenceScreen(), "Return to regular Facebook");
            assertNotNull(back);
            assertTrue(back.isEnabled());
            assertTrue(back.getOnPreferenceClickListener().onPreferenceClick(back));
            ShadowLooper.idleMainLooper();
            assertFalse(Settings.MARKETPLACE_ONLY.savedValue());
            assertFalse(((SwitchPreference) page.findPreference(Settings.MARKETPLACE_ONLY.key)).isChecked());
            assertTrue(Settings.OPEN_ON_CHOSEN_TAB.savedValue());
            assertEquals(StartTab.FRIENDS, Settings.START_TAB.savedValue());
            assertFalse(back.isEnabled());
            // Greyed out, it says what it's for instead of offering the tabs back again.
            assertEquals("For when Marketplace only or Events only is on. Neither is on now.",
                    String.valueOf(back.getSummary()));
            assertNotNull(ShadowToast.getTextOfLatestToast());
        }
    }

    /** A row's summary without the marks that keep a switch's name in its own direction. */
    private static String plain(Preference row) {
        return String.valueOf(row.getSummary()).replace("⁨", "").replace("⁩", "");
    }

    @Test
    public void aSwitchAnotherOverridesNamesTheSwitchToTurnOff() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.START_TAB, PatchFamily.MARKETPLACE_ONLY, PatchFamily.BOTTOM_TAB_BAR);
        Settings.MARKETPLACE_ONLY.save(true);
        Settings.BOTTOM_TAB_BAR.save(true);
        Settings.TAB_BAR_SCROLL_AWAY.save(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = open(controller.get());
            Preference events = page.findPreference(Settings.EVENTS_ONLY.key);
            assertFalse(events.isEnabled());
            assertTrue(plain(events),
                    plain(events).endsWith("Turn off Marketplace only to use this."));
            Preference top = page.findPreference(Settings.TOP_TAB_BAR.key);
            assertFalse(top.isEnabled());
            assertTrue(plain(top),
                    plain(top).endsWith("Turn off Tab bar at the bottom to use this."));
            Preference keep = page.findPreference(Settings.KEEP_TAB_BAR.key);
            assertFalse(keep.isEnabled());
            assertTrue(plain(keep), plain(keep)
                    .endsWith("Turn off " + SwitchLabels.title(Settings.TAB_BAR_SCROLL_AWAY) + " to use this."));

            Setting.saveAll(Map.of(Settings.BOTTOM_TAB_BAR, false));
            page.refreshSwitches();
            ShadowLooper.idleMainLooper();
            assertTrue(top.isEnabled());
            assertFalse(plain(top), plain(top).contains("Turn off"));
        } finally {
            Settings.BOTTOM_TAB_BAR.resetToDefault();
            Settings.TAB_BAR_SCROLL_AWAY.resetToDefault();
        }
    }

    @Test
    public void marketplaceOnlysOwnRowsSitUnderItBeforeEventsOnly() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = open(controller.get());
            PreferenceGroup opening = page.findPreference(Settings.MARKETPLACE_ONLY.key).getParent();
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < opening.getPreferenceCount(); i++) keys.add(opening.getPreference(i).getKey());
            int marketplace = keys.indexOf(Settings.MARKETPLACE_ONLY.key);
            assertEquals(keys.toString(), Settings.MARKETPLACE_QUIET_NOTIFICATIONS.key, keys.get(marketplace + 1));
            assertEquals(keys.toString(), Settings.MARKETPLACE_SKIP_FEED_PREFETCH.key, keys.get(marketplace + 2));
            assertEquals(keys.toString(), Settings.EVENTS_ONLY.key, keys.get(marketplace + 3));
            assertEquals(keys.toString(), "action_regular_facebook", keys.get(marketplace + 4));
        }
    }

    @Test
    public void eventsOnlyHasItsOwnRowAndSummaryAndReturnToRegularTurnsBothOff() {
        assertFalse(Settings.EVENTS_ONLY.defaultValue);
        Settings.EVENTS_ONLY.save(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = open(controller.get());
            SwitchPreference row = (SwitchPreference) page.findPreference(Settings.EVENTS_ONLY.key);
            assertNotNull(row);
            assertTrue(row.isChecked());
            assertEquals("Events only", String.valueOf(row.getTitle()));
            assertEquals(HushfacebookPreferenceFragment.eventsSummary(), String.valueOf(row.getSummary()));
            // The single-tab state is Events' now, so Marketplace only's row doesn't take it as its own.
            assertFalse(HushfacebookPreferenceFragment.eventsSummary().startsWith("Off."));
            CharSequence marketplace = page.findPreference(Settings.MARKETPLACE_ONLY.key).getSummary();
            assertTrue(String.valueOf(marketplace), String.valueOf(marketplace).startsWith("Off."));
            Preference tab = page.findPreference(Settings.START_TAB.key);
            assertFalse(tab.isEnabled());
            assertEquals("Events only chooses the opening tab. Your previous choice stays saved.",
                    String.valueOf(tab.getSummary()));
            assertFalse(page.findPreference(Settings.MARKETPLACE_SKIP_FEED_PREFETCH.key).isEnabled());
            // Events isn't one of the opening tab choices.
            for (CharSequence value : ((android.preference.ListPreference) tab).getEntryValues()) {
                assertFalse(String.valueOf(value), "EVENTS".contentEquals(value));
            }

            Preference back = titled(page.getPreferenceScreen(), "Return to regular Facebook");
            assertTrue(back.isEnabled());
            assertTrue(back.getOnPreferenceClickListener().onPreferenceClick(back));
            ShadowLooper.idleMainLooper();
            assertFalse(Settings.EVENTS_ONLY.savedValue());
            assertFalse(Settings.MARKETPLACE_ONLY.savedValue());
            assertFalse(row.isChecked());
            assertFalse(back.isEnabled());
            assertTrue(tab.isEnabled());
        }
    }

    @Test
    public void restartFeedbackFollowsTheBuiltBarInsteadOfAnUnavailableChoice() {
        Settings.MARKETPLACE_ONLY.save(true);
        MarketplaceOnly.hidesTab(false, new Object(), Collections.emptyList(), Collections.emptySet());
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = open(controller.get());
            Settings.MARKETPLACE_ONLY.save(false);
            assertFalse(page.noteRestartPending(Settings.MARKETPLACE_ONLY, true));
            assertFalse(HushfacebookPreferenceFragment.restartPending.contains(Settings.MARKETPLACE_ONLY.key));
            Settings.MARKETPLACE_ONLY.save(true);
            MarketplaceOnlyForTests.hidesHome();
            Settings.MARKETPLACE_ONLY.save(false);
            assertTrue(page.noteRestartPending(Settings.MARKETPLACE_ONLY, true));
            assertTrue(HushfacebookPreferenceFragment.restartPending.contains(Settings.MARKETPLACE_ONLY.key));
        }
    }

    /** Real Android rows rendered in memory. No phone or desktop display receives input. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk = 30, qualifiers = "w390dp-h844dp-night-xhdpi")
    public void renderMarketplaceChoicesAndRecoveryOffscreen() throws Exception {
        File directory = new File("build/reports/marketplace");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        for (boolean active : new boolean[]{false, true}) {
            MarketplaceOnlyForTests.resetState();
            Settings.MARKETPLACE_ONLY.save(active);
            MarketplaceOnlyForTests.hidesHome();
            try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
                SettingsDialog dialog = SettingsL10nTest.show(controller.get());
                HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(dialog);
                assertNotNull(page.navigation);
                page.navigation.navigate("Opening Facebook");
                View root = dialog.getView();
                assertNotNull(root);
                int width = 780, height = 4200;
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, width, height);
                ListView list = root.findViewById(android.R.id.list);
                assertNotNull(list);
                ShadowLooper.idleMainLooper();
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, width, height);
                assertTrue(list.getChildCount() > 4);
                assertEquals(!active, page.findPreference(Settings.START_TAB.key).isEnabled());
                assertEquals(active, page.findPreference(Settings.MARKETPLACE_SKIP_FEED_PREFETCH.key).isEnabled());
                // Capture the current dialog host, with enough height to show both saved choices.
                int last = list.getCount() - 1;
                assertEquals(last, list.getLastVisiblePosition());
                View lastRow = list.getChildAt(last - list.getFirstVisiblePosition());
                assertNotNull(lastRow);
                Rect lastBounds = new Rect();
                lastRow.getDrawingRect(lastBounds);
                ((ViewGroup) root).offsetDescendantRectToMyCoords(lastRow, lastBounds);
                int bottom = lastBounds.bottom + 32;
                Bitmap image = Bitmap.createBitmap(width, bottom, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(image);
                root.draw(canvas);
                File file = new File(directory, active ? "marketplace-active.png" : "marketplace-off.png");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
                }
                assertTrue("rendered screen is empty", file.length() > 20000);
                image.recycle();
            }
        }
    }
}
