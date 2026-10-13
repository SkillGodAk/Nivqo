/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Keeps the progress bar of a video on screen. Two players hide theirs a few seconds in.
 *
 * <p>The Reels viewer's bar on 581 is the unified video scrubber (VDDScrubberPlugin). It has an
 * active look and a passive one, a thin line, and the patch asks {@link #keepsReelBar} first in the
 * passive one: a yes runs the active look instead, then {@link #hideTimeLabel} hides the elapsed
 * and total time the active look shows, so it appears only while you drag, as stock. The patch hides
 * the "most replayed" graph over the bar there too, as the passive look does, and {@link
 * #clearsLastReel} has it clear the graph and the chapter marks as the scrubber takes the next reel,
 * so one reel's doesn't stay over the next. Older builds' viewer used a bottom bar
 * (FbShortsViewerBottomSeekBarPlugin), which has two sizes. Facebook
 * makes it full size, with the thumb showing and the bar taking drags, when it shows a reel's
 * controls or while you scrub, and shrinks it to a line 2 dp high with the thumb hidden and drags
 * turned off when the controls go away or the reel plays on. The patch asks {@link #keepsReelBar}
 * first in the method that shrinks it, and a yes makes it full size instead, so the bar can be
 * read and dragged at any time. The time labels stay Facebook's: they show while you scrub.
 *
 * <p>A full-screen video's controls (the plugins built on the controls class
 * FeedFullscreenVideoControlsPlugin extends) set a timer to fade out each time they show or you
 * touch them. The patch asks {@link #keepsControls} first in the method that sets that timer, and
 * a yes sets none, so the controls and their progress bar stay until you tap the video, which
 * hides them as before. The newer Litho player hides its controls from its video controls
 * extension, 3 seconds after they show or are touched, and the player that Enter fullscreen
 * landscape mode opens runs on the Reels controls component, whose controller posts a runnable that
 * hides them about 3 seconds after a tap. The patch asks {@link #keepsControls} first in that
 * extension's timer and in that runnable, and a yes hides nothing. A tap still hides them.
 *
 * <p>Tap the bar to seek, in the same family, is {@link TapToSeek}.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, both answer no and
 * Facebook carries on with its own code.
 */
public final class ProgressBar {
    /** Counted under the patch's name each time the reel's bar is kept full size. */
    static final String REEL_BAR_KEPT = "Reel progress bar kept";
    /** Counted each time the kept bar drops the last reel's graph and chapter marks for the next reel. */
    static final String NEXT_REEL_CLEARED = "Reel bar cleared for the next reel";
    /** Counted each time a full-screen video's fade timer isn't set. */
    static final String CONTROLS_KEPT = "Video controls kept";

    /** Counted each time the time label is hidden after the active look. */
    static final String TIME_LABEL_HIDDEN = "Reel time label hidden";

    /** Counted each time a reel's length check for a bar is answered yes, whatever the reel's length. */
    static final String SHORT_REEL_BAR = "Reel passed the bar's length check";

    /** Counted each time a reel Facebook gave no bar at all gets one. */
    static final String NO_BAR_REEL = "Reel without a bar given one";

    /** Counted each time the time label is shown after the active look. */
    static final String TIME_LABEL_SHOWN = "Reel time label shown";

    /** Counted each time a time label Facebook left hidden is put back while the bar shows. */
    static final String TIME_LABEL_BACK = "Reel time label put back";

    /** Counted each time the kept bar's next move is set a frame away instead of Facebook's wait. */
    static final String BAR_SMOOTHED = "Reel bar moved every frame";

    /** Counted each time a reel's footer is kept in its paused layout, with the caption above the bar. */
    static final String FOOTER_LIFTED = "Reel caption kept above the bar";

    /** Counted each time a short reel's check for the faded bar look is answered no. */
    static final String FADED_LOOK_SKIPPED = "Short reel kept from the faded bar";

    /** Counted each time the bar, while kept, is still set to the look that fades it out. */
    static final String FADED_LOOK = "Reel bar set to the faded look";

    /** Counted each time the bar, while kept, is still set to the look that hides it. */
    static final String INVISIBLE_LOOK = "Reel bar set to the invisible look";

    /** Counted each time the bar, while kept, takes back the place of a look Facebook parked to hide it. */
    static final String PARKED_HIDE_REPLACED = "Reel bar kept over a parked hide";

    /** The scrubber's looks that leave no bar on screen: 2 fades it out, 4 hides it. */
    static final int FADED = 2;
    static final int INVISIBLE = 4;

    /**
     * The least time between two refreshes of one time label. Facebook updates the bar many times a
     * second and each write lays the label out again, so the time is written about four times a
     * second, often enough for a seconds readout.
     */
    static final long TIME_REFRESH_MS = 250;

    private static final String FAMILY = FamilyNames.PROGRESS_BAR;

    /**
     * Each label's last refresh. Two scrubbers can update at once (the reel on screen and the next
     * one being readied), and each is held to {@link #TIME_REFRESH_MS} on its own.
     */
    private static final Map<View, Long> lastRefresh = new WeakHashMap<>();

    private ProgressBar() {
    }

    /**
     * The hook, first thing in the Reels viewer's method that shrinks its progress bar. True makes
     * the bar full size instead; false lets Facebook shrink it.
     */
    public static boolean keepsReelBar() {
        return keeps("reel progress bar", REEL_BAR_KEPT);
    }

    /**
     * The hook, as the scrubber takes another video. Facebook keeps one scrubber for the Reels
     * viewer and hands it reel after reel without asking for a look again, and the passive
     * look, which the kept bar never takes, is what took the last reel's "most replayed" graph and
     * chapter marks away. True has the patch hide the graph and run Facebook's own reset of the
     * chapter marks, which also brings back a bar the marks drew over; a reel with its own marks gets
     * them again from Facebook. False loads the reel as Facebook does.
     */
    public static boolean clearsLastReel() {
        return keeps("reel bar reset", NEXT_REEL_CLEARED);
    }

    /**
     * The hook, first thing in the methods that set a full-screen video's fade timer, in the older
     * player and the newer one, and in the landscape player's hide runnable. True sets no timer or
     * runs no hide, so the controls stay; false lets Facebook's code run.
     */
    public static boolean keepsControls() {
        return keeps("video controls", CONTROLS_KEPT);
    }

    /**
     * The hook, right after the active look runs in place of the passive one. The active look shows
     * the scrubber's time label, the elapsed and total time. With the time switch on it stays, and
     * {@link #refreshesTime} keeps it current. Otherwise this hides it the way the passive look does
     * (invisible, not gone, which keeps the layout), as Facebook's update only writes it during a
     * drag, and a drag shows it again.
     */
    public static void hideTimeLabel(ViewGroup label) {
        try {
            if (label == null) return;
            if (showsTime()) {
                if (label.getVisibility() != View.VISIBLE) {
                    label.setVisibility(View.VISIBLE);
                    HookStatus.counted(FAMILY, TIME_LABEL_SHOWN);
                }
            } else if (label.getVisibility() != View.INVISIBLE) {
                label.setVisibility(View.INVISIBLE);
                HookStatus.counted(FAMILY, TIME_LABEL_HIDDEN);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel time label", failure);
        }
    }

    /**
     * The hook, first thing in the scrubber's check that a reel is long enough for a bar. Facebook
     * gives a reel under its minimum length no bar at all. True answers yes for every reel, so the
     * short ones get the bar too; false lets Facebook check.
     */
    public static boolean barsEveryReel() {
        return keeps("short reel bar", SHORT_REEL_BAR);
    }

    /**
     * The hook in the scrubber's bar info builder, with Facebook's answer to whether the reel has a
     * bar at all and the reel's length in ms. Facebook answers no for an ad, for what the viewer's
     * config rules out and for some reels it flags on its servers, and the scrubber then hides the bar
     * (#73). While the bar is kept, a reel with a known length gets one; otherwise Facebook's answer
     * stands.
     */
    public static boolean hasBar(boolean facebook, int lengthMs) {
        if (facebook || lengthMs <= 0) return facebook;
        return keeps("reel without a bar", NO_BAR_REEL);
    }

    /**
     * The hook in the scrubber's progress update, with the scrubber's time label and its bar, once
     * Facebook has moved the bar and just before it checks for a drag. True has Facebook's own time
     * writer put the current elapsed and total time into it: only while the time is shown, the label
     * is on screen and {@link #TIME_REFRESH_MS} has passed since that label's last refresh. The writer
     * moves the bar as well, so it's asked only where Facebook found the position and length usable
     * (#120).
     *
     * <p>Facebook hides the label (invisible) when a touch starts, at a reel's end and while a drag
     * shows preview frames, and only its looks show it again. A look that doesn't change leaves it
     * hidden, so a reel could play on with no time until the next drag (#73). While the time is shown,
     * the bar is on screen and nobody is dragging it, this puts such a label back first.
     */
    public static boolean refreshesTime(ViewGroup label, @Nullable View bar) {
        try {
            if (label == null || !showsTime()) return false;
            if (label.getVisibility() == View.INVISIBLE && bar != null && !bar.isPressed() && visible(bar)) {
                label.setVisibility(View.VISIBLE);
                HookStatus.counted(FAMILY, TIME_LABEL_BACK);
            }
            if (!visible(label)) return false;
            long now = SystemClock.uptimeMillis();
            Long last = lastRefresh.get(label);
            if (last != null && now - last < TIME_REFRESH_MS) return false;
            lastRefresh.put(label, now);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel time", failure);
            return false;
        }
    }

    /** Whether [view] and every view above it are visible. A view not in a window yet counts by its own. */
    private static boolean visible(View view) {
        for (Object at = view; at instanceof View; at = ((View) at).getParent()) {
            if (((View) at).getVisibility() != View.VISIBLE) return false;
        }
        return true;
    }

    /**
     * The hook, first thing in the wait before the scrubber's next progress update. Facebook waits
     * 100 ms, so the bar jumps along in steps. True has the patch wait about a frame instead, so the
     * kept bar moves smoothly; false keeps Facebook's wait.
     */
    public static boolean smoothsBar() {
        return keeps("smooth reel bar", BAR_SMOOTHED);
    }

    /**
     * The hook in the Reels footer's seek bar status, when it starts a reel and each time it would
     * go to HIDDEN, which a playing reel gets. Only VISIBLE, a paused reel's status, lifts the
     * caption above where the bar and its time go, so while playing the kept bar and its time sat on
     * the caption (#73). True keeps the status VISIBLE; false lets Facebook hide it.
     */
    public static boolean liftsFooter() {
        return keeps("reel caption lift", FOOTER_LIFTED);
    }

    /**
     * The hook, first thing in the scrubber's check that a reel is short enough for the look that
     * fades the bar out, which Facebook asks before the length check {@link #barsEveryReel} answers.
     * True answers no, so a short reel keeps its bar; false lets Facebook check.
     */
    public static boolean noFadedBar() {
        return keeps("short reel faded bar", FADED_LOOK_SKIPPED);
    }

    /**
     * The hook, first thing in the scrubber's look selector, with the look. Changes nothing: while
     * the bar is kept it counts the looks that still leave no bar, so a report shows which way a
     * reel without one went.
     */
    public static void look(@Nullable Integer look) {
        try {
            if (look == null || (look != FADED && look != INVISIBLE)) return;
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.KEEP_PROGRESS_BAR.get()) return;
            HookStatus.bound(FAMILY, "reel bar look");
            HookStatus.counted(FAMILY, look == FADED ? FADED_LOOK : INVISIBLE_LOOK);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reel bar look", failure);
        }
    }

    /**
     * The hook in the scrubber's look selector, when the look asked for is the one the bar already
     * has, with the look Facebook parked to apply once a reel's player change ends. Facebook skips a
     * look the bar has, so a hide parked as one reel left the screen still landed on the next reel to
     * use the same scrubber, after that reel's own look had been skipped, and it played with no bar
     * (#73). True, while the bar is kept, for a look that shows the bar over a parked hide: the
     * selector goes on as for a new look and parks it in the hide's place. False keeps the skip.
     */
    public static boolean replacesParkedLook(@Nullable Integer look, @Nullable Integer parked) {
        if (look == null || parked == null) return false;
        if (look == FADED || look == INVISIBLE || (parked != FADED && parked != INVISIBLE)) return false;
        return keeps("reel bar parked hide", PARKED_HIDE_REPLACED);
    }

    /** Whether the reel's time label stays up: the bar is kept and the time switch is on. */
    private static boolean showsTime() {
        return Utils.settingsReady() && Settings.KEEP_PROGRESS_BAR.get() && Settings.KEEP_PROGRESS_BAR_TIME.get();
    }

    private static boolean keeps(String hook, String counted) {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.KEEP_PROGRESS_BAR.get()) return false;
            HookStatus.bound(FAMILY, hook);
            HookStatus.counted(FAMILY, counted);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, hook, failure);
            return false;
        }
    }
}
