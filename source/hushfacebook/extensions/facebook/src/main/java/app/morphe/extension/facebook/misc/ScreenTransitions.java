/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.app.Activity;
import android.os.Build;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Turn off screen transitions. Five things slide or zoom in Facebook, each its own way:
 *
 * <p>A screen that opens over the app as its own activity, like the post composer. Facebook asks
 * Android for a slide with {@code overridePendingTransition} as it opens and closes one, and that
 * call outranks the open and close overrides Android 14 added. So the screen asks for no pending
 * transition as it comes to the front and as it closes, after Facebook has asked for its own. The
 * Android 14 overrides are set as well: the back gesture's preview reads those.
 *
 * <p>A tab. A tap jumps the pager to the tab's page, then Facebook slides the old and new page past
 * each other unless the style the tap came with says not to. {@link #tabStyle} answers with the
 * other style, so Facebook takes its own path without the slide.
 *
 * <p>The Menu, on accounts where it's a panel beside the tabs. Facebook scrolls to the panel asked
 * for, smoothly unless told not to, and {@link #panelSlides} tells it not to.
 *
 * <p>A photo, which grows from its place in the feed to full screen as the photo viewer opens and
 * shrinks back as it closes. {@link #photoZooms} tells the patch to put it at its end place at once.
 * A viewer whose photo can't go back to its place slides away instead, and one opened without it
 * slides up, each with a fade of its background; {@link #photoViewerLength} hands those no length.
 * A multi-photo post's photo opens on a page of its own instead, which grows the photo from its
 * tile with an animator, fades its background in, and on Back shrinks the photo back or slides the
 * page away. {@link #photoPageZoom} and {@link #photoPageLength} hand each of those no length.
 *
 * <p>A screen Facebook opens inside its own, like a post's page: a fragment added with custom
 * animations, which slides in and slides out again on Back. {@link #fragmentAnimation} hands each
 * animation back as none.
 *
 * <p>A swipe between tabs asks none of these and keeps following the finger, and so does the
 * panel's settle after a drag, which Facebook scrolls from a method of its own, and a photo
 * dragged away to close it.
 *
 * <p>The screen calls come from the activity callbacks {@code SettingsEntry} registers at start,
 * so nothing here may throw into Facebook's screen. Without the patch, off, paused, before the
 * settings are ready or when anything here fails, nothing changes.
 */
public final class ScreenTransitions {
    /** Counted under the patch's name for each screen opened without a transition. */
    static final String OPENED = "screen opened without a transition";

    /** Counted for each tab shown without its slide. */
    static final String TAB = "tab shown without its slide";

    /** Counted for each panel, the Menu or the tabs beside it, shown without its slide. */
    static final String PANEL = "panel shown without its slide";

    /** Counted for each page a tab strip inside a screen shows without its slide. */
    static final String PAGE = "page shown without its slide";

    /** Counted for each photo the photo viewer opens or closes without its zoom. */
    static final String PHOTO = "photo shown without its zoom";

    /** Counted for each animation left out of a screen Facebook opens inside its own, like a post's page. */
    static final String INNER = "inner screen animation left out";

    /** Counted for each zoom, fade or slide a multi-photo post's photo page plays without its length. */
    static final String PHOTO_PAGE = "photo page animation left out";

    /** Counted for each slide or fade the photo viewer plays without its length. */
    static final String PHOTO_VIEWER = "photo viewer slide left out";

    private static final String FAMILY = FamilyNames.SCREEN_TRANSITIONS;

    /** Whether a test says the patch is in this build, instead of asking {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean inBuildForTests;

    private ScreenTransitions() {
    }

    /** As each Facebook screen is created. */
    public static void activityCreated(Activity activity) {
        try {
            if (!on()) return;
            HookStatus.bound(FAMILY, "screen opened");
            if (Build.VERSION.SDK_INT >= 34) {
                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0);
            }
            HookStatus.counted(FAMILY, OPENED);
            Logger.printDebug(() -> "Turn off screen transitions: " + activity.getClass().getSimpleName());
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "screen opened", failure);
        }
    }

    /**
     * As a screen comes to the front: a new one over the screen that opened it, or the one under a
     * screen that closed. Android takes the request only from a screen in front, and the last one
     * asked wins, which is why this isn't asked as the screen is created.
     */
    public static void activityResumed(Activity activity) {
        try {
            if (on()) activity.overridePendingTransition(0, 0);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "screen shown", failure);
        }
    }

    /** As a screen leaves the front. A closing one asks for no transition, after Facebook asked for its own. */
    public static void activityPaused(Activity activity) {
        try {
            if (activity.isFinishing() && on()) activity.overridePendingTransition(0, 0);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "screen closed", failure);
        }
    }

    /**
     * Injection point, in the tab bar's pager controller, between reading the style a tab comes in
     * with and comparing it with [slide], the style that slides the pages. While the switch is on,
     * a tab that would slide gets the other style, else [style], Facebook's own.
     */
    public static int tabStyle(int style, int slide) {
        try {
            if (style != slide || !on()) return style;
            HookStatus.bound(FAMILY, "tab switch");
            HookStatus.counted(FAMILY, TAB);
            return slide ^ 1;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "tab switch", failure);
            return style;
        }
    }

    /**
     * Injection point, in the sliding panel's snapToPanel: whether the panel asked for slides in.
     * False while the switch is on, else [slides], Facebook's own answer.
     */
    public static boolean panelSlides(boolean slides) {
        try {
            if (!slides || !on()) return slides;
            HookStatus.bound(FAMILY, "panel");
            HookStatus.counted(FAMILY, PANEL);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "panel", failure);
            return slides;
        }
    }

    /**
     * Injection point, where a tab strip inside a screen asks its pager for a page: whether the
     * page slides in. False while the switch is on, else [slides], Facebook's own answer.
     */
    public static boolean pageSlides(boolean slides) {
        try {
            if (!slides || !on()) return slides;
            HookStatus.bound(FAMILY, "tab strip");
            HookStatus.counted(FAMILY, PAGE);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "tab strip", failure);
            return slides;
        }
    }

    /**
     * Injection point, at the end of the method that sets off the zoom a photo opens and closes
     * with: whether Facebook plays it. False while the switch is on, and the patch then puts the
     * photo straight at its end place; else true.
     */
    public static boolean photoZooms() {
        try {
            if (!on()) return true;
            HookStatus.bound(FAMILY, "photo");
            HookStatus.counted(FAMILY, PHOTO);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "photo", failure);
            return true;
        }
    }

    /**
     * Injection point, at the start of the fragment transaction's setCustomAnimations, once for each
     * of the four animations it's handed (enter, exit, and the two Back plays): 0, none, while the
     * switch is on, else [animation], Facebook's own. A screen Facebook opens inside its own, like a
     * post's page, slides in and out with these.
     */
    public static int fragmentAnimation(int animation) {
        try {
            if (animation == 0 || !on()) return animation;
            HookStatus.bound(FAMILY, "inner screen");
            HookStatus.counted(FAMILY, INNER);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "inner screen", failure);
            return animation;
        }
    }

    /**
     * Injection point, at the start of the animator that grows a photo from one place to another on
     * a multi-photo post's photo page (and in the photo viewer on a phone Facebook rates older than
     * 2015): how long it runs. None, 0, while the switch is on, else [length], Facebook's own, which
     * is null for its default. An animator of no length draws its end at once and tells its
     * listeners it ended on the next frame, so the page goes on as it does after its own zoom.
     */
    @Nullable
    public static Long photoPageZoom(@Nullable Long length) {
        try {
            if (!on()) return length;
            HookStatus.bound(FAMILY, "photo page");
            HookStatus.counted(FAMILY, PHOTO_PAGE);
            return 0L;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "photo page", failure);
            return length;
        }
    }

    /**
     * Injection point, in the photo page's methods that zoom or slide it in and out, at each length
     * handed to an animator or to the fade of the page's background: 0 while the switch is on, else
     * [length], Facebook's own.
     */
    public static long photoPageLength(long length) {
        try {
            if (length == 0 || !on()) return length;
            HookStatus.bound(FAMILY, "photo page");
            HookStatus.counted(FAMILY, PHOTO_PAGE);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "photo page", failure);
            return length;
        }
    }

    /**
     * Injection point, in the photo viewer's methods that slide it away on Back or up for a photo
     * opened without its place in the feed, at each length handed to the slide or to the fade of the
     * viewer's background: 0 while the switch is on, else [length], Facebook's own.
     */
    public static long photoViewerLength(long length) {
        try {
            if (length == 0 || !on()) return length;
            HookStatus.bound(FAMILY, "photo viewer");
            HookStatus.counted(FAMILY, PHOTO_VIEWER);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "photo viewer", failure);
            return length;
        }
    }

    private static boolean on() {
        if (!inBuild()) return false;
        HookStatus.invoked(FAMILY);
        return Utils.settingsReady() && Settings.TURN_OFF_SCREEN_TRANSITIONS.get();
    }

    /** Whether Turn off screen transitions is in this build: the callbacks run in every build. */
    static boolean inBuild() {
        Boolean forced = inBuildForTests;
        return forced != null ? forced : SettingsStatus.turnOffScreenTransitions();
    }
}
