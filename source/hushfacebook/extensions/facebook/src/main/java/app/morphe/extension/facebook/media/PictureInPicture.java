/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.app.Activity;
import android.app.Application;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Picture-in-picture for reels, through the picture-in-picture Facebook already ships for its
 * Reels viewer (ReelsPipUtil) behind server flags. The viewer first asks a gate of its own whether
 * its surface has picture-in-picture, then ReelsPipUtil whether the activity may use it: Android 12
 * or later, its flags or a preference of its own, the phone's picture-in-picture feature and a
 * memory check. With the switch on, the gate says yes on Android 12 or later, and the check says
 * yes there whenever the phone has the feature. Facebook's own code does the rest: it sets the
 * window's aspect ratio and turns on Android's auto-enter, so a playing reel shrinks into a window
 * when you leave Facebook.
 *
 * <p>Facebook arms the window once as the Reels viewer opens, for that one player: it keeps that
 * player going in the window and turns auto-enter off and on as it pauses and plays. A swipe starts
 * the next reel's player without arming again, so the window would open on a frozen reel, or not at
 * all once the first one's pause turned auto-enter off. So when another reel starts, the extension
 * arms the window again with Facebook's own arguments for that reel's player, in that reel's own
 * shape, worked out the way Facebook works it out when it arms. A reel of a shape Facebook arms no
 * window for, a landscape one, isn't armed again: it opens no window, as with Facebook's own arming,
 * where before it opened in the last reel's tall shape around the wide video (#126). And while the
 * window is armed, it sets Facebook's change of auto-enter aside and decides from the reel on screen:
 * while it's paused, or of a shape the window doesn't take, auto-enter is off, set the way
 * Facebook's own disarm sets it, and when a reel plays, it's back on.
 *
 * <p>In the window, Facebook's viewer opens in a container with a new view id, which a React
 * Native view on the main screen can already hold. The extension swaps such an id for a free one,
 * or the viewer opens hidden and the window stays black.
 *
 * <p>Facebook's full-screen Watch viewer arms the same window for the video on screen, behind a
 * server flag of its own. With the switch on, the extension answers that flag yes, and Facebook's
 * own code arms the window; the rest here treats that arming like any other.
 *
 * <p>The Video tab arms the same window as you scroll its videos, behind two answers of its own:
 * the gate the Reels viewer asks, which this copy reads inline, and a server flag for its deep-dive
 * viewer. With the switch on, both say yes, and Facebook's own code arms the window for the video on
 * screen. Its checks for ads, live videos and the video's shape stay Facebook's, so those still open
 * no window.
 *
 * <p>When the Reels tab's window floats, it holds Facebook's main screen and the task every Facebook
 * activity joins, so a link, a notification or a settings shortcut opened meanwhile drew its page in
 * the window and left it there. A page opening in that task while the window floats brings Facebook
 * back full screen ({@link #arrived}).
 *
 * <p>While the window is armed, a reel's More sheet opened on the armed screen gets a
 * Picture-in-picture row ({@link PictureInPictureRow}), whose tap opens the window right away with
 * the shape Facebook armed it with (#44). With Only from a reel's More menu on as well, leaving
 * Facebook opens no window: auto-enter goes off once Facebook has armed, Facebook's own changes of
 * it are set aside, and nothing here turns it back on, so the row is the only way in.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the answer is
 * Facebook's own.
 */
public final class PictureInPicture {
    /** Counted under the patch's name each time the check says yes where Facebook would decide. */
    static final String ALLOWED = "picture-in-picture allowed";

    /** Counted each time the Reels viewer's gate says yes where Facebook would decide. */
    static final String SURFACE = "reels viewer allowed";

    /** Counted each time a paused reel turns auto-enter off. */
    static final String HELD = "paused reel held";

    /** Counted each time a start Tap to play holds turns auto-enter off. */
    static final String START_HELD = "held start kept out of the window";

    /** Counted each time Facebook's own change of auto-enter is set aside. */
    static final String SET_ASIDE = "facebook change set aside";

    /** Counted each time the window is armed again for the reel a swipe started. */
    static final String REARMED = "armed again for the next reel";

    /** Counted each time a reel of a shape Facebook arms no window for turns auto-enter off. */
    static final String KEPT_OUT = "wide reel kept out of the window";

    /** Counted each time a player whose video is off screen plays and the window stays with the reel on screen. */
    static final String OFF_SCREEN = "off-screen player left out of the window";

    /** Counted each time the window is armed again because Facebook didn't after it was opened full screen. */
    static final String REARMED_AFTER_WINDOW = "armed again after the window closed";

    /** How long after its screen pauses the page watch looks whether it went into the window. */
    static final long FLOAT_CHECK_MS = 800;

    /** How long Facebook gets to arm the window itself once its screen comes back full screen. */
    static final long REARM_AFTER_WINDOW_MS = 1000;

    /**
     * The narrowest and widest window Facebook arms for a reel, width to height, as its arming
     * checks it: 1 by 2.39, Android's own narrowest, up to square.
     */
    static final float NARROWEST = 1f / 2.39f;
    static final float WIDEST = 1f;

    /** Counted each time the window's viewer gets a view id of its own instead of one a view already holds. */
    static final String VIEWER_ID = "viewer id replaced";

    /** Counted each time the full-screen Watch viewer's flag says yes where Facebook's answer was no. */
    static final String IMMERSIVE = "watch viewer flag opened";

    /** Counted each time the Video tab's copy of the Reels viewer's gate says yes where Facebook's answer was no. */
    static final String HOME_GATE = "video tab gate opened";

    /** Counted each time the Video tab's flag for its deep-dive viewer says yes where Facebook's answer was no. */
    static final String HOME_FLAG = "video tab flag opened";

    /** Counted each time a page opened in the floating window's task brought Facebook back full screen. */
    static final String BROUGHT_BACK = "page opened in the window brought back full screen";

    /** Counted each time auto-enter goes off after an arming because the window opens only from the More menu. */
    static final String MENU_ONLY = "auto-enter kept off for the More menu";

    /** Counted each time the More sheet's Picture-in-picture row opened the window. */
    static final String FROM_MENU = "opened from the More menu";

    /** How many new view ids the viewer may go through for one nothing on screen holds. */
    private static final int VIEWER_ID_TRIES = 64;

    /**
     * How long after bringing Facebook back another page in the window waits before it may do so
     * again, so a start that didn't bring the window back can't start Facebook over and over.
     */
    static final long BRING_BACK_GAP_MS = 3000;

    private static final String FAMILY = FamilyNames.PICTURE_IN_PICTURE;

    /** The Android version the hooks go by. Tests stand in a later one. */
    static volatile int apiLevel = Build.VERSION.SDK_INT;

    /**
     * The screen ReelsPipUtil last armed the window on, the player that last started while it's
     * armed, and whether a pause holds auto-enter.
     */
    private static WeakReference<Activity> armedScreen = new WeakReference<>(null);
    private static WeakReference<Object> current = new WeakReference<>(null);
    private static boolean held;

    /** Whether the reel on screen has a shape the window doesn't take, so auto-enter stays off for it. */
    private static boolean unfit;

    /**
     * The arguments ReelsPipUtil last armed the window with, kept until it disarms, as Facebook
     * keeps its own: a swipe arms again with them for the next reel's player.
     */
    private static Object[] arming;

    /** When a page in the window last brought Facebook back, in uptime milliseconds. */
    private static long broughtBackAt = -BRING_BACK_GAP_MS;

    /** The application the page watch went on, so arming again adds no second one. */
    private static WeakReference<Application> watching = new WeakReference<>(null);

    /** The armed screen while the page watch saw it in the window, until it comes back full screen. */
    private static WeakReference<Activity> floating = new WeakReference<>(null);

    /** Goes up with each arming and disarm, so a later look can tell whether Facebook did either. */
    private static int armings;

    /** Reads the view a player's video is attached to. Tests stand in their own. */
    interface Views {
        @Nullable
        View of(Object player);
    }

    static volatile Views views = PictureInPicture::playerView;

    private PictureInPicture() {
    }

    /** Filled in by the patch: the view FbGrootPlayer's video is attached to, or null. Only a player may be passed. */
    @Nullable
    public static ViewGroup playerView(Object player) {
        return null;
    }

    /**
     * Whether [player]'s video is attached to a view that isn't on screen: not shown, or less than
     * half of it visible. Facebook starts the reel next to the one on screen for a moment and pauses
     * it again as it gets it ready, and that reel's view sits beside the screen. A player with no
     * view known, or a view that can't be read, counts as on screen, as before (#126).
     */
    static boolean offScreen(Object player) {
        try {
            View view = views.of(player);
            if (view == null) return false;
            int width = view.getWidth();
            int height = view.getHeight();
            if (!view.isAttachedToWindow() || !view.isShown() || width <= 0 || height <= 0) return true;
            Rect visible = new Rect();
            if (!view.getGlobalVisibleRect(visible)) return true;
            return (long) visible.width() * visible.height() * 2 < (long) width * height;
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * The hook, first thing in ReelsPipUtil's check of whether [activity] may use
     * picture-in-picture. True answers yes; false leaves the answer to Facebook's own check.
     */
    public static boolean allowed(Activity activity) {
        try {
            if (activity == null || !on()) return false;
            // Entering throws on a phone without the feature, which Facebook's check reads too.
            if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                return false;
            }
            HookStatus.bound(FAMILY, "picture-in-picture check");
            HookStatus.counted(FAMILY, ALLOWED);
            Logger.printDebug(() -> "Picture-in-picture: allowed for " + activity.getClass().getSimpleName());
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "picture-in-picture check", failure);
            return false;
        }
    }

    /**
     * The hook, first thing in the Reels viewer's gate of whether its surface has
     * picture-in-picture. True answers yes; false leaves the answer to Facebook's own gate.
     */
    public static boolean surfaceAllowed() {
        try {
            if (!on()) return false;
            HookStatus.bound(FAMILY, "reels viewer gate");
            HookStatus.counted(FAMILY, SURFACE);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reels viewer gate", failure);
            return false;
        }
    }

    /**
     * The hook, first thing in ReelsPipUtil's arming of the window for [player] on [activity], with
     * all of the arming's [arguments].
     */
    public static void armed(Activity activity, Object player, Object[] arguments) {
        try {
            arm(activity, player, arguments);
            if (activity != null) watch(activity.getApplication());
            // Facebook turns auto-enter on right after this hook, so it goes off again once the arming is done.
            if (activity != null && menuOnly()) Utils.runOnMainThread(() -> keepForMenu(activity));
            Logger.printDebug(() -> "Picture-in-picture: armed for player " + System.identityHashCode(player));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "arming", failure);
        }
    }

    /**
     * Turns auto-enter off on [screen] after Facebook armed it, while the window opens only from the
     * More menu, unless it was disarmed or armed on another screen since, or the window is open.
     */
    static void keepForMenu(Activity screen) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
            synchronized (PictureInPicture.class) {
                if (armedScreen.get() != screen) return;
            }
            if (screen.isInPictureInPictureMode() || !menuOnly()) return;
            autoEnter(screen, false);
            HookStatus.bound(FAMILY, "More menu only");
            HookStatus.counted(FAMILY, MENU_ONLY);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "More menu only", failure);
        }
    }

    /**
     * The screen a reel's More sheet built in [context] may open the window on: the armed screen,
     * when the sheet is on it, with the switch on, the phone's feature, and no window open yet.
     * Null otherwise, so a sheet on another screen, such as a reel opened over the armed one, gets
     * no row that would float the wrong screen, and a reel the window doesn't take gets none either.
     */
    static Activity menuScreen(@Nullable Context context) {
        Activity screen;
        synchronized (PictureInPicture.class) {
            screen = unfit ? null : armedScreen.get();
        }
        if (screen == null || !on() || activityOf(context) != screen) return null;
        if (screen.isFinishing() || screen.isDestroyed() || screen.isInPictureInPictureMode()) return null;
        if (!screen.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return null;
        return screen;
    }

    /** The activity [context] is, or wraps, or null. */
    @Nullable
    static Activity activityOf(@Nullable Context context) {
        for (int depth = 0; context != null && depth < 16; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /**
     * The More sheet row's tap: opens the window on [screen] now, if it's still the armed screen and
     * may. Params with nothing set keep the shape Facebook armed it with. True when it opened.
     */
    static boolean enterFromMenu(@Nullable Activity screen) {
        try {
            if (screen == null || menuScreen(screen) == null) return false;
            boolean entered = screen.enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
            HookStatus.bound(FAMILY, "More menu row");
            if (entered) HookStatus.counted(FAMILY, FROM_MENU);
            Logger.printDebug(() -> "Picture-in-picture: the More menu row " + (entered ? "opened" : "didn't open") + " the window");
            return entered;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "More menu row", failure);
            return false;
        }
    }

    /**
     * Puts the page watch on [application] once. The first arming comes long after Facebook's
     * application set itself up, which its override of the registration needs.
     */
    private static synchronized void watch(Application application) {
        if (application == null || watching.get() == application) return;
        application.registerActivityLifecycleCallbacks(new Arrivals());
        watching = new WeakReference<>(application);
    }

    /** Hands each Facebook activity as it's created to {@link #arrived}. */
    static final class Arrivals implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, @Nullable Bundle state) {
            arrived(activity);
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
            cameBack(activity);
        }

        @Override
        public void onActivityPaused(Activity activity) {
            if (activity != null && activity == armedScreen()) Utils.runOnMainThreadDelayed(() -> floats(activity), FLOAT_CHECK_MS);
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }

    /**
     * [activity] was just created. While the window floats with the armed screen in it, that screen's
     * task is the one Facebook's activities join, so a page created in it opened inside the window.
     * Android brings a floating task back full screen only for a start it counts as starting the app
     * again, which a new page in the task isn't and the launcher's own start is (the icon tap that
     * already brought it back on the S22). So Facebook is started that way a moment later, once the
     * page is up, and the window comes back full screen with the page on top. Facebook's own answer,
     * closing the window's screen when another activity resumes, is for its separate Reels viewer:
     * nothing in a floating task resumes, and here the screen is Facebook's main one.
     */
    static void arrived(Activity activity) {
        try {
            if (activity == null || !bringingBack(activity, SystemClock.uptimeMillis())) return;
            Intent launch = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
            if (launch == null) return;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            String page = activity.getClass().getSimpleName();
            Utils.runOnMainThread(() -> bringBack(activity, launch, page));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "page in the window", failure);
        }
    }

    /**
     * Whether [activity] opened in the armed screen's task while that screen floats in the window,
     * with the switch on and Facebook not brought back in the last {@link #BRING_BACK_GAP_MS}, as of
     * [now]. Answering yes starts that gap.
     */
    static synchronized boolean bringingBack(Activity activity, long now) {
        Activity screen = armedScreen.get();
        if (screen == null || screen == activity) return false;
        if (!screen.isInPictureInPictureMode() || screen.getTaskId() != activity.getTaskId() || !on()) return false;
        if (now - broughtBackAt < BRING_BACK_GAP_MS) return false;
        broughtBackAt = now;
        return true;
    }

    private static synchronized Activity armedScreen() {
        return armedScreen.get();
    }

    /** [activity] paused a moment ago. If it's the armed screen and it floats in the window now, that's noted. */
    static synchronized void floats(Activity activity) {
        try {
            if (activity == armedScreen.get() && activity.isInPictureInPictureMode()) floating = new WeakReference<>(activity);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "window watch", failure);
        }
    }

    /**
     * [activity] resumed. If it's the screen that floated in the window, it was just opened full
     * screen again, and Facebook gets {@link #REARM_AFTER_WINDOW_MS} to arm the window for it.
     */
    static void cameBack(Activity activity) {
        int seen;
        synchronized (PictureInPicture.class) {
            if (activity == null || activity != floating.get()) return;
            floating = new WeakReference<>(null);
            seen = armings;
        }
        Utils.runOnMainThreadDelayed(() -> armAfterWindow(activity, seen), REARM_AFTER_WINDOW_MS);
    }

    /**
     * Facebook mostly arms the window again once it's opened full screen, but not every time, and
     * then its last arming stays on: leaving Facebook opened the window on its whole screen, with a
     * purple block where the reel should be (#126). Unless Facebook armed or disarmed since [seen],
     * the window is armed again for the reel on screen, as a swipe arms it, or kept closed for a
     * reel it doesn't take. A pause holding auto-enter still holds it.
     */
    static void armAfterWindow(Activity activity, int seen) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
            Object[] arguments;
            Object player;
            boolean wasHeld;
            synchronized (PictureInPicture.class) {
                if (armings != seen || activity != armedScreen.get() || !on()) return;
                arguments = arming;
                player = current.get();
                wasHeld = held;
            }
            if (arguments == null || player == null || activity.isFinishing() || activity.isDestroyed()
                    || activity.isInPictureInPictureMode()) return;
            if (!rearm(arguments, player)) {
                keepOut(activity, player);
                return;
            }
            if (wasHeld && holding(player) != null) autoEnter(activity, false);
            HookStatus.bound(FAMILY, "arming after the window");
            HookStatus.counted(FAMILY, REARMED_AFTER_WINDOW);
            Logger.printDebug(() -> "Picture-in-picture: armed again after the window closed, for player " + System.identityHashCode(player));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "arming after the window", failure);
        }
    }

    /** Starts Facebook from [activity] with its own launcher [launch], counting it under [page]. */
    private static void bringBack(Activity activity, Intent launch, String page) {
        try {
            activity.startActivity(launch);
            HookStatus.bound(FAMILY, "page in the window");
            HookStatus.counted(FAMILY, BROUGHT_BACK);
            Logger.printDebug(() -> "Picture-in-picture: " + page + " opened in the window, so Facebook was brought back full screen");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "page in the window", failure);
        }
    }

    /** The hook, first thing in each of ReelsPipUtil's disarms. Nothing is armed after it. */
    public static void disarmed() {
        try {
            arm(null, null, null);
            Logger.printDebug(() -> "Picture-in-picture: disarmed");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "disarm", failure);
        }
    }

    /** Facebook just set auto-enter itself, on or off, so nothing is held any more. */
    private static synchronized void arm(Activity activity, Object player, Object[] arguments) {
        armedScreen = new WeakReference<>(activity);
        current = new WeakReference<>(player);
        arming = arguments;
        held = false;
        unfit = false;
        armings++;
    }

    /**
     * The hook, first thing in FbGrootPlayer's pause. The reel on screen pausing turns auto-enter
     * off, unless the window is already open.
     */
    public static void playerPaused(Object player) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || player == null) return;
            Activity screen = holding(player);
            if (screen == null) return;
            autoEnter(screen, false);
            HookStatus.counted(FAMILY, HELD);
            Logger.printDebug(() -> "Picture-in-picture: player " + System.identityHashCode(player) + " paused, held");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player pause", failure);
        }
    }

    /**
     * Tap to play, as it holds the start of [player], a video nobody chose to play. Facebook arms
     * the window for each reel as it starts, auto-enter on, a moment before that start is held, and
     * nothing pauses a start that never went ahead. So leaving Facebook opened the window and played
     * the held reel there, and back in Facebook it showed time gone that nobody watched (#90). While
     * the window is armed and closed, auto-enter goes off as it does for a paused reel, and the tap
     * that plays a reel turns it back on ({@link #playerPlaying}).
     */
    public static void startHeld(Object player) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || player == null) return;
            Activity screen = holdingStart();
            if (screen == null) return;
            autoEnter(screen, false);
            HookStatus.counted(FAMILY, START_HELD);
            Logger.printDebug(() -> "Picture-in-picture: player " + System.identityHashCode(player) + " held by Tap to play, held");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "held start", failure);
        }
    }

    /**
     * The armed screen when a held start should hold auto-enter, marked held: whichever player the
     * window was armed for, since the reel on screen is the held one and nothing plays.
     */
    private static synchronized Activity holdingStart() {
        Activity screen = armedScreen.get();
        if (screen == null || screen.isInPictureInPictureMode() || !on()) return null;
        held = true;
        return screen;
    }

    /**
     * The hook, in FbGrootPlayer's play once it goes ahead. While the window is armed, [player] is
     * the reel on screen from now on: a held auto-enter goes back on, switch or no switch, since it
     * was the extension that turned it off, and a reel other than the armed one gets the window
     * armed again for it. Unless that other player's video is off screen: Facebook getting the next
     * reel ready plays it for a moment, and the window then opened on that reel, or on a purple
     * block where its video wasn't drawn (#126).
     */
    public static void playerPlaying(Object player) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || player == null) return;
            if (anotherReel(player) && offScreen(player)) {
                HookStatus.counted(FAMILY, OFF_SCREEN);
                Logger.printDebug(() -> "Picture-in-picture: player " + System.identityHashCode(player) + " plays off screen, left out");
                return;
            }
            Object[] next = switching(player);
            Activity screen = releasing();
            if (screen != null) {
                autoEnter(screen, true);
                Logger.printDebug(() -> "Picture-in-picture: player " + System.identityHashCode(player) + " playing, released");
            }
            if (next != null) Utils.runOnMainThread(() -> armAgain(next, player));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "player play", failure);
        }
    }

    /** Whether the window is armed and [player] isn't the reel on screen. */
    private static synchronized boolean anotherReel(Object player) {
        return armedScreen.get() != null && player != current.get();
    }

    /**
     * The arguments to arm the window again with, when [player] isn't the reel on screen and the
     * switch is on. Either way, while the window is armed, [player] is the reel on screen now.
     */
    private static synchronized Object[] switching(Object player) {
        if (armedScreen.get() == null || player == current.get()) return null;
        current = new WeakReference<>(player);
        return arming != null && on() ? arming : null;
    }

    /** The armed screen when a pause holds auto-enter, released, unless the reel on screen is one the window doesn't take. */
    private static synchronized Activity releasing() {
        if (!held) return null;
        held = false;
        return unfit ? null : armedScreen.get();
    }

    /**
     * Arms the window again with [arguments] for [player], on the main thread where Facebook arms
     * it, unless it was disarmed or another reel started since.
     */
    private static void armAgain(Object[] arguments, Object player) {
        try {
            synchronized (PictureInPicture.class) {
                if (arguments != arming || player != current.get()) return;
            }
            Activity screen = armedScreen.get();
            if (screen == null || screen.isFinishing() || screen.isDestroyed()) return;
            if (!rearm(arguments, player)) {
                keepOut(screen, player);
                return;
            }
            HookStatus.bound(FAMILY, "arming again");
            HookStatus.counted(FAMILY, REARMED);
            Logger.printDebug(() -> "Picture-in-picture: armed again for player " + System.identityHashCode(player));
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "arming again", failure);
        }
    }

    /**
     * Filled in by the patch: arms the window again through ReelsPipUtil with the [arguments] of its
     * last arming, for [player], that player's own video and the shape {@link #shape} gives for it.
     * False when that reel has a shape the window doesn't take, and until the patch fills it in.
     */
    public static boolean rearm(Object[] arguments, Object player) {
        return false;
    }

    /**
     * The window's shape for a reel whose video is [aspect] wide to one high, worked out as Facebook
     * works it out when it arms the window for a reel: in thousandths, from {@link #NARROWEST} up to
     * {@link #WIDEST}, and 9 by 16 for a video of no known shape. Null for a shape Facebook arms no
     * window for, such as a landscape reel's.
     */
    @Nullable
    public static Rational shape(double aspect) {
        try {
            if (!(aspect > 0)) return new Rational(9, 16);
            int thousandths = (int) ((float) aspect * 1000f);
            float ratio = thousandths / 1000f;
            Logger.printDebug(() -> "Picture-in-picture: the next reel is " + aspect + " wide to one high");
            if (thousandths <= 0 || ratio < NARROWEST || ratio > WIDEST) return null;
            return new Rational(thousandths, 1000);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel shape", failure);
            return null;
        }
    }

    /**
     * [player], the reel on screen, has a shape the window doesn't take, so it isn't armed again:
     * auto-enter goes off, leaving it out of the window as Facebook's own arming does, until a reel
     * the window takes plays and is armed. Nothing changes once another reel started, or while the
     * window is open.
     */
    private static void keepOut(Activity screen, Object player) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        synchronized (PictureInPicture.class) {
            if (player != current.get()) return;
            unfit = true;
            held = false;
        }
        if (screen.isInPictureInPictureMode()) return;
        autoEnter(screen, false);
        HookStatus.bound(FAMILY, "arming again");
        HookStatus.counted(FAMILY, KEPT_OUT);
        Logger.printDebug(() -> "Picture-in-picture: player " + System.identityHashCode(player) + " kept out of the window");
    }

    /** The armed screen when [player] is the reel on screen and its pause should hold auto-enter, marked held. */
    private static synchronized Activity holding(Object player) {
        if (held || player != current.get()) return null;
        Activity screen = armedScreen.get();
        if (screen == null || screen.isInPictureInPictureMode() || !on()) return null;
        held = true;
        return screen;
    }

    /**
     * The hook, first thing in the runnable ReelsPipUtil posts when its one player's state turns
     * auto-enter off or on. True sets that change aside: while the window is armed, the reel on
     * screen decides. False lets Facebook set it.
     */
    public static boolean setAside() {
        try {
            // Only from the More menu, Facebook's change is set aside armed or not: it may only turn auto-enter on.
            if (!on() || !(tracking() || menuOnly())) return false;
            HookStatus.bound(FAMILY, "auto-enter change");
            HookStatus.counted(FAMILY, SET_ASIDE);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "auto-enter change", failure);
            return false;
        }
    }

    /**
     * The hook, in the opening of the window's viewer, right after it asks for a new view id for
     * the container it opens in. The viewer's fragment finds that container by its id from the top
     * of [activity]'s screen, and a new id can be one a React Native view already set itself, as
     * Marketplace's does. That view wins, so the viewer opens inside the hidden main screen at no
     * size, and the window stays black while the reel plays. So an [id] a view holds is swapped for
     * one none does; a free one, or one past the tries, stays Facebook's own.
     */
    public static int viewerId(Activity activity, int id) {
        try {
            if (activity == null || !on() || activity.findViewById(id) == null) return id;
            int free = id;
            for (int tries = 0; tries < VIEWER_ID_TRIES && activity.findViewById(free) != null; tries++) {
                free = View.generateViewId();
            }
            if (activity.findViewById(free) != null) return id;
            int chosen = free;
            HookStatus.bound(FAMILY, "viewer id");
            HookStatus.counted(FAMILY, VIEWER_ID);
            Logger.printDebug(() -> "Picture-in-picture: viewer id " + id + " was taken, " + chosen + " instead");
            return chosen;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "viewer id", failure);
            return id;
        }
    }

    /**
     * The hook, right after the full-screen Watch viewer reads the server flag that lets it arm its
     * picture-in-picture window, with Facebook's [answer]. A no turns into a yes while the switch is
     * on; a yes stays one, and everything else is Facebook's own.
     */
    public static boolean immersiveAllowed(boolean answer) {
        try {
            boolean enabled = on();
            if (!enabled) return answer;
            HookStatus.bound(FAMILY, "watch viewer flag");
            if (answer) return true;
            HookStatus.counted(FAMILY, IMMERSIVE);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "watch viewer flag", failure);
            return answer;
        }
    }

    /**
     * The hook, right after the Video tab's arming reads its own copy of the Reels viewer's gate,
     * with Facebook's [answer]. A no turns into a yes while the switch is on.
     */
    public static boolean homeGateAllowed(boolean answer) {
        return opened(answer, "video tab gate", HOME_GATE);
    }

    /**
     * The hook, right after the Video tab's arming reads the server flag that lets its deep-dive
     * viewer keep the window, with Facebook's [answer]; Facebook's no turns the window off. A no
     * turns into a yes while the switch is on.
     */
    public static boolean homeFlagAllowed(boolean answer) {
        return opened(answer, "video tab flag", HOME_FLAG);
    }

    /**
     * Facebook's [answer] to a question that only decides whether to arm the window: yes while the
     * switch is on, counting [counted] when it was a no, and Facebook's own otherwise.
     */
    private static boolean opened(boolean answer, String hook, String counted) {
        try {
            if (!on()) return answer;
            HookStatus.bound(FAMILY, hook);
            if (answer) return true;
            HookStatus.counted(FAMILY, counted);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, hook, failure);
            return answer;
        }
    }

    /** Whether a window is armed with a reel on screen. */
    private static synchronized boolean tracking() {
        return armedScreen.get() != null && current.get() != null;
    }

    /**
     * Params with only auto-enter set change only auto-enter, as Facebook's own disarm relies on.
     * While the window opens only from the More menu, it's always off.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private static void autoEnter(Activity screen, boolean enabled) {
        boolean answer = enabled && !menuOnly();
        screen.setPictureInPictureParams(new PictureInPictureParams.Builder().setAutoEnterEnabled(answer).build());
    }

    /** Whether the switch is on, on Android 12 or later: Facebook's own code needs its auto-enter. */
    private static boolean on() {
        HookStatus.invoked(FAMILY);
        return Utils.settingsReady() && Settings.PICTURE_IN_PICTURE.get() && apiLevel >= Build.VERSION_CODES.S;
    }

    /** Whether the switch is on and the window opens only from the More menu. */
    static boolean menuOnly() {
        return on() && Settings.PICTURE_IN_PICTURE_FROM_MENU.get();
    }

    /** Forgets what was armed and when a page last brought Facebook back. Tests only. */
    static void forget() {
        arm(null, null, null);
        synchronized (PictureInPicture.class) {
            broughtBackAt = -BRING_BACK_GAP_MS;
            floating = new WeakReference<>(null);
        }
    }
}
