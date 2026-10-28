/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static app.morphe.extension.facebook.misc.CloseWhenLeftForTests.SIDE_PID;
import static app.morphe.extension.facebook.misc.CloseWhenLeftForTests.arm;
import static app.morphe.extension.facebook.misc.CloseWhenLeftForTests.context;
import static app.morphe.extension.facebook.misc.CloseWhenLeftForTests.ended;
import static app.morphe.extension.facebook.misc.CloseWhenLeftForTests.processes;
import static app.morphe.extension.facebook.misc.CloseWhenLeftForTests.task;
import static app.morphe.extension.facebook.misc.CloseWhenLeftForTests.pass;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.PowerManager;
import android.os.Process;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAppTask;

import java.util.Arrays;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Close Facebook when you leave it (#202): a few seconds after the last screen goes to the
 * background, Facebook's services stop, its task leaves the recent apps and its processes end. A
 * return in time, the screen going off, a process someone is using and another app's screen over
 * Facebook's task each keep it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CloseWhenLeftTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        CloseWhenLeft.forgetForTests();
        Settings.CLOSE_WHEN_LEFT.resetToDefault();
    }

    private static ShadowAppTask shadow(ActivityManager.AppTask task) {
        return Shadow.extract(task);
    }

    @Test
    public void itStartsOff() {
        assertFalse(Settings.CLOSE_WHEN_LEFT.defaultValue);
    }

    @Test
    public void leavingFacebookEndsItAndTakesItOutOfTheRecentApps() {
        Settings.CLOSE_WHEN_LEFT.save(true);
        ActivityManager.AppTask task = arm();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            CloseWhenLeft.started(controller.get());
            CloseWhenLeft.stopped(controller.get());
            pass(CloseWhenLeft.GRACE_MS - 1);
            assertTrue("not before its time", ended.isEmpty());
            pass(1);
            assertEquals("Facebook's side process, then its own, and never another app's",
                    Arrays.asList(SIDE_PID, Process.myPid()), ended);
            assertTrue("out of the recent apps", shadow(task).isFinishedAndRemoved());
            Application application = (Application) context();
            Intent stopped = shadowOf(application).getNextStoppedService();
            assertEquals("com.facebook.push.PushService", stopped.getComponent().getClassName());
            assertNull("a service that's only bound isn't stopped", shadowOf(application).getNextStoppedService());
        }
    }

    @Test
    public void offFacebookKeepsRunning() {
        ActivityManager.AppTask task = arm();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            CloseWhenLeft.started(controller.get());
            CloseWhenLeft.stopped(controller.get());
            pass(CloseWhenLeft.GRACE_MS * 10);
            assertTrue(ended.isEmpty());
            assertFalse(shadow(task).isFinishedAndRemoved());
        }
    }

    @Test
    public void comingBackInTimeKeepsFacebook() {
        Settings.CLOSE_WHEN_LEFT.save(true);
        arm();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            CloseWhenLeft.started(controller.get());
            CloseWhenLeft.stopped(controller.get());
            pass(CloseWhenLeft.GRACE_MS / 2);
            CloseWhenLeft.started(controller.get());
            pass(CloseWhenLeft.GRACE_MS * 10);
            assertTrue(ended.isEmpty());
        }
    }

    @Test
    public void theScreenGoingOffWithFacebookInFrontIsNoLeave() {
        Settings.CLOSE_WHEN_LEFT.save(true);
        arm();
        shadowOf(context().getSystemService(PowerManager.class)).setIsInteractive(false);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            CloseWhenLeft.started(controller.get());
            CloseWhenLeft.stopped(controller.get());
            pass(CloseWhenLeft.GRACE_MS * 10);
            assertTrue(ended.isEmpty());
        }
    }

    @Test
    public void anUploadInAnotherProcessHoldsTheCloseUntilItsDone() {
        Settings.CLOSE_WHEN_LEFT.save(true);
        arm();
        processes(RunningAppProcessInfo.IMPORTANCE_CACHED, RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            CloseWhenLeft.started(controller.get());
            CloseWhenLeft.stopped(controller.get());
            pass(CloseWhenLeft.GRACE_MS + CloseWhenLeft.RECHECK_MS * 3);
            assertTrue("held while the upload runs", ended.isEmpty());
            processes(RunningAppProcessInfo.IMPORTANCE_CACHED, RunningAppProcessInfo.IMPORTANCE_CACHED);
            pass(CloseWhenLeft.RECHECK_MS);
            assertEquals(Arrays.asList(SIDE_PID, Process.myPid()), ended);
        }
    }

    @Test
    public void anotherAppsScreenOverFacebooksTaskHoldsIt() {
        Settings.CLOSE_WHEN_LEFT.save(true);
        arm();
        ActivityManager.AppTask picking = task(new ComponentName("com.android.documentsui",
                "com.android.documentsui.picker.PickActivity"));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            CloseWhenLeft.started(controller.get());
            CloseWhenLeft.stopped(controller.get());
            pass(CloseWhenLeft.GRACE_MS + CloseWhenLeft.RECHECK_MS);
            assertTrue(ended.isEmpty());
            assertFalse(shadow(picking).isFinishedAndRemoved());
            assertEquals("com.android.documentsui is open over Facebook", CloseWhenLeft.waitReason(context()));
        }
    }

    @Test
    public void aProcessSomeoneIsUsingIsBusy() {
        assertTrue(CloseWhenLeft.busy(RunningAppProcessInfo.IMPORTANCE_FOREGROUND));
        assertTrue(CloseWhenLeft.busy(RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE));
        assertTrue(CloseWhenLeft.busy(RunningAppProcessInfo.IMPORTANCE_VISIBLE));
        assertTrue(CloseWhenLeft.busy(RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE));
        assertTrue(CloseWhenLeft.busy(RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING));
        assertFalse(CloseWhenLeft.busy(RunningAppProcessInfo.IMPORTANCE_SERVICE));
        assertFalse(CloseWhenLeft.busy(RunningAppProcessInfo.IMPORTANCE_CACHED));
    }
}
