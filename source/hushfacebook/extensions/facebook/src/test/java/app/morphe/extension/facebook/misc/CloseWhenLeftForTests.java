/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ActivityManager.RunningServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;

import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAppTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A phone for Close Facebook when you leave it to run on, with Android's process ending stood in for. */
public final class CloseWhenLeftForTests {
    /** A side process of Facebook's, such as its push service. */
    static final int SIDE_PID = 4242;

    /** Another app's process, which shares no uid with Facebook. */
    static final int OTHER_APP_PID = 777;

    /** The pids ended since [arm], in order. */
    static final List<Integer> ended = new ArrayList<>();

    private CloseWhenLeftForTests() {
    }

    static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    static ActivityManager activityManager() {
        return context().getSystemService(ActivityManager.class);
    }

    /** A fresh process on a phone that's on, with Facebook's main and side processes in the background. */
    static ActivityManager.AppTask arm() {
        CloseWhenLeft.forgetForTests();
        ended.clear();
        CloseWhenLeft.ender = ended::add;
        shadowOf(context().getSystemService(PowerManager.class)).setIsInteractive(true);
        processes(RunningAppProcessInfo.IMPORTANCE_CACHED, RunningAppProcessInfo.IMPORTANCE_SERVICE);
        RunningServiceInfo push = new RunningServiceInfo();
        push.service = new ComponentName(context(), "com.facebook.push.PushService");
        push.uid = Process.myUid();
        push.pid = SIDE_PID;
        push.started = true;
        RunningServiceInfo bound = new RunningServiceInfo();
        bound.service = new ComponentName(context(), "com.facebook.BoundService");
        bound.uid = Process.myUid();
        bound.started = false;
        shadowOf(activityManager()).setServices(Arrays.asList(push, bound));
        return task(new ComponentName(context(), "com.facebook.katana.LoginActivity"));
    }

    /** Facebook's main process at [main] and its side process at [side], beside another app's. */
    static void processes(int main, int side) {
        RunningAppProcessInfo own = new RunningAppProcessInfo(context().getPackageName(), Process.myPid(), null);
        own.uid = Process.myUid();
        own.importance = main;
        RunningAppProcessInfo push = new RunningAppProcessInfo(context().getPackageName() + ":fbns", SIDE_PID, null);
        push.uid = Process.myUid();
        push.importance = side;
        RunningAppProcessInfo other = new RunningAppProcessInfo("com.example.other", OTHER_APP_PID, null);
        other.uid = Process.myUid() + 1;
        other.importance = RunningAppProcessInfo.IMPORTANCE_CACHED;
        shadowOf(activityManager()).setProcesses(Arrays.asList(own, push, other));
    }

    /** Facebook's one task, with [top] the screen in front of it. */
    static ActivityManager.AppTask task(ComponentName top) {
        ActivityManager.AppTask task = ShadowAppTask.newInstance();
        ActivityManager.RecentTaskInfo info = new ActivityManager.RecentTaskInfo();
        info.topActivity = top;
        ((ShadowAppTask) Shadow.extract(task)).setTaskInfo(info);
        shadowOf(activityManager()).setAppTasks(Collections.singletonList(task));
        return task;
    }

    /** Lets [millis] of the main thread's time go by. */
    static void pass(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** Whether opening a Facebook screen and leaving it ended Facebook. The probe for the paused tests. */
    public static boolean aLeaveCloses() {
        arm();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            CloseWhenLeft.started(controller.get());
            CloseWhenLeft.stopped(controller.get());
            pass(CloseWhenLeft.GRACE_MS);
            return !ended.isEmpty();
        } finally {
            CloseWhenLeft.forgetForTests();
        }
    }
}
