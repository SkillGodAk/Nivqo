/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Lines between posts, an option of the AMOLED black theme (issue #206). Each row of the feed is a
 * LithoView whose content stops a few pixels short of the row's bottom, 6 px on a 1080 px S22 (583
 * {@code LoggingComponent(NewsFeedFeedUnitComponent)}, 1378 px tall over a 1372 px host, and the
 * stories tray's 641 over 635). That strip shows the row's background, in the page's colour.
 * Facebook's dark style gives the page and its feed gap one colour resource, #101011
 * ({@code FEED_GAP_VERTICAL} and {@code WASH} both point at it), and route two writes the page's
 * colour over it, so on AMOLED the strip is the page's black and a post with no photo runs into the
 * next one. No code asks for the gap's token, so no colour route can tell it from the page.
 *
 * <p>The patch calls {@link #drawRow} last thing in each Litho host's {@code dispatchDraw}, before
 * each return, so the line goes over whatever the host drew there, its children and the drawables
 * Litho mounts beside them. A host that is a row of a list ({@code android:id/list}) gets its bottom
 * strip painted {@link #colour}, where no child of it reaches. The line goes into the row's own
 * drawing and moves with it as the list scrolls, which a line the list drew wouldn't: a scroll moves
 * each row's render node without drawing the list again.
 *
 * <p>AMOLED's route three blackens each dark grey written in code, and {@link #LINE_STEP} is one. It
 * skips the extension, or the line comes out black on black, as it first did on the S22.
 *
 * <p>Every Litho host draws through here, many each frame, so a host that isn't a list's row costs
 * a parent read and an id compare.
 */
public final class FeedGaps {

    private FeedGaps() {}

    /**
     * The step above the page colour a line sits: the #1D1E23 the reporter showed as the look they
     * wanted, on black. On a Background colour it sits the same step above that colour.
     */
    static final int LINE_STEP = 0xFF1D1E23;

    /** The tallest strip that counts as Facebook's gap, in dp. Its 6 px is about 2.3 dp. */
    static final int MAX_GAP_DP = 4;

    /** How many hosts deep a row's content can sit under hosts as tall as the row: the tray's is one. */
    static final int MAX_DEPTH = 3;

    /** Main thread only, as drawing is. */
    private static final Paint PAINT = new Paint();

    /**
     * Paints the gap under a list's row, with Lines between posts on, in Facebook's dark mode.
     * Any other host, and every host in light mode, draws as Facebook drew it.
     */
    public static void drawRow(ViewGroup host, Canvas canvas) {
        ViewParent parent = host.getParent();
        if (!(parent instanceof View) || ((View) parent).getId() != android.R.id.list) return;
        HookStatus.invoked(FamilyNames.AMOLED_THEME);
        if (!on()) return;
        int top = gapTop(host, Math.round(MAX_GAP_DP * host.getResources().getDisplayMetrics().density));
        if (top < 0) return;
        PAINT.setColor(colour());
        canvas.drawRect(0, top, host.getWidth(), host.getHeight(), PAINT);
    }

    /**
     * Whether lines are drawn: the switch on, the settings ready and Facebook's dark mode said on.
     * Said, since its light feed has a gray gap of its own and a dark line there would show.
     */
    static boolean on() {
        return Utils.settingsReady() && Settings.AMOLED_FEED_GAPS.get() && DarkMode.saidOn();
    }

    /** The line's colour: {@link #LINE_STEP} above the page's colour. */
    static int colour() {
        return AmoledTheme.raise(AmoledTheme.background(), AmoledTheme.scaled(LINE_STEP));
    }

    /**
     * Where the strip under a row's content begins, in the row's own coordinates, or -1 when the
     * row has none of 1 to [maxGap] px. The content is the lowest child, or the one child as tall as
     * the row whose own children end higher.
     */
    static int gapTop(ViewGroup row, int maxGap) {
        ViewGroup level = row;
        int offset = 0;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            int bottom = 0;
            int shown = 0;
            View lowest = null;
            for (int i = 0; i < level.getChildCount(); i++) {
                View child = level.getChildAt(i);
                if (child == null || child.getVisibility() == View.GONE) continue;
                shown++;
                if (lowest == null || child.getBottom() > bottom) {
                    bottom = child.getBottom();
                    lowest = child;
                }
            }
            if (shown == 0) return -1;
            int gap = level.getHeight() - bottom;
            if (gap > 0) return gap <= maxGap ? offset + bottom : -1;
            ViewGroup inner = filling(lowest, shown);
            if (inner == null) return -1;
            offset += inner.getTop();
            level = inner;
        }
        return -1;
    }

    /** The one shown child, when it's a group that starts at its parent's top, else null. */
    @Nullable
    private static ViewGroup filling(@Nullable View lowest, int shown) {
        return shown == 1 && lowest instanceof ViewGroup && lowest.getTop() == 0 ? (ViewGroup) lowest : null;
    }
}
