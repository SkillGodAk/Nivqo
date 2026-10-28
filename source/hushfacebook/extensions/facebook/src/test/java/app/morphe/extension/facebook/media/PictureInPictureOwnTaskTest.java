/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Collections;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.navigation.FacebookTabs;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * A start while a reel floats (#126): a start of the floating screen's own class that looks for a
 * task, and the first start of a task stack with Facebook's main screen under a page, get a task of
 * their own. The page's start after that first one, a start that stays in its source's task, another
 * screen, a floating main screen, no window, the switch off or a pause leave the intent as it was.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PictureInPictureOwnTaskTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int OWN = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK;

    /** The floating screen: its window state, task and class name are the test's. */
    public static final class Screen extends Activity {
        boolean floating = true;
        int task = 7;
        String className;

        @Override
        public boolean isInPictureInPictureMode() {
            return floating;
        }

        @Override
        public int getTaskId() {
            return task;
        }

        @Override
        public ComponentName getComponentName() {
            return className == null ? super.getComponentName() : new ComponentName(getPackageName(), className);
        }
    }

    /** An activity a start comes from, in a task of its own. */
    public static final class Source extends Activity {
        int task = 9;

        @Override
        public int getTaskId() {
            return task;
        }
    }

    private Screen screen;
    private Source source;

    @Before
    public void start() {
        HookStatus.clear();
        PictureInPicture.forget();
        screen = Robolectric.buildActivity(Screen.class).setup().get();
        source = Robolectric.buildActivity(Source.class).setup().get();
        Settings.PICTURE_IN_PICTURE.save(true);
        PictureInPicture.armed(screen, new Object(), null);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.PICTURE_IN_PICTURE.resetToDefault();
        PictureInPicture.forget();
        HookStatus.clear();
    }

    /** Gives [Source] the link handler's launch mode. */
    private void singleInstance() {
        ActivityInfo info = new ActivityInfo();
        info.packageName = source.getPackageName();
        info.name = Source.class.getName();
        info.launchMode = ActivityInfo.LAUNCH_SINGLE_INSTANCE;
        info.applicationInfo = RuntimeEnvironment.getApplication().getApplicationInfo();
        shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).addOrUpdateActivity(info);
    }

    private Intent startOf(String className, int flags) {
        return new Intent().setComponent(new ComponentName(screen.getPackageName(), className)).addFlags(flags);
    }

    private Intent viewerStart(int flags) {
        return startOf(Screen.class.getName(), flags);
    }

    private Intent stackFirst() {
        return startOf(FacebookTabs.MAIN_TAB_ACTIVITY, Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_TASK_ON_HOME).putExtra(PictureInPicture.UNDER_A_PAGE, true);
    }

    /**
     * The task flags [intent] has after the hook sees it started from [context], on Android 12, or 0
     * when the hook left its flags as they were.
     */
    private static int seen(Context context, Intent intent) {
        int was = PictureInPicture.apiLevel;
        int before = intent.getFlags();
        PictureInPicture.apiLevel = 31;
        try {
            PictureInPicture.ownTask(context, intent);
            return intent.getFlags() == before ? 0 : intent.getFlags() & OWN;
        } finally {
            PictureInPicture.apiLevel = was;
        }
    }

    private static int counted() {
        for (String line : HookStatus.report()) {
            if (!line.startsWith(FamilyNames.PICTURE_IN_PICTURE + ":")) continue;
            int at = line.indexOf(PictureInPicture.OWN_TASK + " ");
            if (at < 0) return 0;
            String rest = line.substring(at + PictureInPicture.OWN_TASK.length() + 1);
            return Integer.parseInt(rest.split("[^0-9]", 2)[0]);
        }
        return 0;
    }

    @Test
    public void theViewersOwnScreenFromTheLinkHandlerGetsATaskOfItsOwn() {
        singleInstance();
        assertEquals(OWN, seen(source, viewerStart(0)));
        assertEquals(1, counted());
    }

    /** Once the stack's first start has a task of its own, a screen opening in the window is left alone. */
    @Test
    public void aScreenInTheWindowAfterTheStackLeftDoesntBringFacebookBack() {
        assertEquals(OWN, seen(source, stackFirst()));
        source.task = screen.task;
        int was = PictureInPicture.apiLevel;
        PictureInPicture.apiLevel = 31;
        try {
            assertFalse(PictureInPicture.bringingBack(source, SystemClock.uptimeMillis()));
            assertTrue(PictureInPicture.bringingBack(source, SystemClock.uptimeMillis() + PictureInPicture.SENT_AWAY_GAP_MS));
        } finally {
            PictureInPicture.apiLevel = was;
        }
    }

    @Test
    public void theViewersOwnScreenLookingForATaskAnyOtherWayGetsOneToo() {
        assertEquals("asking for a new task", OWN, seen(source, viewerStart(Intent.FLAG_ACTIVITY_NEW_TASK)));
        assertEquals("from no activity", OWN, seen(RuntimeEnvironment.getApplication(), viewerStart(0)));
        source.task = screen.task;
        assertEquals("from the window's task", OWN, seen(source, viewerStart(0)));
        assertEquals(3, counted());
    }

    @Test
    public void aStartThatStaysInItsSourcesTaskIsLeftAlone() {
        assertEquals(0, seen(source, viewerStart(0)));
        assertEquals(0, counted());
        assertEquals("the hook failed instead of deciding", Collections.emptyList(), HookStatus.missing(FamilyNames.PICTURE_IN_PICTURE));
    }

    @Test
    public void anotherScreenOrAnotherAppIsLeftAlone() {
        singleInstance();
        assertEquals("another screen", 0, seen(source, startOf("com.facebook.katana.SomePage", Intent.FLAG_ACTIVITY_NEW_TASK)));
        Intent elsewhere = new Intent().setComponent(new ComponentName("com.example", Screen.class.getName()));
        assertEquals("another app", 0, seen(source, elsewhere));
        assertEquals(0, seen(source, new Intent(Intent.ACTION_VIEW)));
        assertEquals(0, counted());
    }

    @Test
    public void aStacksFirstStartGetsATaskOfItsOwnAndItsPageFollowsIt() {
        singleInstance();
        assertEquals(OWN, seen(source, stackFirst()));
        assertEquals("the page left its stack's new task", 0, seen(source, viewerStart(0)));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("after the stack went by, the page's start is one of its own again", OWN, seen(source, viewerStart(0)));
        assertEquals(2, counted());
    }

    @Test
    public void aStartAskingForANewTaskEndsTheStack() {
        singleInstance();
        seen(source, stackFirst());
        assertEquals(OWN, seen(source, viewerStart(Intent.FLAG_ACTIVITY_NEW_TASK)));
        assertEquals("the stack outlived a start of its own", OWN, seen(source, viewerStart(0)));
    }

    @Test
    public void aMainScreenStartWithoutTheStacksMarkIsLeftAlone() {
        Intent main = stackFirst();
        main.removeExtra(PictureInPicture.UNDER_A_PAGE);
        assertEquals(0, seen(source, main));
    }

    @Test
    public void aFloatingMainScreenIsLeftToTheBringBack() {
        singleInstance();
        screen.className = FacebookTabs.MAIN_TAB_ACTIVITY;
        assertEquals(0, seen(source, startOf(FacebookTabs.MAIN_TAB_ACTIVITY, Intent.FLAG_ACTIVITY_NEW_TASK)));
        assertEquals(0, seen(source, stackFirst()));
        assertEquals(0, counted());
    }

    @Test
    public void noWindowOffOrPausedIsLeftAlone() {
        singleInstance();
        screen.floating = false;
        assertEquals("not floating", 0, seen(source, viewerStart(0)));
        screen.floating = true;
        Settings.PICTURE_IN_PICTURE.save(false);
        assertEquals("off", 0, seen(source, viewerStart(0)));
        Settings.PICTURE_IN_PICTURE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals("paused", 0, seen(source, viewerStart(0)));
        PauseForTests.resume();
        PictureInPicture.disarmed();
        assertEquals("disarmed", 0, seen(source, stackFirst()));
        assertEquals(0, counted());
        PictureInPicture.armed(screen, new Object(), null);
        assertEquals("armed again: the control", OWN, seen(source, viewerStart(0)));
    }

    @Test
    public void noIntentIsNoFailure() {
        PictureInPicture.ownTask(source, null);
        assertEquals(Collections.emptyList(), HookStatus.missing(FamilyNames.PICTURE_IN_PICTURE));
    }
}
