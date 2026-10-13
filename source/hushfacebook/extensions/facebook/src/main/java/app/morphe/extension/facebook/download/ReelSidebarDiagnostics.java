/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.facebook.misc.AppLock;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;

/**
 * A bounded clue for a Reels layout that reaches neither known sidebar (#18). With Debug logging
 * and reel downloads on, a real reel start plans a read a moment later. A known sidebar built
 * shortly before the start, or before the read, cancels it. Only actual View class paths reach the
 * log. Text, descriptions, resource ids and count values are never read. This observes the layout;
 * it doesn't add a button to it.
 */
public final class ReelSidebarDiagnostics {
    static final long DELAY_MS = 1_500;
    static final long GAP_MS = 15_000;
    static final long SIDEBAR_WINDOW_MS = 5_000;
    static final int READS_MAX = 3;
    static final int WINDOWS_MAX = 3;
    static final int VIEWS_MAX = 400;
    static final int TREES_MAX = 4;
    static final int DEPTH_MAX = 40;
    static final int LINE_CHARS_MAX = 512;
    static final int PATHS_MAX = 12;
    static final int PARENTS_MAX = 8;
    static final long BUDGET_MS = 20;
    static final String SCHEDULED = "missing sidebar layout scheduled";
    static final String SAMPLED = "missing sidebar layout sampled";
    static final String LABEL = "Reel sidebar layout";

    private static final String SOURCE = "ReelSidebarDiagnostics";
    private static final String MOUNTING_VIEW = "com.facebook.litho.BaseMountingView";

    private static long generation = -1;
    private static long sidebarSequence;
    private static long lastSidebar = -SIDEBAR_WINDOW_MS;
    private static long lastRead = -GAP_MS;
    private static int reads;

    private ReelSidebarDiagnostics() {}

    /** Filled by Download any reel with the player's params getter and its isFbShorts field. */
    public static boolean isReel(Object player) {
        return false;
    }

