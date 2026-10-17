/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The older comment row's time (#138): in dark mode, with AMOLED or Material You in the build, a
 * colour too close to its page comes back as Facebook's dark secondary text, themed like the rest,
 * and every colour that already reads, light mode and a build with neither theme stay as they were.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CommentTimeColourTest {
    private static final int BLACK = 0xFF000000;
    private static final int LIGHT_SECONDARY = 0xFF65686C;
    private static final int DARKER_SECONDARY = 0xFFA1A4A9;
    private static final int SAND = 0xFF5B513F;
    private static final int SAND_STEP = 12;

    private final TonePalette green = PalettesForTests.palette(PalettesForTests.GREEN);

    @Before
    public void greenPalette() {
        MaterialYouTheme.use(green, false);
        DarkMode.answer(true);
    }

    @After
    public void asBefore() {
        DarkMode.answer(true);
        AmoledTheme.useBackground(BLACK);
        MaterialYouTheme.use(TonePalette.fallback(), false);
    }

    @Test
    public void facebooksOwnDarkSecondaryTextIsLeftAlone() {
        for (int colour : new int[]{CommentTimeColour.DARK_SECONDARY_TEXT, DARKER_SECONDARY, 0xFFF2F4F7}) {
            assertEquals(Integer.toHexString(colour), colour, CommentTimeColour.commentTime(colour, true, false, true));
            assertEquals(Integer.toHexString(colour), colour, CommentTimeColour.commentTime(colour, false, true, true));
            assertEquals(Integer.toHexString(colour), colour, CommentTimeColour.commentTime(colour, true, true, true));
        }
    }

    @Test
    public void aTimeTooCloseToTheBlackPageIsLiftedToFacebooksSecondaryText() {
        // The light theme's grey on AMOLED's black (3.7:1), black itself, a near black, and a clear
        // colour, which draws as the page under it.
        for (int colour : new int[]{LIGHT_SECONDARY, BLACK, 0xFF1C1C1D, 0x00000000, 0x20FFFFFF}) {
            int lifted = CommentTimeColour.commentTime(colour, true, false, true);
            assertEquals(Integer.toHexString(colour), CommentTimeColour.DARK_SECONDARY_TEXT, lifted);
            assertTrue(CommentTimeColour.contrast(lifted, BLACK) >= CommentTimeColour.MIN_CONTRAST);
        }
    }

    @Test
    public void withMaterialYouTheLiftedTimeTakesThePalettesNeutral() {
        int themed = green.sameLightness(TonePalette.NEUTRAL, CommentTimeColour.DARK_SECONDARY_TEXT);
        assertNotEquals("the palette moves Facebook's grey", CommentTimeColour.DARK_SECONDARY_TEXT, themed);
        // Material You by itself: the light grey on Facebook's dark sheet is 2.7:1.
        assertEquals(themed, CommentTimeColour.commentTime(LIGHT_SECONDARY, false, true, true));
        // Both themes: AMOLED's black page, then the palette.
        assertEquals(themed, CommentTimeColour.commentTime(BLACK, true, true, true));
        assertTrue(CommentTimeColour.contrast(themed, BLACK) >= CommentTimeColour.MIN_CONTRAST);
    }

    @Test
    public void onALighterBackgroundColourTheLiftedTimeKeepsTextContrast() {
        AmoledTheme.useBackground(SAND, SAND_STEP);
        int lifted = CommentTimeColour.commentTime(SAND, true, false, true);
        assertEquals("lifted as AMOLED lifts secondary text", 0xFFD6D8DA, lifted);
        assertTrue(CommentTimeColour.contrast(lifted, SAND) >= CommentTimeColour.MIN_CONTRAST);
    }

    @Test
    public void lightModeAndABuildWithNeitherThemeAreStock() {
        for (int colour : new int[]{LIGHT_SECONDARY, BLACK, 0x00000000}) {
            assertEquals("light mode, AMOLED", colour, CommentTimeColour.commentTime(colour, true, false, false));
            assertEquals("light mode, Material You", colour, CommentTimeColour.commentTime(colour, false, true, false));
            assertEquals("neither theme", colour, CommentTimeColour.commentTime(colour, false, false, true));
        }
        // The patched entry point in a build that has neither theme: SettingsStatus answers false.
        assertEquals(BLACK, CommentTimeColour.commentTime(BLACK));
    }
}
