/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * What Facebook's module downloader did before its download screen closed, for the module
 * download line of the diagnostic report, while Debug logging is on (#168). In 0.15.0's reports a
 * game's download screen closed a tenth of a second after opening with only "Failed, no exception",
 * which several of the downloader's own steps answer before any fetch. So the patch hands this each
 * step the downloader takes, and the task the download screen got back when it failed, and the line
 * says which step it was and what the task held.
 *
 * <p>Steps and tasks are read as they come: a step by its own text ("RequestAction{modules=[...]_...}"),
 * a task by its fields, read by type since their names change with every Facebook build. Nothing is
 * kept with Debug logging off.
 */
public final class ModuleDownloadEvidence {
    /** The steps kept, oldest dropped first. A game's download takes two or three. */
    static final int LIMIT = 12;

    /** How long a step stays the download screen's, in milliseconds. */
    static final long WINDOW = 30_000;

    /** The most of one step's text kept. */
    static final int TEXT_LIMIT = 200;

    /** The most of the task's text kept, enough for its result's code and flags after a cause's message. */
    static final int TASK_LIMIT = ScreenLog.ERROR_LIMIT;

    private static final class Step {
        final long at;
        final String text;

        Step(long at, String text) {
            this.at = at;
            this.text = text;
        }
    }

    private static final ArrayDeque<Step> STEPS = new ArrayDeque<>();

    /** What the last failed task held, and when, until the download screen's line takes it. */
    private static String failed;
    private static long failedAt;

    /** Whether, and when, Facebook last wanted Google Play for a module and got its own downloader instead. */
    private static boolean playWasSkipped;
    private static long playSkippedAt;

    private ModuleDownloadEvidence() {}

    private static boolean on() {
        return Utils.settingsReady() && BaseSettings.DEBUG.get();
    }

    /** Called by the downloader for each step it takes, on whichever thread runs it. Never throws. */
    public static void step(Object action) {
        try {
            if (action == null || !on()) return;
            String text = cut(String.valueOf(action), TEXT_LIMIT);
            synchronized (STEPS) {
                while (STEPS.size() >= LIMIT) STEPS.removeFirst();
                STEPS.addLast(new Step(SystemClock.elapsedRealtime(), text));
            }
        } catch (Throwable ignored) {
            // A step that can't be read is left out rather than holding the download up.
        }
    }

    /** Called when the download screen's task came back failed, before the screen closes. Never throws. */
    public static void failed(Object task) {
        try {
            if (task == null || !on()) return;
            String held = task(task);
            synchronized (STEPS) {
                failed = held;
                failedAt = SystemClock.elapsedRealtime();
            }
        } catch (Throwable ignored) {
            // A task that can't be read is left out rather than holding the screen up.
        }
    }

    /** Called by {@link ModuleDownloads} when Facebook wanted Google Play for a module. Never throws. */
    static void playSkipped() {
        try {
            if (!on()) return;
            synchronized (STEPS) {
                playWasSkipped = true;
                playSkippedAt = SystemClock.elapsedRealtime();
            }
        } catch (Throwable ignored) {
            // Left out rather than holding the download up.
        }
    }

    /**
     * The task's text: the exception it holds, and the result object it holds, by its fields in
     * order. A result is any field holding something other than a plain lock object, a list of
     * waiting callbacks, or a flag.
     */
    static String task(Object task) throws IllegalAccessException {
        List<String> parts = new ArrayList<>();
        Throwable exception = null;
        Object result = null;
        for (Class<?> type = task.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                Object value = field.get(task);
                if (value == null || value.getClass() == Object.class || value instanceof List) continue;
                if (value instanceof Throwable) {
                    if (exception == null) exception = (Throwable) value;
                } else if (result == null) {
                    result = value;
                }
            }
        }
        parts.add(exception == null ? "no exception" : "exception " + exception);
        parts.add(result == null ? "no result" : "result " + fields(result, 2));
        return cut(String.join(", ", parts), TASK_LIMIT);
    }

    /**
     * [value]'s own fields in order, by name, as "{A00=-1 A01=null}". A field holding another of
     * Facebook's objects is written the same way, [depth] levels down: the downloader wraps one
     * result in another, and neither has text of its own. An exception is written as its own text,
     * its class and message, since its fields say nothing a report can use.
     */
    static String fields(Object value, int depth) throws IllegalAccessException {
        StringBuilder out = new StringBuilder("{");
        for (Field field : value.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object held = field.get(value);
            if (out.length() > 1) out.append(' ');
            out.append(field.getName()).append('=');
            boolean nested = held != null && depth > 1 && !(held instanceof Throwable) && facebooks(held);
            out.append(nested ? fields(held, depth - 1) : String.valueOf(held));
        }
        return out.append('}').toString();
    }

    /** Whether [value] is one of Facebook's own objects rather than Java's or Android's. */
    private static boolean facebooks(Object value) {
        String name = value.getClass().getName();
        return !name.startsWith("java.") && !name.startsWith("android.") && !name.startsWith("javax.");
    }

    /**
     * What to add to the download screen's line for [modules], as it closes at [now]: whether
     * Google Play was skipped in the last {@link #WINDOW}, the steps from that time that name one
     * of the modules (every recent step when the screen named none), then the task that failed in
     * that time. Empty when nothing was kept. All are cleared, so the next screen's line starts
     * fresh, and a task that failed after its own screen had already closed never turns up on
     * another one's line much later.
     */
    static String forScreen(List<String> modules, long now) {
        List<String> steps = new ArrayList<>();
        String task;
        boolean skipped;
        synchronized (STEPS) {
            for (Step step : STEPS) {
                if (now - step.at > WINDOW) continue;
                if (modules.isEmpty() || names(step.text, modules)) steps.add(step.text);
            }
            STEPS.clear();
            task = failed != null && now - failedAt <= WINDOW ? failed : null;
            failed = null;
            skipped = playWasSkipped && now - playSkippedAt <= WINDOW;
            playWasSkipped = false;
        }
        StringBuilder out = new StringBuilder();
        if (skipped) out.append(", Google Play skipped");
        if (!steps.isEmpty()) out.append(", steps: ").append(String.join(" then ", steps));
        if (task != null) out.append(", task: ").append(task);
        return out.toString();
    }

    private static boolean names(String text, List<String> modules) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String module : modules) {
            if (lower.contains(module.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String cut(String text, int limit) {
        return text.length() > limit ? text.substring(0, limit) + "..." : text;
    }

    static void clearForTests() {
        synchronized (STEPS) {
            STEPS.clear();
            failed = null;
            playWasSkipped = false;
        }
    }
}
