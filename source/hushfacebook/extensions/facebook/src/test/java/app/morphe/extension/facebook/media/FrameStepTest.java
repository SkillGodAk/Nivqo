/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.facebook.media.FrameStepForTests.OnScreen;
import app.morphe.extension.facebook.media.FrameStepForTests.Player;
import app.morphe.extension.facebook.media.FrameStepForTests.Trigger;
import app.morphe.extension.facebook.media.FrameStepForTests.TriggerWithoutStep;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Frame step: a quick tap on the right or left third of a paused video seeks it one frame forward
 * or back with the frame-perfect trigger, past the player's small-seek check, and Facebook's detector gets a cancel
 * instead of the tap. Playing videos, the middle third, holds, drags and every touch while off,
 * paused or before the settings are ready go to Facebook as they were.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FrameStepTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private OnScreen screen;
    private Player player;

    /** The patch is in Morphe Manager's default selection with its switch off; these tests turn it on. */
    @Before
    public void start() {
        FrameStepForTests.install();
        Settings.FRAME_STEP.save(true);
        screen = new OnScreen(0, 0, 900, 1600);
        player = new Player(screen, 10_000);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.FRAME_STEP.resetToDefault();
        FrameStepForTests.forget();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String report() {
        return String.join("\n", FeedFilterCounters.report());
    }

    /** Runs what the main thread has due in the next [ms]. */
    private static void later(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS);
    }

    @Test
    public void theSwitchStartsOffAndAFrameIsAThirtiethOfASecond() {
        assertFalse("the switch starts off", Settings.FRAME_STEP.defaultValue);
        assertEquals(33, FrameStep.FRAME_MS);
        assertEquals("not BY_USER, which 582 seeks fast and up to two seconds early with", "BY_INITIAL_FRAME_PERFECT_SEEK",
                FrameStep.STEP_TRIGGER);
    }

    @Test
    public void aTapOnTheRightThirdOfAPausedVideoStepsAFrameForward() {
        FrameStep.stopped(player, Trigger.BY_USER);
        assertEquals("Facebook's detector gets a cancel", MotionEvent.ACTION_CANCEL, FrameStepForTests.tap(screen, 800, 800));
        assertEquals(Collections.singletonList("BY_INITIAL_FRAME_PERFECT_SEEK@10033"), player.seeks);
        assertEquals("the small-seek check heard the step", Collections.singletonList(true), player.steppingInSeek);
        assertFalse("and nothing else seeks past it", FrameStep.stepping());
        assertTrue(report(), report().contains(FrameStep.FORWARD + " 1"));
        assertTrue(HookStatus.report("").toString(), HookStatus.report("").toString()
                .contains(FamilyNames.FRAME_STEP + ": invoked"));
    }

    @Test
    public void aTapOnTheLeftThirdStepsAFrameBack() {
        FrameStep.stopped(player, Trigger.BY_AUTOPLAY);
        assertEquals(MotionEvent.ACTION_CANCEL, FrameStepForTests.tap(screen, 100, 800));
        assertEquals(Collections.singletonList("BY_INITIAL_FRAME_PERFECT_SEEK@9967"), player.seeks);
        assertTrue(report(), report().contains(FrameStep.BACK + " 1"));
    }

    /** The player takes a moment to say where it is, so each step goes on from the one before. */
    @Test
    public void stepsGoOnFromTheStepBeforeAndFromYourDragOfTheBar() {
        FrameStep.stopped(player, Trigger.BY_USER);
        FrameStepForTests.tap(screen, 800, 800);
        FrameStepForTests.tap(screen, 800, 800);
        FrameStepForTests.tap(screen, 100, 800);
        assertEquals(Arrays.asList("BY_INITIAL_FRAME_PERFECT_SEEK@10033", "BY_INITIAL_FRAME_PERFECT_SEEK@10066", "BY_INITIAL_FRAME_PERFECT_SEEK@10033"), player.seeks);

        FrameStep.seeking(Trigger.BY_SEEKBAR_CONTROLLER, player, 60_000);
        FrameStepForTests.tap(screen, 800, 800);
        assertEquals("BY_INITIAL_FRAME_PERFECT_SEEK@60033", player.seeks.get(3));
    }

    /** A 582 reel lands two seconds early: the step is asked again, and later steps allow for it. */
    @Test
    public void aStepThatLandsOffIsAskedAgainAndLaterStepsAllowForIt() {
        player.landsOff = -2_000;
        FrameStep.stopped(player, Trigger.BY_USER);
        FrameStepForTests.tap(screen, 800, 800);
        assertEquals(Collections.singletonList("BY_INITIAL_FRAME_PERFECT_SEEK@10033"), player.seeks);
        later(FrameStep.FIRST_CHECK_MS + 1);
        assertEquals(Arrays.asList("BY_INITIAL_FRAME_PERFECT_SEEK@10033", "BY_INITIAL_FRAME_PERFECT_SEEK@12033"), player.seeks);
        assertEquals("the frame wanted", 10_033, player.position);
        assertEquals("the small-seek check heard the second ask too", Arrays.asList(true, true), player.steppingInSeek);

        FrameStepForTests.tap(screen, 100, 800);
        assertEquals("BY_INITIAL_FRAME_PERFECT_SEEK@12000", player.seeks.get(2));
        assertEquals(10_000, player.position);
        later(5_000);
        assertEquals("a step that lands on its frame is left alone", 3, player.seeks.size());
        assertTrue(report(), report().contains(FrameStep.ASKED_AGAIN + " 1"));
    }

    /** Right after a seek the player answers with the place it was asked for, which says nothing yet. */
    @Test
    public void anAnswerThatSaysNothingYetIsReadAgain() {
        player.landsOff = -2_000;
        player.echoes = 2;
        FrameStep.stopped(player, Trigger.BY_USER);
        FrameStepForTests.tap(screen, 800, 800);
        later(FrameStep.FIRST_CHECK_MS + FrameStep.NEXT_CHECK_MS + 1);
        assertEquals("two answers that say nothing", 1, player.seeks.size());
        later(FrameStep.NEXT_CHECK_MS);
        assertEquals(Arrays.asList("BY_INITIAL_FRAME_PERFECT_SEEK@10033", "BY_INITIAL_FRAME_PERFECT_SEEK@12033"), player.seeks);
    }

    @Test
    public void aPlayerThatKeepsReadingTheOldPlaceIsLeftAlone() {
        FrameStep.stopped(player, Trigger.BY_USER);
        FrameStepForTests.tap(screen, 800, 800);
        later(5_000);
        assertEquals(Collections.singletonList("BY_INITIAL_FRAME_PERFECT_SEEK@10033"), player.seeks);
    }

    /** A step asked again that still lands off is asked no more, and the player's offset is forgotten. */
    @Test
    public void aStepThatLandsOffAgainIsAskedNoMore() {
        player.landsOff = -2_000;
        FrameStep.stopped(player, Trigger.BY_USER);
        FrameStepForTests.tap(screen, 800, 800);
        player.landsOff = -500;
        later(5_000);
        assertEquals(Arrays.asList("BY_INITIAL_FRAME_PERFECT_SEEK@10033", "BY_INITIAL_FRAME_PERFECT_SEEK@12033"), player.seeks);

        FrameStepForTests.tap(screen, 800, 800);
        assertEquals("asked with nothing taken off", "BY_INITIAL_FRAME_PERFECT_SEEK@10066", player.seeks.get(2));
    }

    @Test
    public void aPlayerThatPlaysOrIsSeekedBeforeTheCheckIsLeftAlone() {
        player.landsOff = -2_000;
        FrameStep.stopped(player, Trigger.BY_USER);
        FrameStepForTests.tap(screen, 800, 800);
        player.playing = true;
        later(5_000);
        assertEquals(1, player.seeks.size());

        player.playing = false;
        FrameStepForTests.tap(screen, 800, 800);
        FrameStep.seeking(Trigger.BY_SEEKBAR_CONTROLLER, player, 60_000);
        later(5_000);
        assertEquals("your drag of the bar since", 2, player.seeks.size());
    }

    @Test
    public void aStepBackStopsAtTheStart() {
        player.position = 20;
        FrameStep.stopped(player, Trigger.BY_USER);
        FrameStepForTests.tap(screen, 100, 800);
        assertEquals(Collections.singletonList("BY_INITIAL_FRAME_PERFECT_SEEK@0"), player.seeks);
        // Nothing is left to step back to, and the tap still isn't Facebook's: let through, it
        // played the video, which a tap on the left third never asks for.
        assertEquals("a back tap at the start stays out of Facebook", MotionEvent.ACTION_CANCEL,
                FrameStepForTests.tap(screen, 100, 800));
        assertEquals("and seeks nowhere", 1, player.seeks.size());
    }

    @Test
    public void theMiddleThirdGoesToFacebook() {
        FrameStep.stopped(player, Trigger.BY_USER);
        assertEquals(MotionEvent.ACTION_UP, FrameStepForTests.tap(screen, 450, 800));
        assertTrue(player.seeks.isEmpty());
    }

    @Test
    public void aPlayingVideoTakesTapsAsBefore() {
        FrameStep.stopped(player, Trigger.BY_USER);
        player.playing = true;
        assertEquals(MotionEvent.ACTION_UP, FrameStepForTests.tap(screen, 800, 800));

        player.playing = false;
        FrameStep.started(player, Trigger.BY_USER);
        assertEquals("a start forgets the pause", MotionEvent.ACTION_UP, FrameStepForTests.tap(screen, 800, 800));
        assertTrue(player.seeks.isEmpty());
        assertEquals(0, FrameStep.pausedKnown());
    }

    @Test
    public void aVideoNeverStoppedIsNotStepped() {
        assertEquals(MotionEvent.ACTION_UP, FrameStepForTests.tap(screen, 800, 800));
        assertTrue(player.seeks.isEmpty());
    }

    @Test
    public void aReleasedPlayerIsForgotten() {
        FrameStep.stopped(player, Trigger.BY_USER);
        FrameStep.released(player, Trigger.BY_PLAYER);
        assertEquals(MotionEvent.ACTION_UP, FrameStepForTests.tap(screen, 800, 800));
        assertEquals(0, FrameStep.pausedKnown());
    }

    /** A tap steps only the paused player drawing under it, never one somewhere else on the screen. */
    @Test
    public void onlyThePlayerUnderTheTapIsStepped() {
        OnScreen lower = new OnScreen(0, 1200, 900, 400);
        Player below = new Player(lower, 5_000);
        FrameStep.stopped(below, Trigger.BY_USER);
        assertEquals(MotionEvent.ACTION_UP, FrameStepForTests.tap(screen, 800, 300));
        assertTrue(below.seeks.isEmpty());
        assertEquals(MotionEvent.ACTION_CANCEL, FrameStepForTests.tap(screen, 800, 1400));
        assertEquals(Collections.singletonList("BY_INITIAL_FRAME_PERFECT_SEEK@5033"), below.seeks);

        // Two paused under one tap: the one stopped last.
        FrameStep.stopped(player, Trigger.BY_USER);
        FrameStepForTests.tap(screen, 800, 1400);
        assertEquals(Collections.singletonList("BY_INITIAL_FRAME_PERFECT_SEEK@10033"), player.seeks);
        assertEquals(1, below.seeks.size());

        player.view = null;
        FrameStepForTests.tap(screen, 100, 1400);
        assertEquals("a player with no view is left alone", "BY_INITIAL_FRAME_PERFECT_SEEK@5000", below.seeks.get(1));
    }

    @Test
    public void holdsDragsAndTwoFingersGoToFacebook() {
        FrameStep.stopped(player, Trigger.BY_USER);
        long hold = ViewConfiguration.getLongPressTimeout();
        assertEquals("a hold", MotionEvent.ACTION_UP, FrameStepForTests.touch(screen, 800, 800, hold, 0));
        float slop = ViewConfiguration.get(screen.getContext()).getScaledTouchSlop();
        assertEquals("a drag", MotionEvent.ACTION_UP, FrameStepForTests.touch(screen, 700, 800, 50, slop + 20));
        assertTrue(player.seeks.isEmpty());

        long down = SystemClock.uptimeMillis();
        FrameStep.touch(screen, MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 800, 800, 0));
        FrameStep.touch(screen, MotionEvent.obtain(down, down + 10,
                MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 800, 800, 0));
        MotionEvent up = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, 800, 800, 0);
        FrameStep.touch(screen, up);
        assertEquals("two fingers", MotionEvent.ACTION_UP, up.getActionMasked());

        MotionEvent stray = MotionEvent.obtain(down + 100, down + 120, MotionEvent.ACTION_UP, 800, 800, 0);
        FrameStep.touch(screen, stray);
        assertEquals("an up whose down wasn't seen", MotionEvent.ACTION_UP, stray.getActionMasked());
        assertTrue(player.seeks.isEmpty());
    }

    @Test
    public void offOrPausedEveryTapIsFacebooks() {
        FrameStep.stopped(player, Trigger.BY_USER);
        Settings.FRAME_STEP.save(false);
        assertEquals(MotionEvent.ACTION_UP, FrameStepForTests.tap(screen, 800, 800));

        Settings.FRAME_STEP.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals(MotionEvent.ACTION_UP, FrameStepForTests.tap(screen, 800, 800));
        assertTrue(player.seeks.isEmpty());

        PauseForTests.resume();
        assertEquals(MotionEvent.ACTION_CANCEL, FrameStepForTests.tap(screen, 800, 800));
    }

    @Test
    public void aStopWhileOffIsNotKept() {
        Settings.FRAME_STEP.save(false);
        FrameStep.stopped(player, Trigger.BY_USER);
        Settings.FRAME_STEP.save(true);
        assertEquals(MotionEvent.ACTION_UP, FrameStepForTests.tap(screen, 800, 800));
        assertEquals(0, FrameStep.pausedKnown());
    }

    @Test
    public void aTriggerEnumWithoutByUserStepsNothingAndSaysSo() {
        FrameStep.stopped(player, TriggerWithoutStep.BY_PLAYER);
        assertEquals(MotionEvent.ACTION_UP, FrameStepForTests.tap(screen, 800, 800));
        assertTrue(player.seeks.isEmpty());
        assertTrue(HookStatus.report("").toString(), HookStatus.anyMissing());
    }

    /** Without the patch's stubs nothing seeks: the player reads as playing and has no view. */
    @Test
    public void theUnfilledStubsStepNothing() {
        FrameStep.access = FrameStep.PATCHED;
        FrameStep.stopped(player, Trigger.BY_USER);
        assertEquals(MotionEvent.ACTION_UP, FrameStepForTests.tap(screen, 800, 800));
        assertTrue(FrameStep.playing(player));
        assertEquals(FrameStep.NOT_PATCHED_TIME, FrameStep.position(player));
        assertFalse(FrameStep.seekPlayer(player, Trigger.BY_USER, 0));
    }

    @Test
    public void onlyAFewPausedPlayersAreKept() {
        for (int i = 0; i < FrameStep.MAX_PAUSED + 3; i++) {
            FrameStep.stopped(new Player(screen, i), Trigger.BY_USER);
        }
        assertEquals(FrameStep.MAX_PAUSED, FrameStep.pausedKnown());
    }

    @Test
    public void positionsReadAsSeconds() {
        assertEquals("10.033 s", FrameStep.millis(10_033));
        assertEquals("0.000 s", FrameStep.millis(0));
        assertEquals("nothing", FrameStep.millis(FrameStep.NOT_PATCHED_TIME));
    }
}
