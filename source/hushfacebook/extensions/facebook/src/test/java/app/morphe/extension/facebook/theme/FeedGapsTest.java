/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.RectF;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.facebook.theme.FeedGapsForTests.Host;
import app.morphe.extension.facebook.theme.FeedGapsForTests.RecordingCanvas;

/**
 * Lines between posts, issue #206: with AMOLED a post with no photo ran into the next one, as the
 * gap between feed rows showed the page's black. With the switch on, each row of a list paints the
 * strip under its content a step above the page. Sizes are the S22's: 1080 px wide, density 3 here
 * so the 4 dp cap is 12 px, a post 1378 px tall over 1372 px of content.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "xxhdpi")
public class FeedGapsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int BLACK = 0xFF000000;

    /** The line the reporter showed, on black. */
    private static final int LINE = 0xFF1D1E23;

    private Context context;

    @Before
    public void linesOn() {
        context = RuntimeEnvironment.getApplication();
        DarkMode.answer(true);
        Settings.AMOLED_FEED_GAPS.save(true);
    }

    @After
    public void restore() {
        Settings.AMOLED_FEED_GAPS.resetToDefault();
        DarkMode.answer(true);
        AmoledTheme.useBackground(BLACK);
    }

    private Host feedRow(int height, int contentHeight) {
        Host row = FeedGapsForTests.row(context, 1080, height, contentHeight);
        FeedGapsForTests.list(context).addView(row);
        return row;
    }

    @Test
    public void aPostsGapIsPaintedGrayUnderItsContent() {
        RecordingCanvas canvas = FeedGapsForTests.draw(feedRow(1378, 1372));
        assertEquals("one strip", 1, canvas.rects.size());
        assertEquals("the 6 px under the content, the row's width", new RectF(0, 1372, 1080, 1378), canvas.rects.get(0));
        assertEquals("in the reporter's gray", LINE, (int) canvas.colours.get(0));
    }

    /** The stories tray's content sits one host down, in a host as tall as the row. */
    @Test
    public void aGapOneHostDownIsFoundToo() {
        Host row = new Host(context);
        Host inner = FeedGapsForTests.row(context, 1080, 641, 635);
        row.addView(inner);
        row.layout(0, 0, 1080, 641);
        inner.layout(0, 0, 1080, 641);
        FeedGapsForTests.list(context).addView(row);
        RecordingCanvas canvas = FeedGapsForTests.draw(row);
        assertEquals("the tray's 6 px", new RectF(0, 635, 1080, 641), canvas.rects.get(0));
    }

    @Test
    public void aLighterBackgroundGetsTheSameStepAboveIt() {
        AmoledTheme.useBackground(0xFF101010);
        RecordingCanvas canvas = FeedGapsForTests.draw(feedRow(1378, 1372));
        assertEquals("#1D1E23 above #101010", 0xFF2D2E33, (int) canvas.colours.get(0));
    }

    @Test
    public void aGoneChildIsNotTheContent() {
        Host row = feedRow(1378, 1372);
        View hidden = new View(context);
        row.addView(hidden);
        hidden.layout(0, 0, 1080, 1378);
        hidden.setVisibility(View.GONE);
        assertEquals("the gap under what shows", new RectF(0, 1372, 1080, 1378),
                FeedGapsForTests.draw(row).rects.get(0));
    }

    @Test
    public void nothingIsDrawnWithTheSwitchOff() {
        Settings.AMOLED_FEED_GAPS.save(false);
        assertTrue(FeedGapsForTests.draw(feedRow(1378, 1372)).rects.isEmpty());
    }

    /** Facebook's light feed has a gray gap of its own. */
    @Test
    public void nothingIsDrawnInLightMode() {
        DarkMode.answer(false);
        assertTrue(FeedGapsForTests.draw(feedRow(1378, 1372)).rects.isEmpty());
    }

    @Test
    public void nothingIsDrawnBeforeFacebookSaysItsDark() {
        DarkMode.forget();
        assertTrue(FeedGapsForTests.draw(feedRow(1378, 1372)).rects.isEmpty());
    }

    @Test
    public void aHostOutsideAListDrawsAsItWas() {
        Host row = FeedGapsForTests.row(context, 1080, 1378, 1372);
        new FrameLayout(context).addView(row);
        assertTrue("in another parent", FeedGapsForTests.draw(row).rects.isEmpty());
        assertTrue("with no parent", FeedGapsForTests.draw(FeedGapsForTests.row(context, 1080, 1378, 1372)).rects.isEmpty());
    }

    /** A row with room left at its bottom on purpose, a footer or a spacer, isn't Facebook's gap. */
    @Test
    public void aStripTallerThanTheGapIsLeftAlone() {
        assertTrue("13 px is over 4 dp", FeedGapsForTests.draw(feedRow(1385, 1372)).rects.isEmpty());
        assertEquals("12 px is 4 dp", 1, FeedGapsForTests.draw(feedRow(1384, 1372)).rects.size());
    }

    @Test
    public void aRowItsContentFillsGetsNoLine() {
        assertTrue("content to the bottom", FeedGapsForTests.draw(feedRow(1378, 1378)).rects.isEmpty());
        Host empty = new Host(context);
        empty.layout(0, 0, 1080, 1378);
        FeedGapsForTests.list(context).addView(empty);
        assertTrue("no content", FeedGapsForTests.draw(empty).rects.isEmpty());
    }
}
