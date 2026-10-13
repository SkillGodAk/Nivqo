/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for FbGrootPlayer, its trigger enum and the views on screen, the way the patch's
 * filled-in stubs would reach them, and taps the gesture view the way Android hands it touches.
 */
public final class FrameStepForTests {
    /** Stands in for Facebook's EventTriggerType: only the constant names matter. */
    public enum Trigger { BY_USER, BY_PLAYER, BY_AUTOPLAY, BY_SEEKBAR_CONTROLLER, BY_INITIAL_FRAME_PERFECT_SEEK }

    /** A trigger enum without the constant a step seeks with. */
    public enum TriggerWithoutStep { BY_USER, BY_PLAYER, BY_AUTOPLAY }

    /** A view at a fixed place on screen, shown, of a fixed size. */
    public static final class OnScreen extends FrameLayout {
        private final int left;
        private final int top;

        public OnScreen(int left, int top, int width, int height) {
            super(context());
            this.left = left;
            this.top = top;
            layout(0, 0, width, height);
        }

        @Override
        public boolean isShown() {
            return true;
        }

        @Override
        public void getLocationOnScreen(int[] location) {
            location[0] = left;
            location[1] = top;
        }
    }

    /** Stands in for FbGrootPlayer. */
    public static final class Player {
        public int position;
        public boolean playing;
        public View view;
        /** Every seek made on it, as "TRIGGER@ms". */
        public final List<String> seeks = new ArrayList<>();
        /** What the small-seek check heard inside each seek. */
        public final List<Boolean> steppingInSeek = new ArrayList<>();
        /** Once set, a seek lands this far from where it asked, as a 582 reel's lands two seconds early. */
        public Integer landsOff;
        /** Once set, every seek lands here, as one snapping to a keyframe would. */
        public Integer landsAt;
        /** How many reads after a seek answer with the place asked for, as Facebook's player does for a moment. */
        public int echoes;
        private int asked;
        private int echoesLeft;

        public Player(View view, int position) {
            this.view = view;
            this.position = position;
        }
    }

    /**
     * What the patched stubs do, on {@link Player}s. Unless a test says where seeks land, the
     * position stays where it was, as a real player's does for a moment.
     */
    static final FrameStep.Player ACCESS = new FrameStep.Player() {
        @Override
        public int position(Object player) {
            Player reading = (Player) player;
            if (reading.echoesLeft > 0) {
                reading.echoesLeft--;
                return reading.asked;
            }
            return reading.position;
        }

        @Override
        public boolean playing(Object player) {
            return ((Player) player).playing;
        }

        @Override
        public View view(Object player) {
            return ((Player) player).view;
        }

        @Override
        public boolean seek(Object player, Object trigger, int positionMs) {
            Player seeking = (Player) player;
            // Facebook's seek tells the seek hook first, then asks its small-seek check.
            FrameStep.seeking(trigger, player, positionMs);
            seeking.steppingInSeek.add(FrameStep.stepping());
            seeking.seeks.add(((Enum<?>) trigger).name() + "@" + positionMs);
            seeking.asked = positionMs;
            seeking.echoesLeft = seeking.echoes;
            if (seeking.landsAt != null) seeking.position = seeking.landsAt;
            else if (seeking.landsOff != null) seeking.position = Math.max(0, positionMs + seeking.landsOff);
            return true;
        }
    };

    private FrameStepForTests() {
    }

    static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    /** Stands in for the patched stubs, and forgets every player and touch. */
    public static void install() {
        FrameStep.forgetAll();
        FrameStep.access = ACCESS;
    }

    /** Puts the app's own player back. */
    public static void forget() {
        FrameStep.forgetAll();
    }

    /**
     * A one-finger touch on [view] at ([x], [y]), which is where it is on screen too, held for
     * [heldMs] and lifted [movedBy] further right. Answers the up as Facebook's detector gets it.
     */
    public static int touch(View view, float x, float y, long heldMs, float movedBy) {
        long down = SystemClock.uptimeMillis();
        MotionEvent first = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        FrameStep.touch(view, first);
        MotionEvent up = MotionEvent.obtain(down, down + heldMs, MotionEvent.ACTION_UP, x + movedBy, y, 0);
        FrameStep.touch(view, up);
        int action = up.getActionMasked();
        first.recycle();
        up.recycle();
        return action;
    }

    /** A quick tap on [view] at ([x], [y]). */
    public static int tap(View view, float x, float y) {
        return touch(view, x, y, 50, 0);
    }

    /**
     * For the pause probe: a video paused by you, then a tap on its right third. True when that
     * tap steps it, which a paused Hushfacebook must never do.
     */
    public static boolean stepsAPausedVideo() {
        install();
        OnScreen screen = new OnScreen(0, 0, 900, 1600);
        Player player = new Player(screen, 10_000);
        FrameStep.stopped(player, Trigger.BY_USER);
        tap(screen, 800, 800);
        boolean stepped = !player.seeks.isEmpty();
        forget();
        return stepped;
    }
}