    /** From the kept player-start tracker. Other videos, off, Pause and unready settings schedule nothing. */
    public static void started(Object player) {
        try {
            if (player != null && enabled() && isReel(player)) reelStarted();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "sidebar diagnostic reel check", failure);
        }
    }

    /** Both existing sidebar hooks call this, without keeping a player or anything on its screen. */
    static synchronized void sidebarObserved() {
        try {
            resetIfCleared();
            sidebarSequence++;
            lastSidebar = SystemClock.uptimeMillis();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "sidebar diagnostic observation", failure);
        }
    }

    private static boolean enabled() {
        return Utils.settingsReady() && BaseSettings.DEBUG.get() && Settings.DOWNLOAD_REELS.get()
                && !HushfacebookPause.isPaused();
    }

    /** The model check is separate so host tests exercise the same scheduling and capture path. */
    static synchronized void reelStarted() {
        try {
            if (!enabled()) return;
            resetIfCleared();
            long now = SystemClock.uptimeMillis();
            if (reads >= READS_MAX || now - lastRead < GAP_MS || now - lastSidebar < SIDEBAR_WINDOW_MS) return;
            reads++;
            lastRead = now;
            long observed = sidebarSequence;
            long plannedGeneration = generation;
            HookStatus.counted(FamilyNames.REEL_DOWNLOAD, SCHEDULED);
            Utils.runOnMainThreadDelayed(() -> readIfMissing(plannedGeneration, observed), DELAY_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "sidebar diagnostic schedule", failure);
        }
    }

    private static void resetIfCleared() {
        long current = HookStatus.generation();
        if (generation == current) return;
        generation = current;
        sidebarSequence = 0;
        lastSidebar = -SIDEBAR_WINDOW_MS;
        lastRead = -GAP_MS;
        reads = 0;
    }

    private static void readIfMissing(long plannedGeneration, long observed) {
        try {
            synchronized (ReelSidebarDiagnostics.class) {
                if (plannedGeneration != HookStatus.generation() || sidebarSequence != observed || !enabled()) return;
            }
            Reading reading = read(AppLock.windowsForDiagnostics(), SystemClock.elapsedRealtime() + BUDGET_MS);
            synchronized (ReelSidebarDiagnostics.class) {
                if (plannedGeneration != HookStatus.generation() || sidebarSequence != observed || !enabled()) return;
            }
            HookStatus.counted(FamilyNames.REEL_DOWNLOAD, SAMPLED);
            String body = LABEL + ": neither known sidebar observed near a reel start; "
                    + reading.views + " native views, " + reading.trees + " Litho mounting views, "
                    + reading.paths.size() + " native View paths"
                    + (reading.limited ? " (scan limit reached)" : "")
                    + (reading.unavailable ? " (native windows unavailable)" : "")
                    + ". Deeper Litho component paths unavailable; only native View classes are retained."
                    + (reading.paths.isEmpty() ? "" : "\n" + String.join("\n", reading.paths));
            Logger.diagnosticDebug(DiagnosticCategory.DOWNLOADS, SOURCE, () -> body);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "sidebar diagnostic read", failure);
        }
    }

    static final class Reading {
        int views;
        int trees;
        boolean limited;
        boolean unavailable;
        final Set<String> paths = new LinkedHashSet<>();
    }

    /** No text, accessibility properties or description dumps are read. A deadline stops our walk. */
    static Reading read(List<View> windows, long deadline) {
        Reading reading = new Reading();
        if (windows == null) {
            reading.unavailable = true;
            return reading;
        }
        if (windows.isEmpty()) return reading;
        if (SystemClock.elapsedRealtime() >= deadline) {
            reading.limited = true;
            return reading;
        }
        List<String> parents = new ArrayList<>();
        for (int i = 0; i < windows.size() && i < WINDOWS_MAX; i++) {
            collect(windows.get(i), parents, reading, 0, deadline);
        }
        if (windows.size() > WINDOWS_MAX) reading.limited = true;
        return reading;
    }

    private static void collect(View view, List<String> parents, Reading reading, int depth, long deadline) {
        if (reading.views >= VIEWS_MAX || depth >= DEPTH_MAX || reading.paths.size() >= PATHS_MAX
                || SystemClock.elapsedRealtime() >= deadline) {
            reading.limited = true;
            return;
        }
        reading.views++;
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        Class<?> type = view.getClass();
        if (namedType(type, MOUNTING_VIEW) != null) {
            if (reading.trees >= TREES_MAX) {
                reading.limited = true;
                return;
            }
            reading.trees++;
        }
        if (SystemClock.elapsedRealtime() >= deadline) {
            reading.limited = true;
            return;
        }
        parents.add(type.getName());
        try {
            if (!(view instanceof ViewGroup) || ((ViewGroup) view).getChildCount() == 0) {
                addPath(parents, reading);
                return;
            }
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (reading.views >= VIEWS_MAX || reading.paths.size() >= PATHS_MAX
                        || SystemClock.elapsedRealtime() >= deadline) {
                    reading.limited = true;
                    break;
                }
                collect(group.getChildAt(i), parents, reading, depth + 1, deadline);
            }
        } finally {
            parents.remove(parents.size() - 1);
        }
    }

    private static void addPath(List<String> parents, Reading reading) {
        int first = Math.max(0, parents.size() - PARENTS_MAX);
        int length = " [view]".length();
        for (int i = first; i < parents.size(); i++) {
            length += parents.get(i).length() + (i == first ? 0 : 3);
            if (length > LINE_CHARS_MAX) {
                reading.limited = true;
                return;
            }
        }
        reading.paths.add(String.join(" > ", parents.subList(first, parents.size())) + " [view]");
    }

    private static Class<?> namedType(Class<?> type, String name) {
        for (; type != null; type = type.getSuperclass()) if (name.equals(type.getName())) return type;
        return null;
    }

}
