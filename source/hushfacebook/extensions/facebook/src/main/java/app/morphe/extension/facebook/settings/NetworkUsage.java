/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * How much Facebook downloaded and uploaded, for the diagnostic report's [NETWORK] section: since
 * Hushfacebook started in this process (at the end of Facebook's application start, so bytes
 * that start moved are left out), and since Facebook last came to the foreground. Android already
 * counts every app's bytes ({@link TrafficStats#getUidRxBytes}), so nothing is hooked and nothing
 * leaves the phone: the counters are read at that start, each time a Facebook screen starts after
 * none were showing, and when the report is made. The count is per app, so it covers all of
 * Facebook's processes (games, ads), and it includes Hushfacebook's own saves.
 */
public final class NetworkUsage {
    /** The app's byte counters, or a negative number where the phone doesn't keep them. */
    interface Counter {
        long received();
        long sent();
    }

    /** The counters at one moment, on the elapsed-realtime clock. */
    static final class Mark {
        final long received, sent, at;

        Mark(long received, long sent, long at) {
            this.received = received;
            this.sent = sent;
            this.at = at;
        }
    }

    private static final Counter UID = new Counter() {
        @Override public long received() { return TrafficStats.getUidRxBytes(Process.myUid()); }
        @Override public long sent() { return TrafficStats.getUidTxBytes(Process.myUid()); }
    };

    /**
     * A rebuilt screen's new copy starts well under a second after the old one stopped, so a
     * start later than this is coming back, not the rebuild.
     */
    static final long REBUILD_WINDOW = 10_000;

    private static final Object LOCK = new Object();
    private static Counter counter = UID;
    @Nullable private static Mark started, foreground;
    private static int startedActivities;
    private static boolean watching;
    /** When the last screen stopped only to be rebuilt, or -1. */
    private static long rebuildingSince = -1;

    private NetworkUsage() {
    }

    /** From the main process's application start: the baseline, and a watch for the foreground. */
    public static void start(Context context) {
        try {
            started(SystemClock.elapsedRealtime());
            synchronized (LOCK) {
                if (watching || !(context instanceof Application)) return;
                watching = true;
            }
            ((Application) context).registerActivityLifecycleCallbacks(new Foreground());
        } catch (Throwable ex) {
            // Never in the way of the rest of Facebook's start.
            Logger.printException(() -> "Network usage: could not start counting", ex);
        }
    }

    static void started(long now) {
        synchronized (LOCK) {
            if (started == null) started = read(now);
        }
    }

    /**
     * Coming to the foreground is the first activity to start after none were, unless the last
     * one only stopped to be rebuilt. A rebuilt copy that never started doesn't hide the next
     * real return: a start long after that stop is one.
     */
    static void activityStarted(long now) {
        synchronized (LOCK) {
            boolean rebuilt = rebuildingSince >= 0 && now - rebuildingSince <= REBUILD_WINDOW;
            if (startedActivities++ == 0 && !rebuilt) foreground = read(now);
            rebuildingSince = -1;
        }
    }

    /**
     * A screen rebuilt for a rotation, a dark mode or font change stops before its new copy
     * starts, which isn't leaving the foreground.
     */
    static void activityStopped(boolean changingConfigurations, long now) {
        synchronized (LOCK) {
            if (startedActivities > 0) startedActivities--;
            if (startedActivities == 0) rebuildingSince = changingConfigurations ? now : -1;
        }
    }

    private static Mark read(long now) {
        return new Mark(counter.received(), counter.sent(), now);
    }

    public static final LogBufferManager.ReportSection REPORT = new LogBufferManager.ReportSection() {
        @Override public String title() { return "NETWORK"; }
        @Override public boolean isAppState() { return true; }
        @Override public List<String> lines() { return NetworkUsage.lines(SystemClock.elapsedRealtime()); }
    };

    static List<String> lines(long now) {
        Mark since, front, current;
        synchronized (LOCK) {
            since = started;
            front = foreground;
            current = read(now);
        }
        if (since == null) return Collections.singletonList("not counted in this process, it starts with Facebook");
        if (current.received < 0 || current.sent < 0 || since.received < 0 || since.sent < 0) {
            return Collections.singletonList("not available, this phone doesn't count Facebook's traffic");
        }
        List<String> lines = new ArrayList<>(2);
        lines.add(line("since start", since, current));
        // A mark the phone answered -1 for would count everything since the phone started.
        if (front != null && front.received >= 0 && front.sent >= 0) {
            lines.add(line("this time in the foreground", front, current));
        }
        return lines;
    }

    private static String line(String label, Mark from, Mark to) {
        String head = label + " (" + duration(to.at - from.at) + "): ";
        long received = to.received - from.received, sent = to.sent - from.sent;
        // Android starts the counters over now and then (a network stack restart), never per app.
        if (received < 0 || sent < 0) return head + "the phone started its counters over, nothing to compare";
        return head + size(received) + " received, " + size(sent) + " sent";
    }

    static String size(long bytes) {
        if (bytes < 999_500) return Math.round(bytes / 1000.0) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
    }

    static String duration(long millis) {
        long minutes = Math.max(0, millis) / 60_000;
        if (minutes < 1) return "under a minute";
        if (minutes < 60) return minutes + " min";
        return minutes / 60 + " h " + minutes % 60 + " min";
    }

    static void resetForTests(Counter replacement) {
        synchronized (LOCK) {
            counter = replacement == null ? UID : replacement;
            started = null;
            foreground = null;
            startedActivities = 0;
            rebuildingSince = -1;
        }
    }

    /** Runs inside Facebook's own activity starts and stops, so nothing may escape it. */
    private static final class Foreground implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityStarted(@NonNull Activity activity) {
            try {
                activityStarted(SystemClock.elapsedRealtime());
            } catch (Throwable ex) {
                Logger.printException(() -> "Network usage: could not mark the foreground", ex);
            }
        }

        @Override public void onActivityStopped(@NonNull Activity activity) {
            try {
                activityStopped(activity.isChangingConfigurations(), SystemClock.elapsedRealtime());
            } catch (Throwable ex) {
                Logger.printException(() -> "Network usage: could not count a stop", ex);
            }
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityResumed(@NonNull Activity activity) { }
        @Override public void onActivityPaused(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }
}
