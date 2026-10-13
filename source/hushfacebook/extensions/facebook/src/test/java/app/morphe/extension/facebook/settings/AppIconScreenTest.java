/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Looper;
import android.preference.Preference;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import app.morphe.extension.shared.SettingsContextRule;

/**
 * Pick an app icon (#122): the row starts Facebook's own App icon page by its explicit component,
 * with the entry point Facebook's Settings row passes, so the page opens on accounts where Facebook
 * doesn't list it. A build without the page gets a toast instead of a crash.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class AppIconScreenTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity activity;

    @Before
    public void open() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }

    @Test
    public void thePageIsFacebooksOwnActivityInThisPackage() {
        Intent screen = AppIconScreen.screenIntent(activity);
        assertEquals(new ComponentName(activity.getPackageName(), "com.facebook.appicon.ChangeAppIconActivity"),
                screen.getComponent());
        assertEquals("settings_bookmark", screen.getStringExtra("extra_entry_point"));
        assertNull("the page needs no action", screen.getAction());
        assertNull("the extra is read only without a URI", screen.getData());
    }

    @Test
    public void theRowStartsThePage() {
        Preference row = AppIconScreen.row(activity);
        assertEquals("Pick an app icon", String.valueOf(row.getTitle()));
        assertTrue(String.valueOf(row.getSummary()), String.valueOf(row.getSummary()).startsWith("Opens Facebook's App icon page"));
        assertFalse("the row saved something", row.isPersistent());

        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull("nothing opened", started);
        assertEquals(AppIconScreen.screenIntent(activity).getComponent(), started.getComponent());
        assertEquals("settings_bookmark", started.getStringExtra("extra_entry_point"));
        assertNull("something else opened as well", shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void aBuildWithoutThePageShowsAToastInsteadOfClosing() {
        shadowOf(activity.getApplication()).checkActivities(true);
        AppIconScreen.open(activity);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("This Facebook build has no App icon page.", ShadowToast.getTextOfLatestToast());
    }
}
