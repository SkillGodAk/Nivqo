/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * A page opening in the floating window's task: an activity created in the armed screen's task while
 * that screen is in the window brings Facebook back with its launcher start, once per gap. A page in
 * another task, a screen that isn't floating, the screen itself, no arming, the switch off or a pause
 * leave the window alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PictureInPictureBringBackTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** An activity whose window state and task the test sets. */
    public static final class Screen extends Activity {
        boolean floating;
        int task = 7;

        @Override
        public boolean isInPictureInPictureMode() {
            return floating;
        }

        @Override
        public int getTaskId() {
            return task;
        }
    }

    private Screen screen;
    private Screen page;

    @Before
    public void start() {
        HookStatus.clear();
        PictureInPicture.forget();
        screen = Robolectric.buildActivity(Screen.class).setup().get();
        page = Robolectric.buildActivity(Screen.class).get();
        Settings.PICTURE_IN_PICTURE.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.PICTURE_IN_PICTURE.resetToDefault();
        PictureInPicture.forget();
        HookStatus.clear();
    }

    private static boolean decides(Activity activity, long now) {
        int was = PictureInPicture.apiLevel;
        PictureInPicture.apiLevel = 31;
        try {
            return PictureInPicture.bringingBack(activity, now);
        } finally {
            PictureInPicture.apiLevel = was;
        }
    }

    @Test
    public void aPageInTheFloatingScreensTaskBringsItBack() {
        PictureInPicture.armed(screen, new Object(), null);
        screen.floating = true;
        assertTrue(decides(page, 10_000));
    }

    @Test
    public void onlyOncePerGap() {
        PictureInPicture.armed(screen, new Object(), null);
        screen.floating = true;
        assertTrue(decides(page, 10_000));
        assertFalse("a second page inside the gap", decides(page, 10_000 + PictureInPicture.BRING_BACK_GAP_MS - 1));
        assertTrue("after the gap", decides(page, 10_000 + 2 * PictureInPicture.BRING_BACK_GAP_MS));
    }

    @Test
    public void aScreenNotInTheWindowIsLeftAlone() {
        PictureInPicture.armed(screen, new Object(), null);
        assertFalse(decides(page, 10_000));
    }

    @Test
    public void aPageInAnotherTaskIsLeftAlone() {
        PictureInPicture.armed(screen, new Object(), null);
        screen.floating = true;
        page.task = 8;
        assertFalse(decides(page, 10_000));
    }

    @Test
    public void theScreenItselfIsLeftAlone() {
        PictureInPicture.armed(screen, new Object(), null);
        screen.floating = true;
        assertFalse(decides(screen, 10_000));
    }

    @Test
    public void nothingArmedOrDisarmedIsLeftAlone() {
        screen.floating = true;
        assertFalse("never armed", decides(page, 10_000));
        PictureInPicture.armed(screen, new Object(), null);
        PictureInPicture.disarmed();
        assertFalse("disarmed", decides(page, 10_000));
    }

    @Test
    public void offOrPausedIsLeftAlone() {
        PictureInPicture.armed(screen, new Object(), null);
        screen.floating = true;
        Settings.PICTURE_IN_PICTURE.save(false);
        assertFalse("off", decides(page, 10_000));
        Settings.PICTURE_IN_PICTURE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused", decides(page, 10_000));
    }

    /** The launcher entry Facebook's manifest declares, so getLaunchIntentForPackage answers. */
    private static void addLauncher() {
        String packageName = RuntimeEnvironment.getApplication().getPackageName();
        ComponentName launcher = new ComponentName(packageName, "com.facebook.katana.LoginActivity");
        shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).addActivityIfNotPresent(launcher);
        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_LAUNCHER);
        shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).addIntentFilterForActivity(launcher, filter);
    }

    /** [page] created on Android [api], through the lifecycle callback the page watch registers, and the main thread run. */
    private static void arrive(Activity page, int api) {
        int was = PictureInPicture.apiLevel;
        PictureInPicture.apiLevel = api;
        try {
            new PictureInPicture.Arrivals().onActivityCreated(page, null);
            shadowOf(Looper.getMainLooper()).idle();
        } finally {
            PictureInPicture.apiLevel = was;
        }
    }

    @Test
    public void arrivingStartsFacebooksLauncherAndCountsIt() {
        addLauncher();
        PictureInPicture.armed(screen, new Object(), null);
        screen.floating = true;
        arrive(page, 31);
        Intent started = shadowOf(page).getNextStartedActivity();
        assertNotNull("Facebook started again", started);
        assertEquals(Intent.ACTION_MAIN, started.getAction());
        assertTrue(started.hasCategory(Intent.CATEGORY_LAUNCHER));
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                started.getFlags() & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        String line = null;
        for (String each : HookStatus.report()) {
            if (each.startsWith(FamilyNames.PICTURE_IN_PICTURE + ":")) line = each;
        }
        assertNotNull(line);
        assertTrue(line, line.contains(PictureInPicture.BROUGHT_BACK + " 1"));
    }

    @Test
    public void arrivingOutsideTheWindowStartsNothing() {
        addLauncher();
        PictureInPicture.armed(screen, new Object(), null);
        arrive(page, 31);
        assertNull(shadowOf(page).getNextStartedActivity());
    }

    @Test
    public void arrivingWithTheSwitchOffOrBeforeAndroid12StartsNothing() {
        addLauncher();
        PictureInPicture.armed(screen, new Object(), null);
        screen.floating = true;
        Settings.PICTURE_IN_PICTURE.save(false);
        arrive(page, 31);
        assertNull("off", shadowOf(page).getNextStartedActivity());
        Settings.PICTURE_IN_PICTURE.save(true);
        arrive(page, 30);
        assertNull("Android 11", shadowOf(page).getNextStartedActivity());
        arrive(page, 31);
        assertNotNull("on, Android 12: the control", shadowOf(page).getNextStartedActivity());
    }
}
