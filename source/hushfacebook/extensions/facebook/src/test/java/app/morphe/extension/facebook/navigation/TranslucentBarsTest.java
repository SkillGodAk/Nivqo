/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * See-through bars (#124): each background that spans a bar gets its alpha scaled, small ones and
 * touch ripples keep theirs, Facebook setting a fill again is scaled again, and off, paused or a
 * hidden bar gives Facebook's own alpha back. Android 11 here, so no frost.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TranslucentBarsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int SEE_THROUGH = TranslucentBars.seeThrough(255, false);

    @Before
    public void start() {
        HookStatus.clear();
        Settings.TRANSLUCENT_BARS.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.TRANSLUCENT_BARS.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOffAndBelongsToTheTabBarFamily() {
        Settings.TRANSLUCENT_BARS.resetToDefault();
        assertFalse(Settings.TRANSLUCENT_BARS.get());
        assertFalse("a change shows on the next frame, without a restart", Settings.TRANSLUCENT_BARS.rebootApp);
        assertTrue(PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.TRANSLUCENT_BARS));
        assertFalse(TranslucentBars.on());
    }

    @Test
    public void eachBarIsItsHighestBarSizedViewAndTheFeedsPageIsUnderIt() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        assertSame(screen.topHolder, TranslucentBars.barOf(screen.topBar, 1600));
        assertSame(screen.tabHolder, TranslucentBars.barOf(screen.tabBar, 1600));
        assertSame(screen.page, TranslucentBars.under(screen.topHolder));
        assertSame(screen.page, TranslucentBars.under(screen.tabHolder));
        assertNull("nothing is drawn before the page", TranslucentBars.under(screen.page));
    }

    @Test
    public void onTheFillsGoSeeThroughAndTheIconsBadgesAndRipplesStay() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        Drawable ripple = screen.tab.getBackground();
        int rippleAlpha = ripple.getAlpha();

        screen.bars.update();

        assertEquals(SEE_THROUGH, screen.topHolder.getBackground().getAlpha());
        assertEquals("the theme's colour stays, with less of it", TranslucentBarsForTests.TOP_COLOUR & 0xFFFFFF,
                ((ColorDrawable) screen.topHolder.getBackground()).getColor() & 0xFFFFFF);
        assertEquals(SEE_THROUGH, screen.tabBar.getBackground().getAlpha());
        assertEquals("a round button keeps its background", 255, screen.searchButton.getBackground().getAlpha());
        assertEquals("a badge keeps its dot", 255, screen.badge.getBackground().getAlpha());
        assertEquals("a tab's ripple is left alone", rippleAlpha, ripple.getAlpha());
        assertEquals("the page under the bars is left alone", 255, screen.page.getBackground().getAlpha());
        assertEquals(2, screen.bars.fills.size());

        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.contains(TranslucentBars.MADE_SEE_THROUGH + " 2"));

        // A second frame changes nothing and counts nothing.
        screen.bars.update();
        assertEquals(SEE_THROUGH, screen.topHolder.getBackground().getAlpha());
        assertTrue(statusLine(), statusLine().contains(TranslucentBars.MADE_SEE_THROUGH + " 2"));
    }

    @Test
    public void aColourFacebookSetsAgainIsMadeSeeThroughAgain() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        screen.bars.update();
        // A theme change: Facebook's own setBackgroundColor reuses the ColorDrawable, fully drawn.
        screen.topHolder.setBackgroundColor(0xFFFFFFFF);
        assertEquals(255, screen.topHolder.getBackground().getAlpha());
        screen.bars.update();
        assertEquals(SEE_THROUGH, screen.topHolder.getBackground().getAlpha());
        assertEquals(0xFFFFFF, ((ColorDrawable) screen.topHolder.getBackground()).getColor() & 0xFFFFFF);

        Settings.TRANSLUCENT_BARS.save(false);
        screen.bars.update();
        assertEquals("the new colour comes back whole", 0xFFFFFFFF, ((ColorDrawable) screen.topHolder.getBackground()).getColor());
    }

    @Test
    public void facebooksOwnFadeIsScaledAndItsAlphaIsWhatComesBack() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        GradientDrawable header = new GradientDrawable();
        header.setColor(0xFF1877F2);
        screen.topHolder.setBackground(header);
        screen.bars.update();
        assertEquals(SEE_THROUGH, header.getAlpha());

        // Facebook fades its header's drawable as it scrolls.
        header.setAlpha(0x80);
        screen.bars.update();
        assertEquals(TranslucentBars.seeThrough(0x80, false), header.getAlpha());

        Settings.TRANSLUCENT_BARS.save(false);
        screen.bars.update();
        assertEquals("Facebook's own fade comes back, not full", 0x80, header.getAlpha());
        assertTrue(screen.bars.fills.isEmpty());
    }

    @Test
    public void offOrPausedEveryFillGetsFacebooksAlphaBackAndOnAgainItGoesAgain() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        screen.bars.update();
        assertEquals(SEE_THROUGH, screen.tabBar.getBackground().getAlpha());

        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(why);
            screen.bars.update();
            assertEquals("paused by " + why, 255, screen.topHolder.getBackground().getAlpha());
            assertEquals("paused by " + why, 255, screen.tabBar.getBackground().getAlpha());
            assertTrue(screen.bars.fills.isEmpty());
            PauseForTests.resume();
            screen.bars.update();
            assertEquals("running again after " + why, SEE_THROUGH, screen.tabBar.getBackground().getAlpha());
        }

        Settings.TRANSLUCENT_BARS.save(false);
        screen.bars.update();
        assertEquals(255, screen.topHolder.getBackground().getAlpha());
        assertEquals(255, screen.tabBar.getBackground().getAlpha());
        assertEquals(TranslucentBarsForTests.TOP_COLOUR, ((ColorDrawable) screen.topHolder.getBackground()).getColor());
        assertTrue(statusLine(), statusLine().contains(TranslucentBars.GIVEN_BACK));
        // Off, frames leave Facebook's alpha alone.
        screen.topHolder.getBackground().setAlpha(0x40);
        int facebooks = screen.topHolder.getBackground().getAlpha();
        screen.bars.update();
        assertEquals(facebooks, screen.topHolder.getBackground().getAlpha());
    }

    @Test
    public void aDrawableFacebookTakesOutOrHidesGetsItsAlphaBack() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        screen.bars.update();
        Drawable old = screen.topHolder.getBackground();
        ColorDrawable replacement = new ColorDrawable(0xFF101010);
        screen.topHolder.setBackground(replacement);
        screen.bars.update();
        assertEquals("one Facebook took out may go somewhere else", 255, old.getAlpha());
        assertEquals(SEE_THROUGH, replacement.getAlpha());

        screen.tabHolder.setVisibility(View.GONE);
        screen.layOut();
        screen.bars.update();
        assertEquals("a hidden bar's fill comes back", 255, screen.tabBar.getBackground().getAlpha());
        assertEquals(SEE_THROUGH, replacement.getAlpha());
    }

    @Test
    public void aStoppedWindowGivesEverythingBackAndStopsAsking() {
        TranslucentBarsForTests screen = new TranslucentBarsForTests();
        screen.bars.update();
        screen.bars.stop();
        assertEquals(255, screen.topHolder.getBackground().getAlpha());
        screen.bars.update();
        assertEquals(255, screen.topHolder.getBackground().getAlpha());
    }

    @Test
    public void onlyTheMainScreenGetsBars() {
        Activity other = Robolectric.buildActivity(Activity.class).setup().get();
        new TranslucentBars.Screens().onActivityResumed(other);
        synchronized (TranslucentBars.WINDOWS) {
            assertFalse(TranslucentBars.WINDOWS.containsKey(other.getWindow().getDecorView()));
        }
        assertEquals("Facebook's main screen", "com.facebook.katana.activity.FbMainTabActivity", TranslucentBars.MAIN_SCREEN);
    }

    @Test
    public void theAlphaKeepsMostOfTheBarsColour() {
        assertEquals(0xC0, TranslucentBars.seeThrough(255, false));
        assertEquals(0xA6, TranslucentBars.seeThrough(255, true));
        assertEquals(0, TranslucentBars.seeThrough(0, true));
        assertTrue("frost lets more through", TranslucentBars.seeThrough(255, true) < TranslucentBars.seeThrough(255, false));
    }
}
