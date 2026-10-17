/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.view.View;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Whether the tab bar at the bottom slides away while the feed scrolls down. Facebook has this
 * built in: as its main screen starts, it adds the bottom bar to the views that scroll away with
 * the feed, the way the top bar does, when one check answers yes. That check passes only with
 * scroll-away running on the phone and the bar at the bottom, and then asks two server settings
 * that turn the experiment on for some accounts. The hook sits between those two parts. Its yes
 * stands in for the server settings, and Facebook's own code then slides the bar out scrolling
 * down, brings it back scrolling up or at the top, and keeps the feed clear of it. The feed's
 * floating button, the mini player and the screens that make room for the bar ask the same check,
 * so they move with it. A kept bar answers no there instead, so it stays put on the accounts the
 * server settings give the experiment to.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the answer is no and
 * Facebook asks its server settings as before. With the bar at the top the hook isn't reached. The
 * bar's entry is made as the main screen starts, so a change shows after a restart.
 *
 * <p>The top bar is a scroll-away entry of its own, added whatever the server settings say. Each
 * move of an entry asks {@link #topBarOffset}, which keeps a kept top bar where it rests, and
 * {@link #topBarFade}, which keeps it from fading out as it would have gone. Its entry keeps its
 * place in Facebook's insets, so the feed still starts below it.
 */
public final class TabBarScrollAway {
    /** Counted under the patch's name each time the check is answered yes. */
    static final String SLIDES_AWAY = "Tab bar set to slide away";
    /** Counted under the patch's name each time the check is answered no for a kept bar. */
    static final String STAYS_PUT = "Tab bar kept on screen";
    /** Counted under the patch's name each time a kept top bar is held where it rests. */
    static final String TOP_BAR_HELD = "Top bar held on screen";
    /** Counted each time a kept top bar is kept from fading as Facebook moves it. */
    static final String TOP_BAR_UNFADED = "Top bar kept from fading";
    /** Counted each time a kept bar's pages are padded past the navigation bar under it. */
    static final String PAGES_CLEARED = "Pages kept clear of the kept tab bar";
    /** Counted each time a kept top bar lets a move through because Facebook left the bars' surface. */
    static final String TOP_BAR_LET_GO = "Top bar let go off the feed";

    /** The name Facebook's main screen gives the top bar's scroll-away entry. */
    static final String APP_HEADER = "app_header_container";
    /** The entry's name when the tabs sit at the top, under the top bar, and move with it. */
    static final String TITLE_AND_TABS = "title_bar_tab_bar_container";

    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;

    /**
     * Whether the main screen has left the surface the bars belong on. Facebook turns scroll-away
     * off as it opens the Reels tab or a full-screen page and hides the bars itself, then back on as
     * it returns to the feed. Between the two, a kept top bar lets Facebook's moves through, so it
     * goes with the feed instead of sitting over a reel (issue #159).
     */
    private static volatile boolean offTheFeed;

    private TabBarScrollAway() {
    }

    /**
     * The hook at the start of Facebook's deactivate of scroll-away: the main screen is leaving the
     * bars' surface and is about to hide them. Never throws.
     */
    public static void topBarsLetGo() {
        offTheFeed = true;
    }

    /** The hook at the start of Facebook's reactivate of scroll-away: the main screen is back on the feed. */
    public static void topBarsTakenBack() {
        offTheFeed = false;
    }

    /**
     * The hook, right after Facebook's check finds the tab bar at the bottom. True lets the bar
     * slide away; false leaves the answer to Facebook's server settings.
     */
    public static boolean slidesAway() {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.TAB_BAR_SCROLL_AWAY.get()) return false;
            HookStatus.bound(FAMILY, "tab bar scroll-away");
            HookStatus.counted(FAMILY, SLIDES_AWAY);
            Logger.printDebug(() -> "Tab bar scroll-away: the bar at the bottom slides away");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "tab bar scroll-away", failure);
            return false;
        }
    }

    /**
     * Asked just ahead of {@link #slidesAway()}. True answers Facebook's check no outright, so the
     * bar keeps its place whatever the server settings say. Hide the tab bar while scrolling wins
     * when both are on, the way the settings page greys this switch out while that one is on.
     */
    public static boolean staysPut() {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || Settings.TAB_BAR_SCROLL_AWAY.get() || !Settings.KEEP_TAB_BAR.get()) {
                return false;
            }
            HookStatus.bound(FAMILY, "tab bar kept");
            HookStatus.counted(FAMILY, STAYS_PUT);
            Logger.printDebug(() -> "Tab bar scroll-away: the bar at the bottom stays put");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "tab bar kept", failure);
            return false;
        }
    }

    /**
     * The hook at the start of each method that moves a scroll-away view, with the entry's name and
     * the distance Facebook is about to move it from where it rests. A kept top bar gets 0, so it
     * stays where it rests; every other entry, and the top bar while the switch is off, keeps
     * Facebook's distance. So does a kept top bar while the main screen is off the feed, where
     * Facebook hides the bars itself.
     */
    public static int topBarOffset(@Nullable String entry, int offset) {
        try {
            if (offset == 0 || !(APP_HEADER.equals(entry) || TITLE_AND_TABS.equals(entry))) return offset;
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.KEEP_TOP_BAR.get()) return offset;
            HookStatus.bound(FAMILY, "top bar kept");
            if (offTheFeed) {
                HookStatus.counted(FAMILY, TOP_BAR_LET_GO);
                return offset;
            }
            HookStatus.counted(FAMILY, TOP_BAR_HELD);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "top bar kept", failure);
            return offset;
        }
    }

    /**
     * The hook beside {@link #topBarOffset} in the same moves, with how far out of sight the move
     * takes the entry, 0 to 1. Facebook fades some entries by it as they go (an entry over a
     * see-through navigation bar, and one part of the top bar on some accounts), which a held
     * distance doesn't stop. A kept top bar gets 0, so it stays fully drawn.
     */
    public static float topBarFade(@Nullable String entry, float hidden) {
        try {
            if (hidden == 0f || !(APP_HEADER.equals(entry) || TITLE_AND_TABS.equals(entry))) return hidden;
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.KEEP_TOP_BAR.get() || offTheFeed) return hidden;
            HookStatus.bound(FAMILY, "top bar kept drawn");
            HookStatus.counted(FAMILY, TOP_BAR_UNFADED);
            return 0f;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "top bar kept drawn", failure);
            return hidden;
        }
    }

    /**
     * The tab pager's padding call as the main screen starts. Facebook's padding is set first,
     * always. With the bar staying put, that's the bar's height, but on a phone drawing under its
     * navigation bar the bar's holder covers the navigation bar too, so the bottom of each page, a
     * reel's progress bar with it, sat behind the tab bar. For a kept bar the pager then adds that
     * overlap each time it's laid out with Facebook's padding, and leaves any other padding alone.
     * One listener per pager, however often Facebook sets it up.
     */
    public static void padsPager(@Nullable View pager, int left, int top, int right, int bottom) {
        if (pager == null) return;
        pager.setPadding(left, top, right, bottom);
        try {
            HookStatus.invoked(FAMILY);
            if (bottom <= 0 || !Utils.settingsReady() || Settings.TAB_BAR_SCROLL_AWAY.get() || !Settings.KEEP_TAB_BAR.get()) return;
            HookStatus.bound(FAMILY, "tab bar kept pages");
            ClearsNavigationBar listening;
            synchronized (CLEARED_PAGERS) {
                listening = CLEARED_PAGERS.get(pager);
                if (listening == null) {
                    listening = new ClearsNavigationBar(bottom);
                    CLEARED_PAGERS.put(pager, listening);
                    pager.addOnLayoutChangeListener(listening);
                }
            }
            listening.facebooks = bottom;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "tab bar kept pages", failure);
        }
    }

    /** The pagers given a listener, so a second set-up doesn't add another. */
    static final Map<View, ClearsNavigationBar> CLEARED_PAGERS = new WeakHashMap<>();

    /**
     * The bottom padding a kept bar's pager wants: [facebooks], plus as much of the navigation bar
     * as the pager reaches under, with the pager ending at [pagerBottom] in a window [windowBottom]
     * tall. -1 leaves [current] alone, as Facebook set it to something other than its own or [ours].
     */
    static int keptPadding(int current, int facebooks, int ours, int pagerBottom, int windowBottom, int navigationBar) {
        if (current != facebooks && current != ours) return -1;
        int under = navigationBar <= 0 ? 0 : Math.max(0, Math.min(navigationBar, pagerBottom - (windowBottom - navigationBar)));
        return facebooks + under;
    }

    static final class ClearsNavigationBar implements View.OnLayoutChangeListener {
        private volatile int facebooks;
        private int ours = -1;

        ClearsNavigationBar(int facebooks) {
            this.facebooks = facebooks;
        }

        @Override
        public void onLayoutChange(View pager, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            try {
                WindowInsets insets = pager.getRootWindowInsets();
                if (insets == null) return;
                int[] at = new int[2];
                pager.getLocationInWindow(at);
                int current = pager.getPaddingBottom();
                int facebooks = this.facebooks;
                int wanted = keptPadding(current, facebooks, ours, at[1] + pager.getHeight(),
                        pager.getRootView().getHeight(), insets.getInsets(WindowInsets.Type.navigationBars()).bottom);
                // A padding of Facebook's own for another kind of page stays, and the overlap comes
                // back once Facebook puts the bar's padding back.
                if (wanted < 0 || wanted == current) return;
                ours = wanted;
                // Not in the middle of this layout: the padding change asks for the next one.
                pager.post(() -> {
                    if (pager.getPaddingBottom() != current) return;
                    pager.setPadding(pager.getPaddingLeft(), pager.getPaddingTop(), pager.getPaddingRight(), wanted);
                    if (wanted != facebooks) HookStatus.counted(FAMILY, PAGES_CLEARED);
                });
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "tab bar kept pages", failure);
                pager.removeOnLayoutChangeListener(this);
            }
        }
    }
}
