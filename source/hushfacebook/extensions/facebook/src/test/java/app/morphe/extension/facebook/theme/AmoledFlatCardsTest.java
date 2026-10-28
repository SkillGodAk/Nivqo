/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * Black cards, issue #191: with the switch on, a card, a comment or a popover takes the page's
 * colour instead of the near black that keeps its edge, and a secondary button takes an input's
 * fill so it still shows on the page.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AmoledFlatCardsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for Facebook's colour token enums: only the constant names matter. */
    enum Token { CARD_BACKGROUND, COMMENT_BACKGROUND, POPOVER_BACKGROUND, PRIMARY_UI, BACKGROUND_PRIMARY_UI,
        SECONDARY_BUTTON_BACKGROUND, SECONDARY_BUTTON_BACKGROUND_OPAQUE, SECONDARY_BUTTON_BACKGROUND_FLOATING,
        PRIMARY_TEXT, DIVIDER }

    private static final int BLACK = 0xFF000000;

    /** An input's fill on black, which a button takes with Black cards on. */
    private static final int INPUT_FILL = 0xFF262627;

    @Before
    public void darkMode() {
        DarkMode.answer(true);
    }

    @After
    public void restore() {
        Settings.AMOLED_FLAT_CARDS.resetToDefault();
        DarkMode.answer(true);
        AmoledTheme.useBackground(BLACK);
        AmoledTheme.forgetPageColourForTests();
    }

    /** The Menu's profile card is a #333334 card, the older ones #3A3B3C. */
    @Test
    public void aCardTakesThePagesBlack() {
        Settings.AMOLED_FLAT_CARDS.save(true);
        assertEquals("a card", BLACK, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
        assertEquals("a comment", BLACK, AmoledTheme.apply(0xFF333334, Token.COMMENT_BACKGROUND));
        assertEquals("the older palette's card", BLACK, AmoledTheme.apply(0xFF3A3B3C, Token.CARD_BACKGROUND));
        assertEquals("a popover", BLACK, AmoledTheme.apply(0xFF3B3C3E, Token.POPOVER_BACKGROUND));
        assertEquals("the black band, as before", BLACK, AmoledTheme.apply(0xFF252728, Token.CARD_BACKGROUND));
        assertEquals("a server's card", BLACK, AmoledTheme.parseColor("#333334"));
    }

    /**
     * The Create story tile at the start of the stories tray is #333334 that Facebook writes into its
     * code on its own dark branch, past every token. The patch sends it here and it takes the page's
     * colour. A link box's lighter grey, a hue and black stay.
     */
    @Test
    public void aCardsGreyOnFacebooksDarkBranchTakesThePagesBlack() {
        Settings.AMOLED_FLAT_CARDS.save(true);
        assertEquals("the Create story tile", BLACK, AmoledTheme.darkBranch(0xFF333334));
        assertEquals("a dark surface", BLACK, AmoledTheme.darkBranch(0xFF252728));
        assertEquals("a link box's grey", 0xFF3B3C3E, AmoledTheme.darkBranch(0xFF3B3C3E));
        assertEquals("a hue", 0xFF2B3036, AmoledTheme.darkBranch(0xFF2B3036));
        assertEquals("black", BLACK, AmoledTheme.darkBranch(BLACK));
        AmoledTheme.useBackground(0xFF101010);
        assertEquals("a lighter Background colour", 0xFF101010, AmoledTheme.darkBranch(0xFF333334));
    }

    /**
     * With Material You in the build too, its stand-in runs on what AMOLED's answered, as the two
     * calls sit after the tile's {@code const}: on, the tile keeps the page's black rather than
     * taking the palette's grey; off, the palette's grey as before.
     */
    @Test
    public void withMaterialYouTheTileKeepsBlackCardsBlack() {
        TonePalette palette = TonePalette.fallback();
        MaterialYouTheme.use(palette, false);
        int paletteGrey = palette.sameLightness(TonePalette.NEUTRAL, 0xFF333334);
        assertEquals("off", paletteGrey, MaterialYouTheme.darkBranch(AmoledTheme.darkBranch(0xFF333334)));

        Settings.AMOLED_FLAT_CARDS.save(true);
        assertEquals("on", BLACK, MaterialYouTheme.darkBranch(AmoledTheme.darkBranch(0xFF333334)));
    }

    /** See more and Log out sit on the Menu's cards: with the cards gone, they keep a shape. */
    @Test
    public void aButtonOrAnInputKeepsAFill() {
        Settings.AMOLED_FLAT_CARDS.save(true);
        assertEquals("an input", INPUT_FILL, AmoledTheme.apply(0xFF333334, Token.PRIMARY_UI));
        assertEquals("FDS's input", INPUT_FILL, AmoledTheme.apply(0xFF333334, Token.BACKGROUND_PRIMARY_UI));
        assertEquals("a secondary button", INPUT_FILL, AmoledTheme.apply(0xFF333334, Token.SECONDARY_BUTTON_BACKGROUND));
        assertEquals("an opaque one", INPUT_FILL, AmoledTheme.apply(0xFF46484B, Token.SECONDARY_BUTTON_BACKGROUND_OPAQUE));
        assertEquals("a floating one", INPUT_FILL, AmoledTheme.apply(0xFF46484B, Token.SECONDARY_BUTTON_BACKGROUND_FLOATING));
    }

    /** On a lighter Background colour the cards take that colour, and a button sits the same step above it. */
    @Test
    public void aLighterBackgroundColourIsWhatACardTakes() {
        Settings.AMOLED_FLAT_CARDS.save(true);
        AmoledTheme.useBackground(0xFF101010);
        assertEquals(0xFF101010, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
        assertEquals(0xFF363637, AmoledTheme.apply(0xFF46484B, Token.SECONDARY_BUTTON_BACKGROUND_OPAQUE));
        assertEquals(0xFF101010, AmoledTheme.parseColor("#333334"));
    }

    /** The mutation controls: off, the theme's near black and Facebook's buttons, as before the switch. */
    @Test
    public void offTheCardsStayNearBlack() {
        assertEquals("a card", 0xFF121213, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
        assertEquals("a server's card", 0xFF121213, AmoledTheme.parseColor("#333334"));
        assertEquals("a secondary button", 0xFF333334, AmoledTheme.apply(0xFF333334, Token.SECONDARY_BUTTON_BACKGROUND));
        assertEquals("an opaque one", 0xFF46484B, AmoledTheme.apply(0xFF46484B, Token.SECONDARY_BUTTON_BACKGROUND_OPAQUE));
        assertEquals("an input", INPUT_FILL, AmoledTheme.apply(0xFF333334, Token.PRIMARY_UI));
        assertEquals("the Create story tile", 0xFF333334, AmoledTheme.darkBranch(0xFF333334));
    }

    /** More controls, with the switch on: light mode, text, a divider, a lighter button and a hue. */
    @Test
    public void onlyDarkSurfacesChange() {
        Settings.AMOLED_FLAT_CARDS.save(true);
        assertEquals("text", 0xFF333334, AmoledTheme.apply(0xFF333334, Token.PRIMARY_TEXT));
        assertEquals("a divider", 0xFF3A3B3C, AmoledTheme.apply(0xFF3A3B3C, Token.DIVIDER));
        assertEquals("a button above the band", 0xFF5A5B5C,
                AmoledTheme.apply(0xFF5A5B5C, Token.SECONDARY_BUTTON_BACKGROUND));
        assertEquals("a button with a hue", 0xFF263951, AmoledTheme.apply(0xFF263951, Token.SECONDARY_BUTTON_BACKGROUND));
        assertEquals("a card with a hue", 0xFF2E3A44, AmoledTheme.apply(0xFF2E3A44, Token.CARD_BACKGROUND));
        assertEquals("no token to go on", 0xFF333334, AmoledTheme.apply(0xFF333334, "CARD_BACKGROUND"));

        DarkMode.answer(false);
        assertEquals("light mode, the Video tab's dark card", 0xFF333334, AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND));
        assertEquals("light mode, a button", 0xFF46484B,
                AmoledTheme.apply(0xFF46484B, Token.SECONDARY_BUTTON_BACKGROUND_OPAQUE));
        assertEquals("light mode, a server's card", 0xFF333334, AmoledTheme.parseColor("#333334"));
        assertEquals("light mode, the Create story tile", 0xFF333334, AmoledTheme.darkBranch(0xFF333334));
    }

    /** Facebook's one #333334 colour resource, which a dozen tokens' dark items point at. */
    private static final int SHARED_GREY = 0x7f06014e;

    /**
     * A dark resource with a hue (as dark as a raised grey, its channels too far apart), one that's
     * black already, and the page colour resource the patch adds.
     */
    private static final int HUE = 0x7f060150;
    private static final int ALREADY_BLACK = 0x7f060151;
    private static final int PAGE = 0x7f060fff;

    private static Context facebook() {
        Map<Integer, Integer> colours = new HashMap<>();
        colours.put(SHARED_GREY, 0xFF333334);
        colours.put(HUE, 0xFF2A3440);
        colours.put(ALREADY_BLACK, BLACK);
        colours.put(PAGE, BLACK);
        return ColourResources.context(colours, Collections.singletonMap(AmoledTheme.PAGE_COLOUR, PAGE));
    }

    /**
     * The band behind the Menu's Log out button is a drawable of the resource FDS's token lookup gives
     * out for CARD_BACKGROUND_FLAT. With the switch on, a card's answer is the page's colour resource,
     * while a button or an input asking for the same shared grey keeps it, and so does a hue.
     */
    @Test
    public void aCardsTokenResourceIsThePagesColour() {
        AmoledTheme.forgetPageColourForTests();
        Settings.AMOLED_FLAT_CARDS.save(true);
        Context context = facebook();
        assertEquals("a card", PAGE, AmoledTheme.tokenResource(SHARED_GREY, context, Token.CARD_BACKGROUND));
        assertEquals("a comment", PAGE, AmoledTheme.tokenResource(SHARED_GREY, context, Token.COMMENT_BACKGROUND));
        assertEquals("a button", SHARED_GREY,
                AmoledTheme.tokenResource(SHARED_GREY, context, Token.SECONDARY_BUTTON_BACKGROUND));
        assertEquals("an input", SHARED_GREY, AmoledTheme.tokenResource(SHARED_GREY, context, Token.PRIMARY_UI));
        assertEquals("text", SHARED_GREY, AmoledTheme.tokenResource(SHARED_GREY, context, Token.PRIMARY_TEXT));
        assertEquals("a card with a hue", HUE, AmoledTheme.tokenResource(HUE, context, Token.CARD_BACKGROUND));
        assertEquals("a card that's black already", ALREADY_BLACK,
                AmoledTheme.tokenResource(ALREADY_BLACK, context, Token.CARD_BACKGROUND));
        assertEquals("no token to go on", SHARED_GREY, AmoledTheme.tokenResource(SHARED_GREY, context, "CARD_BACKGROUND"));
        assertEquals("no resource", 0, AmoledTheme.tokenResource(0, context, Token.CARD_BACKGROUND));
        assertEquals("not a colour", 0x7f0a0001, AmoledTheme.tokenResource(0x7f0a0001, context, Token.CARD_BACKGROUND));
        assertEquals("no context", SHARED_GREY, AmoledTheme.tokenResource(SHARED_GREY, null, Token.CARD_BACKGROUND));
    }

    /** The mutation controls: off, in light mode and in a build without the page's resource, every id comes back. */
    @Test
    public void offTheTokenResourceComesBack() {
        AmoledTheme.forgetPageColourForTests();
        Context context = facebook();
        assertEquals("off", SHARED_GREY, AmoledTheme.tokenResource(SHARED_GREY, context, Token.CARD_BACKGROUND));

        Settings.AMOLED_FLAT_CARDS.save(true);
        DarkMode.answer(false);
        assertEquals("light mode", SHARED_GREY, AmoledTheme.tokenResource(SHARED_GREY, context, Token.CARD_BACKGROUND));

        DarkMode.answer(true);
        Context unpatched = ColourResources.context(Collections.singletonMap(SHARED_GREY, 0xFF333334));
        assertEquals("no page resource", SHARED_GREY,
                AmoledTheme.tokenResource(SHARED_GREY, unpatched, Token.CARD_BACKGROUND));
    }

    /** A colour asked for before the settings are ready gets the theme's own answer. */
    @Test
    public void beforeTheSettingsAreReadyTheCardsStayNearBlack() {
        Settings.AMOLED_FLAT_CARDS.save(true);
        int[] seen = new int[3];
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            seen[0] = AmoledTheme.apply(0xFF333334, Token.CARD_BACKGROUND);
            seen[1] = AmoledTheme.apply(0xFF46484B, Token.SECONDARY_BUTTON_BACKGROUND_OPAQUE);
            seen[2] = AmoledTheme.darkBranch(0xFF333334);
        });
        assertEquals(0xFF121213, seen[0]);
        assertEquals(0xFF46484B, seen[1]);
        assertEquals("the Create story tile", 0xFF333334, seen[2]);
    }
}
