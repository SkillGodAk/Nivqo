/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.download.MediaDownload;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Close Facebook when you leave it (#202). With its switch on, a few seconds after Facebook's last
 * screen goes to the background its tasks leave the recent apps and every one of its processes
 * ends, so nothing of it stays in memory until it's opened again.
 *
 * <p>Leaving is the last started screen of the main process stopping. A rotation isn't one, and
 * neither is the screen going off with Facebook in front, since Android stops the screens after
 * the phone stops being interactive. A picture-in-picture window is still a started screen.
 *
 * <p>When the time is up it looks again, and waits, looking every few seconds, while a Facebook
 * screen is back, any Facebook process is in front, visible, noticeable or running a foreground
 * service (an upload, a call, a screen of another process such as the crash screen's), a
 * Hushfacebook save is still running, or a Facebook task has another app's screen on top, such as
 * a file picker or camera Facebook is waiting on.
 *
 * <p>Then it stops Facebook's running services, so Android doesn't start a sticky one again,
 * takes Facebook's tasks out of the recent apps, and ends its other processes and then its own.
 * The wait is short so it runs before Android freezes a cached app, which would hold the timer.
 */
public final class CloseWhenLeft {
    /** How long after the last screen stops Facebook closes. */
    static final long GRACE_MS = 3_000;

    /** How often a close that's waiting looks again. */
    static final long RECHECK_MS = 5_000;

    /** Ends a process: Android's, or a test's stand-in. */
    interface Ender {
        void end(int pid);
    }

    static Ender ender = Process::killProcess;

    /** Main process screens between their start and their stop. */
    private static int started;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable check = CloseWhenLeft::check;
    private static WeakReference<Context> leftFrom = new WeakReference<>(null);

    /** The application this process's callbacks went on, so a second start of the hook adds none. */
    private static WeakReference<Application> watching = new WeakReference<>(null);

    private CloseWhenLeft() {
    }

    /** From the settings entry's application hook, in the main process. */
    public static void watch(Context context) {
        if (!(context instanceof Application)) return;
        Application application = (Application) context;
        if (watching.get() == application) return;
        application.registerActivityLifecycleCallbacks(new Watcher());
        watching = new WeakReference<>(application);
    }

    static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { started(activity); }
        @Override public void onActivityResumed(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { stopped(activity); }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) { }
    }

    static void started(Activity activity) {
        started++;
        main.removeCallbacks(check);
    }

    static void stopped(Activity activity) {
        try {
            if (started > 0) started--;
            if (started > 0 || activity.isChangingConfigurations() || !switchedOn()) return;
            Context app = activity.getApplicationContext();
            PowerManager power = app.getSystemService(PowerManager.class);
            if (power != null && !power.isInteractive()) return;
            leftFrom = new WeakReference<>(app);
            main.removeCallbacks(check);
            main.postDelayed(check, GRACE_MS);
        } catch (Throwable failure) {
            Logger.printException(() -> "Close when left: could not tell whether Facebook was left", failure);
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.CLOSE_WHEN_LEFT.get();
    }

    static void check() {
        try {
            Context context = leftFrom.get();
            if (context == null || started > 0 || !switchedOn()) return;
            String wait = waitReason(context);
            if (wait != null) {
                Logger.printDebug(() -> "Close when left: waiting, " + wait);
                main.postDelayed(check, RECHECK_MS);
                return;
            }
            close(context);
        } catch (Throwable failure) {
            Logger.printException(() -> "Close when left: could not close Facebook", failure);
        }
    }

    /** Why Facebook can't close yet, or null when it can. */
    @Nullable
    static String waitReason(Context context) {
        if (MediaDownload.saving()) return "a save is running";
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        for (RunningAppProcessInfo process : processes(manager)) {
            if (process.uid == Process.myUid() && busy(process.importance)) {
                return process.processName + " is in use (" + process.importance + ")";
            }
        }
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            try {
                ComponentName top = task.getTaskInfo().topActivity;
                if (top != null && !context.getPackageName().equals(top.getPackageName())) {
                    return top.getPackageName() + " is open over Facebook";
                }
            } catch (IllegalArgumentException gone) {
                // The task closed since the list was made.
            }
        }
        return null;
    }

    /** Whether a process of this [importance] is one someone is using. */
    static boolean busy(int importance) {
        return importance <= RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE
                || importance == RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING
                || importance == RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE;
    }

    static void close(Context context) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        int uid = Process.myUid();
        int services = 0;
        @SuppressWarnings("deprecation") // Lists this app's own services, which is all it's used for.
        List<RunningServiceInfo> running = manager.getRunningServices(Integer.MAX_VALUE);
        if (running != null) {
            for (RunningServiceInfo service : running) {
                if (service.uid != uid || !service.started || service.service == null) continue;
                try {
                    if (context.stopService(new Intent().setComponent(service.service))) services++;
                } catch (RuntimeException failure) {
                    Logger.printDebug(() -> "Close when left: could not stop " + service.service.getClassName(), failure);
                }
            }
        }
        int tasks = 0;
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            try {
                task.finishAndRemoveTask();
                tasks++;
            } catch (IllegalArgumentException gone) {
                // The task closed since the list was made.
            }
        }
        int me = Process.myPid();
        int others = 0;
        for (RunningAppProcessInfo process : processes(manager)) {
            if (process.uid != uid || process.pid == me) continue;
            ender.end(process.pid);
            others++;
        }
        int stoppedServices = services, removedTasks = tasks, endedProcesses = others;
        Logger.printDebug(() -> "Close when left: stopped " + stoppedServices + " services, removed "
                + removedTasks + " tasks, ended " + endedProcesses + " other processes, ending this one");
        ender.end(me);
    }

    private static List<RunningAppProcessInfo> processes(ActivityManager manager) {
        List<RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        return processes != null ? processes : Collections.emptyList();
    }

    /** Forgets this process's screens and any close that's waiting. */
    static void forgetForTests() {
        started = 0;
        main.removeCallbacks(check);
        leftFrom = new WeakReference<>(null);
        ender = Process::killProcess;
    }
}
