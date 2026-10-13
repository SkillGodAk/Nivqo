/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.drawable.ShapeDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.SeekBar;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Tap the bar to seek (#125) on a real SeekBar, laid out 1000 px wide with a 40 px thumb and the
 * track starting 20 px in, so x maps to progress as (x - 20) / 960 of 1000. Each touch goes to the
 * hook first, as the patch has it, and only a touch the hook leaves goes on to the bar, which
 * stands in for Facebook's handler. The bar's own handler is what the hook plays a tap into.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TapToSeekTest {
    private static final int Y = 30;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private long downTime;

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.TAP_TO_SEEK.resetToDefault();
        HookStatus.clear();
    }

    private static SeekBar bar(int max, int progress) {
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        ShapeDrawable thumb = new ShapeDrawable();
        thumb.setIntrinsicWidth(40);
        thumb.setIntrinsicHeight(40);
        bar.setThumb(thumb);
        bar.setThumbOffset(20);
        bar.setPadding(20, 0, 20, 0);
        bar.setMax(max);
        bar.setProgress(progress);
        bar.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY));
        bar.layout(0, 0, 1000, 60);
        return bar;
    }

    /** The touch through the hook, then on to the bar when the hook leaves it. Answers the hook. */
    private boolean touch(SeekBar bar, int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) downTime += 1000;
        MotionEvent event = MotionEvent.obtain(downTime, downTime + 10, action, x, y, 0);
        try {
            boolean taken = TapToSeek.takesTouch(bar, event);
            if (!taken) bar.onTouchEvent(event);
            return taken;
        } finally {
            event.recycle();
        }
    }

    private static int progressAt(float x) {
        return Math.round((x - 20) / 960f * 1000);
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.PROGRESS_BAR + ":")) return line;
        }
        return null;
    }

    private static void assertNear(String message, int expected, int actual) {
        assertTrue(message + ": expected about " + expected + ", got " + actual, Math.abs(expected - actual) <= 25);
    }

    @Test
    public void aTapOffTheThumbJumpsThereAsADragFromTheThumb() {
        Settings.TAP_TO_SEEK.save(true);
        SeekBar bar = bar(1000, 100);
        assertTrue("the press off the thumb went on to Facebook", touch(bar, MotionEvent.ACTION_DOWN, 800, Y));
        assertEquals("the bar moved before the finger lifted", 100, bar.getProgress());
        assertTrue("the lift went on to Facebook", touch(bar, MotionEvent.ACTION_UP, 800, Y));
        assertNear("the tap didn't seek there", progressAt(800), bar.getProgress());

        assertTrue(touch(bar, MotionEvent.ACTION_DOWN, 200, Y));
        assertTrue(touch(bar, MotionEvent.ACTION_UP, 200, Y));
        assertNear("a tap back behind the thumb didn't seek there", progressAt(200), bar.getProgress());
        String line = statusLine();
        assertTrue(line, line != null && line.contains(TapToSeek.TAPPED + " 2"));
    }

    @Test
    public void offAPressOffTheThumbIsFacebooks() {
        assertFalse("Tap the bar to seek doesn't start off", Settings.TAP_TO_SEEK.get());
        SeekBar bar = bar(1000, 100);
        assertFalse("off, the press was held", touch(bar, MotionEvent.ACTION_DOWN, 800, Y));
        assertFalse("off, the lift was held", touch(bar, MotionEvent.ACTION_UP, 800, Y));
        String line = statusLine();
        assertFalse("off, something was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void aPressOnTheThumbIsFacebooks() {
        Settings.TAP_TO_SEEK.save(true);
        SeekBar bar = bar(1000, 500);
        float thumb = bar.getThumb().getBounds().exactCenterX();
        assertFalse("a press on the thumb was held", touch(bar, MotionEvent.ACTION_DOWN, thumb, Y));
        assertFalse("its drag was held", touch(bar, MotionEvent.ACTION_MOVE, thumb + 200, Y));
        assertFalse("its lift was held", touch(bar, MotionEvent.ACTION_UP, thumb + 200, Y));
    }

    @Test
    public void aSidewaysDragOffTheThumbIsHandedToTheBarFromItsThumb() {
        Settings.TAP_TO_SEEK.save(true);
        SeekBar bar = bar(1000, 100);
        int slop = ViewConfiguration.get(bar.getContext()).getScaledTouchSlop();
        assertTrue(touch(bar, MotionEvent.ACTION_DOWN, 800, Y));
        assertTrue("a move inside the touch slop wasn't held", touch(bar, MotionEvent.ACTION_MOVE, 800 - slop / 2f, Y));
        assertFalse("the drag wasn't handed on", touch(bar, MotionEvent.ACTION_MOVE, 800 - 3 * slop, Y));
        assertFalse(touch(bar, MotionEvent.ACTION_MOVE, 600, Y));
        assertFalse("the drag's lift was held", touch(bar, MotionEvent.ACTION_UP, 600, Y));
        assertNear("the drag didn't end where the finger lifted", progressAt(600), bar.getProgress());
        String line = statusLine();
        assertTrue(line, line != null && line.contains(TapToSeek.DRAGGED + " 1"));
        assertFalse("the drag was counted as a tap: " + line, line.contains(TapToSeek.TAPPED));
    }

    @Test
    public void aSwipeUpOrDownFromTheBarSeeksNothing() {
        Settings.TAP_TO_SEEK.save(true);
        SeekBar bar = bar(1000, 100);
        assertTrue(touch(bar, MotionEvent.ACTION_DOWN, 800, Y));
        assertTrue("the swipe went to the bar", touch(bar, MotionEvent.ACTION_MOVE, 805, Y - 200));
        assertTrue(touch(bar, MotionEvent.ACTION_MOVE, 810, Y - 400));
        assertTrue("the swipe's lift went to the bar", touch(bar, MotionEvent.ACTION_UP, 810, Y - 400));
        assertEquals("the swipe moved the bar", 100, bar.getProgress());

        // The pager taking the swipe away ends it the same way.
        assertTrue(touch(bar, MotionEvent.ACTION_DOWN, 800, Y));
        assertTrue(touch(bar, MotionEvent.ACTION_CANCEL, 800, Y));
        assertEquals("a cancelled press moved the bar", 100, bar.getProgress());
        String line = statusLine();
        assertFalse("a swipe was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void aBarThatCantSeekIsFacebooks() {
        Settings.TAP_TO_SEEK.save(true);
        assertFalse("a bar with no length yet held the press", touch(bar(0, 0), MotionEvent.ACTION_DOWN, 800, Y));
        SeekBar off = bar(1000, 100);
        off.setEnabled(false);
        assertFalse("a bar Facebook turned off held the press", touch(off, MotionEvent.ACTION_DOWN, 800, Y));
        SeekBar bare = bar(1000, 100);
        bare.setThumb(null);
        assertFalse("a bar with no thumb held the press", touch(bare, MotionEvent.ACTION_DOWN, 800, Y));
        assertFalse("no bar held the press", TapToSeek.takesTouch(null, null));
    }

    @Test
    public void pausedOrNotReadyAPressIsFacebooks() {
        Settings.TAP_TO_SEEK.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " held the press",
                    touch(bar(1000, 100), MotionEvent.ACTION_DOWN, 800, Y));
            PauseForTests.resume();
        }
        SeekBar bar = bar(1000, 100);
        SettingsContextRule.withoutContext(() ->
                assertFalse("the press was held before the settings were ready", touch(bar, MotionEvent.ACTION_DOWN, 800, Y)));
        assertTrue("on again after the pause, the press wasn't held", touch(bar(1000, 100), MotionEvent.ACTION_DOWN, 800, Y));
    }
}
