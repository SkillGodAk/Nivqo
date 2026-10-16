package app.hushmessenger.extension;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.ContextThemeWrapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

/** A long press on the Messenger logo at the top of Chats, as the patched title handler hands it over. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class LogoLongPressTest {
    @Before public void reset() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.preferences.edit().clear().commit();
        CrashGuard.resetForTests();
        Settings.hookErrors.remove("menu_row");
    }

    /** Litho hands the handler the toolbar's context, a theme wrapper around Messenger's activity. */
    private static Context toolbar(Activity chats) {
        return new ContextThemeWrapper(chats, android.R.style.Theme_Material);
    }

    /** What a Root Mount install looks like to the app: PackageManager doesn't know HushMessenger's screens. */
    private static void mount() {
        Application app = RuntimeEnvironment.getApplication();
        var packages = Shadows.shadowOf(app.getPackageManager());
        for (Class<?> screen : new Class<?>[] {SettingsActivity.class, RestartActivity.class}) {
            ComponentName name = new ComponentName(app, screen);
            app.getPackageManager().setComponentEnabledSetting(name, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            packages.removeActivity(name);
        }
    }

    @Test public void aLongPressOnTheLogoOpensSettingsOverChats() {
        Application app = RuntimeEnvironment.getApplication();
        try (var chats = Robolectric.buildActivity(Activity.class).setup()) {
            Settings.logoLongPressed(toolbar(chats.get()));
            Intent opened = Shadows.shadowOf(chats.get()).getNextStartedActivity();
            assertNotNull(opened);
            assertEquals(new ComponentName(app, SettingsActivity.class), opened.getComponent());
            // The same launch the Menu rows make: settings in their own task, in front of Chats.
            assertTrue(HostScreens.intentFor(app, HostScreens.SETTINGS).filterEquals(opened));
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, opened.getFlags());
            // One press, one screen.
            assertNull(Shadows.shadowOf(chats.get()).getNextStartedActivity());
        }
        assertNull(Settings.hookErrors.get("menu_row"));
    }

    @Test public void pauseAndSafeModeLeaveTheLogoAWayBackIn() {
        Settings.preferences.edit().putBoolean("paused", true).putBoolean("safe_mode", true).commit();
        try (var chats = Robolectric.buildActivity(Activity.class).setup()) {
            Settings.logoLongPressed(toolbar(chats.get()));
            Intent opened = Shadows.shadowOf(chats.get()).getNextStartedActivity();
            assertNotNull(opened);
            assertEquals(SettingsActivity.class.getName(), opened.getComponent().getClassName());
        }
    }

    @Test public void onARootMountInstallTheLogoOpensSettingsInsideTheStockHost() {
        mount();
        try (var chats = Robolectric.buildActivity(Activity.class).setup()) {
            Settings.logoLongPressed(toolbar(chats.get()));
            Intent opened = Shadows.shadowOf(chats.get()).getNextStartedActivity();
            assertNotNull(opened);
            assertEquals(HostScreens.SCREEN_HOST, opened.getComponent().getClassName());
            assertEquals("settings", opened.getStringExtra(HostScreens.EXTRA));
        }
    }

    @Test public void aLaunchAndroidRefusesShowsWhereElseToGoAndKeepsMessengerRunning() {
        Application app = RuntimeEnvironment.getApplication();
        Context refusing = new ContextWrapper(app) {
            @Override public void startActivity(Intent intent) {
                throw new ActivityNotFoundException("refused");
            }
        };
        Settings.logoLongPressed(refusing);
        assertEquals(new SettingsText(app).get("settings_open_failed"), ShadowToast.getTextOfLatestToast());
        assertNotNull(Settings.hookErrors.get("menu_row"));
    }

    @Test public void aPressWithNoContextIsLeftAlone() {
        Settings.logoLongPressed(null);
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
        assertNull(Settings.hookErrors.get("menu_row"));
    }
}
