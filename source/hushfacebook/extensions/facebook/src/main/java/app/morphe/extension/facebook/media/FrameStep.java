/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Frame step: with a reel or video paused, a tap on its right third moves it one frame forward and
 * a tap on its left third one frame back. The middle third, and every tap on a video that's
 * playing, goes to Facebook as before.
 *
 * <p>The patch tells this class about FbGrootPlayer, the player nearly every Facebook surface plays
 * video with, at the same places Resume long videos does: {@link #started} and {@link #stopped}
 * first in its kept-name maybeTrackVideoStart and maybeTrackVideoStop, {@link #released} where it's
 * released, and {@link #seeking} first in the seek every seek goes through. A player whose last word
 * was a stop is a paused one here, until it starts or is released.
 *
 * <p>Taps come from {@link #touch}, first in the touch listener of GestureReactionComponent's view,
 * the layer Facebook lays over nearly every reel and video player. It sees only touches that reach
 * the video. A quick tap with one finger on a side third, over the view of a paused player that
 * isn't playing, seeks that player by {@link #FRAME_MS} with {@link #STEP_TRIGGER}. The tap is then
 * handed on as a cancel, so Facebook's gesture detector doesn't also take it as a tap to play or the
 * first half of a double tap. A seek this small is one Facebook's player can drop as too close to
 * where the video is, so {@link #stepping} answers that check no for the step's own seek alone.
 *
 * <p>The trigger decides how exact the seek is. Facebook's player seeks fast for BY_USER, with
 * parameters that let it land up to two seconds before the place asked for: on Facebook 582 a paused
 * reel stepped with BY_USER landed on a keyframe up to two seconds back, or two seconds early when
 * none was there. BY_INITIAL_FRAME_PERFECT_SEEK is one it seeks to the exact frame for.
 *
 * <p>In case a player still lands off, a moment after each step this class reads where the player
 * says it is. A landing more than {@link #OFF_BY} away from the frame wanted teaches it how far off
 * that player lands, the step is asked again with that taken off, and the player's later steps are
 * asked that way straight off. A player that lands somewhere else again is asked no more and its
 * offset is forgotten. Right after a seek the player answers with the place it was asked for, which
 * says nothing, so that answer and the one it gave before the seek mean read again, a few times.
 *
 * <p>Facebook's player names no frame rate, so a frame is 1/30 second, the rate nearly every
 * Facebook video has. Off, paused, before the settings are ready, or when anything here throws, every
 * tap goes to Facebook as it was. The report counts the steps each way and the ones asked again, and
 * Debug logging writes the positions before and after, never which video.
 */
public final class FrameStep {
    /** One frame at 30 frames a second, in milliseconds. */
    static final int FRAME_MS = 33;

    /** The trigger a step seeks with: one Facebook's player seeks to the exact frame for, not a keyframe. */
    static final String STEP_TRIGGER = "BY_INITIAL_FRAME_PERFECT_SEEK";

    /** The report's line. */
    static final String ROUTE = "Frame step";

    // What the report counts, by kind. None of them says which video.
    static final String FORWARD = "a frame forward";
    static final String BACK = "a frame back";
    static final String ASKED_AGAIN = "a step asked again";

    /** What {@link #position} answers until the patch fills it in. */
    static final int NOT_PATCHED_TIME = Integer.MIN_VALUE;

    /** At most this many paused players are kept in mind; the oldest goes first. */
    static final int MAX_PAUSED = 8;

    /** How long after a seek the player is first asked where it is, in milliseconds. */
    static final int FIRST_CHECK_MS = 300;

    /** How long after an answer that says nothing it's asked again, in milliseconds. */
    static final int NEXT_CHECK_MS = 200;

    /** How many times a seek's landing is read before this class stops asking. */
    static final int CHECKS = 4;

    /** A landing further than this from the frame wanted, in milliseconds, is one to correct. */
    static final int OFF_BY = 120;

    private static final String SOURCE = "FrameStep";
    private static final String FAMILY = FamilyNames.FRAME_STEP;

    /** What this class reads from a player and does to it. {@link #PATCHED} is the patch's; tests stand in. */
    interface Player {
        /** The player's position in milliseconds, or {@link #NOT_PATCHED_TIME}. */
        int position(Object player);

        /** Whether the player is playing. True when the patch didn't fill it in. */
        boolean playing(Object player);

        /** The view the player draws in, or null when it has none or the patch didn't fill it in. */
        @Nullable
        View view(Object player);

        /** Seeks the player with [trigger] to [positionMs]. False when the patch didn't fill it in. */
        boolean seek(Object player, Object trigger, int positionMs);
    }

    static final Player PATCHED = new Player() {
        @Override
        public int position(Object player) {
            return FrameStep.position(player);
        }

        @Override
        public boolean playing(Object player) {
            return FrameStep.playing(player);
        }

        @Override
        public View view(Object player) {
            return playerView(player);
        }

        @Override
        public boolean seek(Object player, Object trigger, int positionMs) {
            return seekPlayer(player, trigger, positionMs);
        }
    };

    static volatile Player access = PATCHED;

    /** The paused players, oldest first. */
    private static final List<Paused> PAUSED = new ArrayList<>();
    private static final Map<Class<?>, Object> STEP_TRIGGERS = new HashMap<>();

    /** How far each player was seen landing from where it was asked, kept while the player lives. */
    private static final Map<Object, Integer> OFFSETS = new WeakHashMap<>();

    /** The thread a step is seeking on, while it is. */
    @Nullable
    private static volatile Thread stepping;

    // The touch going on now: where it went down and whether a second finger joined it.
    private static long downTime = -1;
    private static float downX;
    private static float downY;
    private static boolean manyFingers;

    private FrameStep() {
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: FbGrootPlayer's position reader. Only a player may be passed. */
    public static int position(Object player) {
        return NOT_PATCHED_TIME;
    }

    /** Filled in by the patch: FbGrootPlayer's isPlaying. Only a player may be passed. */
    public static boolean playing(Object player) {
        return true;
    }

    /** Filled in by the patch: the view FbGrootPlayer is attached to, or null. Only a player may be passed. */
    @Nullable
    public static ViewGroup playerView(Object player) {
        return null;
    }

    /**
     * Filled in by the patch: FbGrootPlayer's seek, with the trigger (an EventTriggerType constant)
     * and the position. True once it's called it.
     */
    public static boolean seekPlayer(Object player, Object trigger, int positionMs) {
        return false;
    }

    // ------------------------------------------------------------------ hooks

    /** The hook, first thing in FbGrootPlayer's maybeTrackVideoStart. A playing player isn't stepped. */
    public static void started(Object player, Object trigger) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on() || player == null) return;
            HookStatus.bound(FAMILY, "player start");
            forget(player);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player start", failure);
        }
    }

    /** The hook, first thing in FbGrootPlayer's maybeTrackVideoStop, which its pause and the video's end run. */
    public static void stopped(Object player, Object trigger) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on() || player == null || !(trigger instanceof Enum)) return;
            HookStatus.bound(FAMILY, "player stop");
            Class<?> type = ((Enum<?>) trigger).getDeclaringClass();
            synchronized (PAUSED) {
                removeLocked(player);
                PAUSED.add(new Paused(player, type));
                while (PAUSED.size() > MAX_PAUSED) PAUSED.remove(0);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player stop", failure);
        }
    }

    /** The hook, first thing in FbGrootPlayer's release. */
    public static void released(Object player, Object trigger) {
        try {
            HookStatus.invoked(FAMILY);
            if (player == null) return;
            forget(player);
            synchronized (OFFSETS) {
                OFFSETS.remove(player);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player release", failure);
        }
    }

    /**
     * The hook, first thing in FbGrootPlayer's seek. A paused player's next step goes from where
     * this seek asked for, your drag of the bar or the step before, since the player can take a
     * moment to say it's there.
     */
    public static void seeking(Object trigger, Object player, int positionMs) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on() || player == null) return;
            HookStatus.bound(FAMILY, "player seek");
            synchronized (PAUSED) {
                Paused paused = findLocked(player);
                if (paused != null) paused.sought = Math.max(0, positionMs);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player seek", failure);
        }
    }

    /**
     * The hook, first thing in FbGrootPlayer's check that drops a seek too close to where the video
     * is. True while a step is seeking on this thread, and the check answers no then. Never throws.
     */
    public static boolean stepping() {
        if (stepping != Thread.currentThread()) return false;
        try {
            HookStatus.bound(FAMILY, "small seek check");
        } catch (Throwable ignored) {
            // The answer stands without the report.
        }
        return true;
    }

    /**
     * The hook, first thing in the touch listener of the gesture view over a reel or video. A quick
     * tap on a side third of a paused video steps it a frame, and [event] becomes a cancel so
     * Facebook doesn't take the tap too. Every other touch is left as it is.
     */
    public static void touch(View view, MotionEvent event) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on() || view == null || event == null) return;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downTime = event.getDownTime();
                    downX = event.getRawX();
                    downY = event.getRawY();
                    manyFingers = false;
                    return;
                case MotionEvent.ACTION_POINTER_DOWN:
                    manyFingers = true;
                    return;
                case MotionEvent.ACTION_UP:
                    break;
                default:
                    return;
            }
            HookStatus.bound(FAMILY, "gesture view tap");
            int direction = direction(view, event);
            if (direction == 0) return;
            Paused paused = pausedUnder(event.getRawX(), event.getRawY());
            if (paused == null) return;
            if (step(paused, direction)) event.setAction(MotionEvent.ACTION_CANCEL);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "gesture view tap", failure);
        }
    }

    // ------------------------------------------------------------------ the rule

    private static boolean on() {
        return Utils.settingsReady() && Settings.FRAME_STEP.get();
    }

    /**
     * 1 for a quick one-finger tap on [view]'s right third, -1 for one on its left third, 0 for any
     * other touch: a hold, a drag, two fingers, the middle third, or an up from a touch whose down
     * this class didn't see.
     */
    static int direction(View view, MotionEvent event) {
        if (manyFingers || event.getPointerCount() != 1 || event.getDownTime() != downTime) return 0;
        if (event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout()) return 0;
        float slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        if (Math.hypot(event.getRawX() - downX, event.getRawY() - downY) > slop) return 0;
        int width = view.getWidth();
        if (width <= 0) return 0;
        float across = event.getX() / width;
        if (across < 1f / 3) return -1;
        if (across > 2f / 3) return 1;
        return 0;
    }

    /** The paused player drawing under the screen point, the one stopped last when there are several, or null. */
    @Nullable
    private static Paused pausedUnder(float rawX, float rawY) {
        List<Paused> candidates;
        synchronized (PAUSED) {
            candidates = new ArrayList<>(PAUSED);
        }
        Player player = access;
        int[] at = new int[2];
        for (int i = candidates.size() - 1; i >= 0; i--) {
            Paused paused = candidates.get(i);
            Object held = paused.player.get();
            if (held == null) continue;
            View drawn = player.view(held);
            if (drawn == null || !drawn.isShown()) continue;
            drawn.getLocationOnScreen(at);
            if (rawX < at[0] || rawX >= at[0] + drawn.getWidth() || rawY < at[1] || rawY >= at[1] + drawn.getHeight()) {
                continue;
            }
            if (player.playing(held)) continue;
            return paused;
        }
        return null;
    }

    /**
     * Seeks [paused] a frame forward (1) or back (-1). True once the seek was made, or when a step
     * back from the very start has nowhere to go: the tap is still taken, since Facebook would
     * play the video with it.
     */
    private static boolean step(Paused paused, int direction) {
        Object held = paused.player.get();
        if (held == null) return false;
        Player player = access;
        int before = player.position(held);
        int from = paused.sought >= 0 ? paused.sought : before;
        if (from == NOT_PATCHED_TIME || from < 0) return false;
        Object trigger = stepTrigger(paused.triggers);
        if (trigger == null) return false;
        int to = Math.max(0, from + direction * FRAME_MS);
        if (to == from) return true;
        int ask = Math.max(0, to - landsBy(held));
        if (!seek(player, held, trigger, ask)) return false;
        paused.sought = to;
        String kind = direction > 0 ? FORWARD : BACK;
        FeedFilterCounters.sawKind(ROUTE, kind);
        log(() -> "Frame step: " + kind + ", " + millis(from) + " to " + millis(to) + ", asked the player for "
                + millis(ask));
        check(new Landing(paused, trigger, to, ask, before, false), FIRST_CHECK_MS, CHECKS);
        return true;
    }

    /** Seeks with the small-seek check answering no. True once the seek was made. */
    private static boolean seek(Player player, Object held, Object trigger, int positionMs) {
        stepping = Thread.currentThread();
        try {
            return player.seek(held, trigger, positionMs);
        } finally {
            stepping = null;
        }
    }

    /** How far [held] was seen landing from where it was asked, 0 until it's been seen. */
    private static int landsBy(Object held) {
        synchronized (OFFSETS) {
            Integer lands = OFFSETS.get(held);
            return lands == null ? 0 : lands;
        }
    }

    /** One seek of a step and what's known about it, for reading where it landed. */
    private static final class Landing {
        final Paused paused;
        final Object trigger;
        /** The frame wanted. */
        final int to;
        /** What the player was asked for. */
        final int ask;
        /** What the player read before this seek. */
        final int before;
        /** Whether this seek is the step asked again. */
        final boolean again;

        Landing(Paused paused, Object trigger, int to, int ask, int before, boolean again) {
            this.paused = paused;
            this.trigger = trigger;
            this.to = to;
            this.ask = ask;
            this.before = before;
            this.again = again;
        }
    }

    /** Reads where [landing]'s player is after [delayMs], [left] times at most. */
    private static void check(Landing landing, int delayMs, int left) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                landed(landing, left - 1);
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "step landing", failure);
            }
        }, delayMs);
    }

    /** Leaves a step that landed on its frame, and asks again for one that landed off. */
    private static void landed(Landing landing, int left) {
        Paused paused = landing.paused;
        Object held = paused.player.get();
        if (held == null || !on()) return;
        synchronized (PAUSED) {
            // Started, released or stopped again since, or seeked by Facebook, your drag or a newer step.
            if (!PAUSED.contains(paused) || paused.sought != landing.to) return;
        }
        Player player = access;
        if (player.playing(held)) return;
        int read = player.position(held);
        if (read == NOT_PATCHED_TIME) return;
        if (read == landing.ask || read == landing.before) {
            // The place it was asked for, or the one from before the seek: it hasn't said yet.
            if (left > 0) check(landing, NEXT_CHECK_MS, left);
            else log(() -> "Frame step: asked for " + millis(landing.ask) + ", the player kept reading " + millis(read));
            return;
        }
        if (Math.abs(read - landing.to) <= OFF_BY) {
            log(() -> "Frame step: wanted " + millis(landing.to) + ", the player reads " + millis(read));
            return;
        }
        if (landing.again) {
            synchronized (OFFSETS) {
                OFFSETS.remove(held);
            }
            log(() -> "Frame step: wanted " + millis(landing.to) + ", asked again and the player reads "
                    + millis(read) + ", so it's asked no more");
            return;
        }
        int lands = read - landing.ask;
        synchronized (OFFSETS) {
            OFFSETS.put(held, lands);
        }
        int ask = Math.max(0, landing.to - lands);
        if (!seek(player, held, landing.trigger, ask)) return;
        paused.sought = landing.to;
        FeedFilterCounters.sawKind(ROUTE, ASKED_AGAIN);
        log(() -> "Frame step: wanted " + millis(landing.to) + ", the player reads " + millis(read)
                + ", so it's asked for " + millis(ask));
        check(new Landing(paused, landing.trigger, landing.to, ask, read, true), FIRST_CHECK_MS, CHECKS);
    }

    /** The constant of the stop's own trigger enum a step seeks with, or null when there's none. */
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object stepTrigger(Class<?> type) {
        synchronized (STEP_TRIGGERS) {
            if (STEP_TRIGGERS.containsKey(type)) return STEP_TRIGGERS.get(type);
            Object constant;
            try {
                constant = Enum.valueOf((Class) type, STEP_TRIGGER);
            } catch (IllegalArgumentException missing) {
                constant = null;
                HookStatus.missingMember(FAMILY, "enum constant", type.getName(), STEP_TRIGGER);
            }
            STEP_TRIGGERS.put(type, constant);
            return constant;
        }
    }

    // ------------------------------------------------------------------ the players

    /** A player whose last word was a stop, and the place its last seek since asked for. */
    private static final class Paused {
        final WeakReference<Object> player;
        final Class<?> triggers;
        int sought = -1;

        Paused(Object player, Class<?> triggers) {
            this.player = new WeakReference<>(player);
            this.triggers = triggers;
        }
    }

    private static void forget(Object player) {
        synchronized (PAUSED) {
            removeLocked(player);
        }
    }

    /** Takes out [player] and every player that's gone. */
    private static void removeLocked(Object player) {
        Iterator<Paused> each = PAUSED.iterator();
        while (each.hasNext()) {
            Object held = each.next().player.get();
            if (held == null || held == player) each.remove();
        }
    }

    @Nullable
    private static Paused findLocked(Object player) {
        for (Paused paused : PAUSED) {
            if (paused.player.get() == player) return paused;
        }
        return null;
    }

    // ------------------------------------------------------------------ the report

    private static void log(Logger.LogMessage message) {
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, message);
    }

    /** A position as seconds and milliseconds. */
    static String millis(int ms) {
        if (ms == NOT_PATCHED_TIME) return "nothing";
        return String.format(Locale.ROOT, "%d.%03d s", ms / 1000, Math.abs(ms % 1000));
    }

    // ------------------------------------------------------------------ for tests

    /** Forgets every paused player, cached trigger and touch, and puts the app's player back. */
    static void forgetAll() {
        synchronized (PAUSED) {
            PAUSED.clear();
        }
        synchronized (STEP_TRIGGERS) {
            STEP_TRIGGERS.clear();
        }
        synchronized (OFFSETS) {
            OFFSETS.clear();
        }
        downTime = -1;
        manyFingers = false;
        stepping = null;
        access = PATCHED;
    }

    static int pausedKnown() {
        synchronized (PAUSED) {
            return PAUSED.size();
        }
    }
}
