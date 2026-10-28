/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Open comments with a gesture: a double tap or a long press on a reel presses the reel's Comment
 * button, or in Clean mode builds its sheet from the reel's long-press handler, with the switch on,
 * and leaves everything to Facebook with it off, while Hushfacebook is paused, and whenever the reel
 * or the view isn't known.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelGesturesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final class Pressed {
        final View view;
        final Object button;

        Pressed(View view, Object button) {
            this.view = view;
            this.button = button;
        }
    }

    /** Facebook's long-press handler: a lambda keeping the reel it captured. */
    @SuppressWarnings("unused")
    private static final class Handler {
        final Object $shortFormVideoUnit;

        Handler(Object reel) {
            this.$shortFormVideoUnit = reel;
        }
    }

    /** Facebook's long-press handler as the overlay makes it: the reel and what the comment sheet needs. */
    @SuppressWarnings("unused")
    private static final class FullHandler {
        final Object $shortFormVideoUnit;
        final Object $fbUserSession = new Object();
        final Object $environment = new Object();
        final Object $videoGraphQLPhoton = new Object();
        final Object $immersiveFeedPlayerConfig = new Object();

        FullHandler(Object reel) {
            this.$shortFormVideoUnit = reel;
        }
    }

    /** Facebook's long-press handler as the video controls make it, Clean mode's only one: no story holder. */
    @SuppressWarnings("unused")
    private static final class ControlsHandler {
        final Object $shortFormVideoUnit;
        final Object $fbUserSession = new Object();
        final Object $environment = new Object();
        final Object $videoGraphQLPhoton = null;
        final Object $immersiveFeedPlayerConfig = new Object();

        ControlsHandler(Object reel) {
            this.$shortFormVideoUnit = reel;
        }
    }

    /** What the sheet opener was handed. */
    private static final class Built {
        final View view;
        final Object[] parts;

        Built(View view, Object... parts) {
            this.view = view;
            this.parts = parts;
        }
    }

    /** A handler of some other shape: no reel in it. */
    private static final class OtherHandler {
        final Object other = new Object();
    }

    /** The gesture a long press hands over, with the view it landed on. */
    @SuppressWarnings("unused")
    private static final class Holder {
        final android.view.MotionEvent event = null;
        final View view;

        Holder(View view) {
            this.view = view;
        }
    }

    private final List<Pressed> pressed = new ArrayList<>();
    private final List<Built> built = new ArrayList<>();
    /** ReelGestures holds buttons weakly, so the test keeps them, as a reel's views do on a phone. */
    private final List<Object> buttons = new ArrayList<>();
    private ReelGestures.Opener saved;
    private ReelGestures.SheetOpener savedSheet;
    private View view;

    /** A Comment button object that stays alive until the test ends, so a GC can't drop it mid-test. */
    private Object button() {
        Object button = new Object();
        buttons.add(button);
        return button;
    }

    @Before
    public void setUp() {
        saved = ReelGestures.opener;
        savedSheet = ReelGestures.sheetOpener;
        ReelGestures.reset();
        pressed.clear();
        built.clear();
        ReelGestures.opener = (v, button) -> {
            pressed.add(new Pressed((View) v, button));
            return true;
        };
        ReelGestures.sheetOpener = (v, session, item, environment, story, config) -> {
            built.add(new Built((View) v, session, item, environment, story, config));
            return true;
        };
        view = new View(RuntimeEnvironment.getApplication());
        ReelGestures.noteView(view);
        Settings.DOUBLE_TAP_OPENS_COMMENTS.save(true);
        Settings.LONG_PRESS_OPENS_COMMENTS.save(true);
    }

    @After
    public void restore() {
        ReelGestures.opener = saved;
        ReelGestures.sheetOpener = savedSheet;
        ReelGestures.reset();
        PauseForTests.resume();
        Settings.DOUBLE_TAP_OPENS_COMMENTS.resetToDefault();
        Settings.LONG_PRESS_OPENS_COMMENTS.resetToDefault();
        Settings.TURN_OFF_DOUBLE_TAP_LIKE.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_GESTURES + ":")) return line;
        }
        return null;
    }

    @Test
    public void bothSwitchesStartOff() {
        assertFalse(Settings.DOUBLE_TAP_OPENS_COMMENTS.defaultValue);
        assertFalse(Settings.LONG_PRESS_OPENS_COMMENTS.defaultValue);
    }

    @Test
    public void aDoubleTapPressesTheReelsCommentButtonWithTheViewItLandedOn() {
        Object reel = new Object();
        Object button = new Object();
        ReelGestures.remember(button, reel);
        assertTrue(ReelGestures.doubleTap(reel));
        assertEquals(1, pressed.size());
        assertSame(button, pressed.get(0).button);
        assertSame(view, pressed.get(0).view);
        String line = statusLine();
        assertNotNull(line);
        assertTrue(line, line.contains(ReelGestures.OPENED + " 1"));
    }

    @Test
    public void aDoubleTapOnAReelWithoutAButtonStillDoesNotLike() {
        assertTrue("the double tap was left to like", ReelGestures.doubleTap(new Object()));
        assertTrue(pressed.isEmpty());
        assertTrue(statusLine(), statusLine().contains(ReelGestures.NO_BUTTON + " 1"));
        assertTrue("a null reel is left to like", ReelGestures.doubleTap(null));
    }

    @Test
    public void aDoubleTapWithoutAViewDoesNotLikeEither() {
        ReelGestures.reset();
        Object reel = new Object();
        ReelGestures.remember(button(),reel);
        assertTrue(ReelGestures.doubleTap(reel));
        assertTrue(pressed.isEmpty());
        assertTrue(statusLine(), statusLine().contains(ReelGestures.NO_VIEW + " 1"));
    }

    @Test
    public void offOrPausedADoubleTapIsFacebooks() {
        Object reel = new Object();
        ReelGestures.remember(button(),reel);
        Settings.DOUBLE_TAP_OPENS_COMMENTS.save(false);
        assertFalse(ReelGestures.doubleTap(reel));
        Settings.DOUBLE_TAP_OPENS_COMMENTS.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(reason);
            assertFalse(reason.name(), ReelGestures.doubleTap(reel));
        }
        PauseForTests.resume();
        assertTrue(ReelGestures.doubleTap(reel));
        assertEquals(1, pressed.size());
    }

    @Test
    public void turnOffDoubleTapToLikeStillWins() {
        Object reel = new Object();
        ReelGestures.remember(button(),reel);
        Settings.TURN_OFF_DOUBLE_TAP_LIKE.save(true);
        assertFalse(ReelGestures.doubleTap(reel));
        assertTrue(pressed.isEmpty());
    }

    @Test
    public void aLongPressPressesTheCommentButtonOfTheReelTheHandlerCaptured() {
        Object reel = new Object();
        Object other = new Object();
        Object button = new Object();
        ReelGestures.remember(button(),other);
        ReelGestures.remember(button, reel);
        assertTrue(ReelGestures.longPress(new Handler(reel), new Holder(view)));
        assertEquals(1, pressed.size());
        assertSame(button, pressed.get(0).button);
        assertSame("the view comes from the gesture, not the last double tap", view, pressed.get(0).view);
    }

    @Test
    public void aLongPressThatCannotOpenCommentsIsFacebooks() {
        Object reel = new Object();
        ReelGestures.remember(button(),reel);
        assertFalse("no button for this reel", ReelGestures.longPress(new Handler(new Object()), new Holder(view)));
        assertFalse("no view", ReelGestures.longPress(new Handler(reel), new Holder(null)));
        assertFalse("no holder", ReelGestures.longPress(new Handler(reel), null));
        assertFalse("no handler", ReelGestures.longPress(null, new Holder(view)));
        assertFalse("a handler with no reel", ReelGestures.longPress(new OtherHandler(), new Holder(view)));
        assertFalse("a handler with no reel to find", ReelGestures.longPress(new Object(), new Object()));
        assertTrue(pressed.isEmpty());
    }

    @Test
    public void offOrPausedALongPressIsFacebooks() {
        Object reel = new Object();
        ReelGestures.remember(button(),reel);
        Settings.LONG_PRESS_OPENS_COMMENTS.save(false);
        assertFalse(ReelGestures.longPress(new Handler(reel), new Holder(view)));
        Settings.LONG_PRESS_OPENS_COMMENTS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(ReelGestures.longPress(new Handler(reel), new Holder(view)));
        PauseForTests.resume();
        assertTrue(ReelGestures.longPress(new Handler(reel), new Holder(view)));
        assertEquals(1, pressed.size());
    }

    @Test
    public void theOtherSwitchDoesNotOpenComments() {
        Object reel = new Object();
        ReelGestures.remember(button(),reel);
        Settings.LONG_PRESS_OPENS_COMMENTS.save(false);
        assertFalse(ReelGestures.longPress(new Handler(reel), new Holder(view)));
        Settings.LONG_PRESS_OPENS_COMMENTS.save(true);
        Settings.DOUBLE_TAP_OPENS_COMMENTS.save(false);
        assertFalse(ReelGestures.doubleTap(reel));
        assertTrue(ReelGestures.longPress(new Handler(reel), new Holder(view)));
    }

    @Test
    public void theNewestButtonOfAReelWinsAndAnEqualReelIsFoundWhenTheSameOneIsNot() {
        Object reel = new String("reel");
        Object older = new Object();
        Object newer = new Object();
        ReelGestures.remember(older, reel);
        ReelGestures.remember(newer, reel);
        assertSame(newer, ReelGestures.buttonFor(reel));
        assertSame("an equal reel", newer, ReelGestures.buttonFor(new String("reel")));
        assertNull(ReelGestures.buttonFor("other"));
        assertNull(ReelGestures.buttonFor(null));
    }

    @Test
    public void onlyAHandfulOfButtonsAreKept() {
        Object first = new Object();
        ReelGestures.remember(button(),first);
        for (int i = 0; i < 40; i++) ReelGestures.remember(new Object(), new Object());
        assertNull("the oldest was dropped", ReelGestures.buttonFor(first));
    }

    @Test
    public void aFailingPressIsReportedAndFacebookGoesOn() {
        Object reel = new Object();
        ReelGestures.remember(button(),reel);
        ReelGestures.opener = (v, button) -> {
            throw new IllegalStateException("boom");
        };
        assertFalse("a throw in a double tap goes to Facebook", ReelGestures.doubleTap(reel));
        assertFalse("a throw in a long press goes to Facebook", ReelGestures.longPress(new Handler(reel), new Holder(view)));
        ReelGestures.opener = (v, button) -> false;
        assertFalse("a stub nobody filled in", ReelGestures.longPress(new Handler(reel), new Holder(view)));
        assertTrue(statusLine(), statusLine().contains("threw"));
    }

    @Test
    public void theUnpatchedStubPressesNothing() {
        assertFalse(ReelGestures.openStub(new Object(), new Object(), false));
        Object any = new Object();
        assertFalse(ReelGestures.openSheetStub(any, any, any, any, any, any));
    }

    @Test
    public void inCleanModeALongPressBuildsTheSheetFromItsOwnHandler() {
        Object reel = new Object();
        FullHandler handler = new FullHandler(reel);
        assertTrue(ReelGestures.longPress(handler, new Holder(view)));
        assertTrue("there's no button to press", pressed.isEmpty());
        assertEquals(1, built.size());
        Built sheet = built.get(0);
        assertSame(view, sheet.view);
        assertSame(handler.$fbUserSession, sheet.parts[0]);
        assertSame(reel, sheet.parts[1]);
        assertSame(handler.$environment, sheet.parts[2]);
        assertSame(handler.$videoGraphQLPhoton, sheet.parts[3]);
        assertSame(handler.$immersiveFeedPlayerConfig, sheet.parts[4]);
        assertTrue(statusLine(), statusLine().contains(ReelGestures.OPENED_WITHOUT_BUTTON + " 1"));
    }

    @Test
    public void theControlsHandlerWithNoStoryHolderStillBuildsTheSheet() {
        Object reel = new Object();
        ControlsHandler handler = new ControlsHandler(reel);
        ReelGestures.rememberHandler(handler, reel);
        assertTrue(ReelGestures.longPress(handler, new Holder(view)));
        assertTrue(ReelGestures.doubleTap(reel));
        assertEquals(2, built.size());
        Built sheet = built.get(0);
        assertSame(handler.$fbUserSession, sheet.parts[0]);
        assertSame(reel, sheet.parts[1]);
        assertSame(handler.$environment, sheet.parts[2]);
        assertNull("the patch's helper makes the holder from the reel", sheet.parts[3]);
        assertSame(handler.$immersiveFeedPlayerConfig, sheet.parts[4]);
        assertTrue(statusLine(), statusLine().contains(ReelGestures.OPENED_WITHOUT_BUTTON + " 2"));
    }

    @Test
    public void inCleanModeADoubleTapBuildsTheSheetFromTheNewestHandlerOfItsReel() {
        Object reel = new String("reel");
        FullHandler older = new FullHandler(reel);
        FullHandler newer = new FullHandler(reel);
        FullHandler other = new FullHandler(new Object());
        ReelGestures.rememberHandler(older, reel);
        ReelGestures.rememberHandler(newer, reel);
        ReelGestures.rememberHandler(other, other.$shortFormVideoUnit);
        assertTrue(ReelGestures.doubleTap(new String("reel")));
        assertEquals(1, built.size());
        assertSame(newer.$fbUserSession, built.get(0).parts[0]);
        assertSame(view, built.get(0).view);
        assertSame(newer, ReelGestures.handlerFor(reel));
    }

    @Test
    public void theButtonWinsOverTheHandler() {
        Object reel = new Object();
        Object button = new Object();
        ReelGestures.remember(button, reel);
        FullHandler handler = new FullHandler(reel);
        ReelGestures.rememberHandler(handler, reel);
        assertTrue(ReelGestures.longPress(handler, new Holder(view)));
        assertTrue(ReelGestures.doubleTap(reel));
        assertEquals(2, pressed.size());
        assertTrue(built.isEmpty());
        assertTrue(statusLine(), statusLine().contains(ReelGestures.OPENED + " 2"));
    }

    @Test
    public void aHandlerMissingWhatTheSheetNeedsIsNoButton() {
        Object reel = new Object();
        Handler handler = new Handler(reel);
        ReelGestures.rememberHandler(handler, reel);
        assertFalse(ReelGestures.longPress(handler, new Holder(view)));
        assertTrue(ReelGestures.doubleTap(reel));
        assertTrue(built.isEmpty());
        assertTrue(statusLine(), statusLine().contains(ReelGestures.NO_BUTTON + " 2"));
    }

    @Test
    public void aSheetThatCannotBeBuiltIsReportedAndTheLongPressIsFacebooks() {
        ReelGestures.sheetOpener = (v, session, item, environment, story, config) -> false;
        Object reel = new Object();
        assertFalse(ReelGestures.longPress(new FullHandler(reel), new Holder(view)));
        assertFalse("no view", ReelGestures.longPress(new FullHandler(reel), new Holder(null)));
        assertTrue(statusLine(), statusLine().contains("threw"));
        ReelGestures.sheetOpener = savedSheet;
        assertFalse("the unpatched stub", ReelGestures.longPress(new FullHandler(reel), new Holder(view)));
    }

    @Test
    public void rememberingNothingIsHarmless() {
        ReelGestures.remember(null, new Object());
        ReelGestures.remember(button(),null);
        ReelGestures.rememberHandler(null, new Object());
        ReelGestures.rememberHandler(new Object(), null);
        ReelGestures.noteView(null);
        assertNull(ReelGestures.buttonFor(new Object()));
        assertNull(ReelGestures.handlerFor(new Object()));
    }
}
