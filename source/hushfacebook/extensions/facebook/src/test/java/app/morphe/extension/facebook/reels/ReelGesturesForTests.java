/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.view.View;

import org.robolectric.RuntimeEnvironment;

import app.morphe.extension.facebook.settings.Settings;

/** What tests outside this package need of Open comments with a gesture: a reel with a Comment button, and a gesture on it. */
public final class ReelGesturesForTests {
    private ReelGesturesForTests() { }

    /** Facebook's long-press handler as the extension reads it: a lambda keeping the reel it captured. */
    @SuppressWarnings({"unused", "checkstyle:MemberName"})
    private static final class Handler {
        final Object $shortFormVideoUnit;

        Handler(Object reel) {
            this.$shortFormVideoUnit = reel;
        }
    }

    /** The gesture a long press hands over: holds the view it landed on. */
    @SuppressWarnings("unused")
    private static final class Holder {
        final View view;

        Holder(View view) {
            this.view = view;
        }
    }

    /** Whether a double tap on a reel that has a Comment button presses it. */
    public static boolean doubleTapOpens() {
        // Turn off double tap to like wins over this switch, and the probes switch everything on.
        boolean off = Settings.TURN_OFF_DOUBLE_TAP_LIKE.savedValue();
        Settings.TURN_OFF_DOUBLE_TAP_LIKE.save(false);
        try {
            return withButton((reel, view) -> ReelGestures.doubleTap(reel));
        } finally {
            Settings.TURN_OFF_DOUBLE_TAP_LIKE.save(off);
        }
    }

    /** Whether a long press on a reel that has a Comment button presses it. */
    public static boolean longPressOpens() {
        return withButton((reel, view) -> ReelGestures.longPress(new Handler(reel), new Holder(view)));
    }

    private interface Gesture {
        boolean run(Object reel, View view);
    }

    private static boolean withButton(Gesture gesture) {
        ReelGestures.Opener saved = ReelGestures.opener;
        boolean[] pressed = {false};
        ReelGestures.reset();
        try {
            // The opener holds the button, which ReelGestures only keeps weakly, until the gesture ends.
            Object button = new Object();
            ReelGestures.opener = (view, pressedButton) -> {
                pressed[0] = pressedButton == button;
                return true;
            };
            Object reel = new Object();
            View view = new View(RuntimeEnvironment.getApplication());
            ReelGestures.remember(button, reel);
            ReelGestures.noteView(view);
            gesture.run(reel, view);
            return pressed[0];
        } finally {
            ReelGestures.opener = saved;
            ReelGestures.reset();
        }
    }
}
