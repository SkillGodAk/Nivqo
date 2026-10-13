/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.util.concurrent.TimeUnit;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Keep the progress bar: with the switch on, the Reels viewer's bar is kept full size each time
 * Facebook would shrink it, and a full-screen video's fade timer isn't set, each counted. Off,
 * paused, or before the settings are ready, both answer no, so Facebook shrinks the bar and fades
 * the controls as before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ProgressBarTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.KEEP_PROGRESS_BAR.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.PROGRESS_BAR + ":")) return line;
        }
        return null;
    }

    @Test
    public void onTheBarAndTheControlsAreKeptEachTimeAndCounted() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        // A reel's controls hide, then it plays on: Facebook shrinks the bar twice.
        assertTrue("the reel's bar shrank with the switch on", ProgressBar.keepsReelBar());
        assertTrue("the reel's bar shrank with the switch on", ProgressBar.keepsReelBar());
        // A full-screen video's controls show once and are touched once.
        assertTrue("a fade timer was set with the switch on", ProgressBar.keepsControls());
        assertTrue("a fade timer was set with the switch on", ProgressBar.keepsControls());
        assertTrue("a fade timer was set with the switch on", ProgressBar.keepsControls());
        assertEquals(FamilyNames.PROGRESS_BAR + ": invoked 5, 2 found, 0 missing. Counted: "
                + ProgressBar.REEL_BAR_KEPT + " 2, " + ProgressBar.CONTROLS_KEPT + " 3", statusLine());
    }

    @Test
    public void offOrPausedFacebookShrinksAndFades() {
        assertFalse("the switch doesn't start off", Settings.KEEP_PROGRESS_BAR.get());
        assertFalse("off, the reel's bar was kept", ProgressBar.keepsReelBar());
        assertFalse("off, the controls were kept", ProgressBar.keepsControls());

        Settings.KEEP_PROGRESS_BAR.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " kept the reel's bar", ProgressBar.keepsReelBar());
            assertFalse("a Hushfacebook paused by " + reason + " kept the controls", ProgressBar.keepsControls());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            assertFalse("the reel's bar was kept before the settings were ready", ProgressBar.keepsReelBar());
            assertFalse("the controls were kept before the settings were ready", ProgressBar.keepsControls());
        });

        String line = statusLine();
        assertFalse("an answer that left it to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, the reel's bar wasn't kept", ProgressBar.keepsReelBar());
        assertTrue("on again after the pause, the controls weren't kept", ProgressBar.keepsControls());
    }

    @Test
    public void whileKeptAReelFacebookGaveNoBarGetsOneOnceItsLengthIsKnown() {
        // Off: Facebook's answer stands either way.
        assertFalse("off, a reel without a bar got one", ProgressBar.hasBar(false, 30_000));
        assertTrue("off, a reel with a bar lost it", ProgressBar.hasBar(true, 30_000));

        Settings.KEEP_PROGRESS_BAR.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("a paused Hushfacebook gave a reel a bar", ProgressBar.hasBar(false, 30_000));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertFalse("a reel got a bar before the settings were ready", ProgressBar.hasBar(false, 30_000)));
        String line = statusLine();
        assertFalse("an answer that left it to Facebook was counted: " + line, line != null && line.contains("Counted"));

        assertTrue("a reel Facebook had a bar for lost it", ProgressBar.hasBar(true, 30_000));
        assertTrue("a reel Facebook gave no bar didn't get one", ProgressBar.hasBar(false, 30_000));
        // A length not known yet (or a live video's) leaves Facebook's no, as the bar would have nothing to show.
        assertFalse("a reel of unknown length got a bar", ProgressBar.hasBar(false, 0));
        assertFalse("a reel of unknown length got a bar", ProgressBar.hasBar(false, -1));
        line = statusLine();
        assertTrue("only the reel given a bar was counted: " + line,
                line != null && line.endsWith("Counted: " + ProgressBar.NO_BAR_REEL + " 1"));
    }

    @Test
    public void theSwitchNeedsNoRestartAndTravelsWithItsFamily() {
        assertFalse("each shrink and timer asks again, so no restart is needed", Settings.KEEP_PROGRESS_BAR.rebootApp);
        assertNull("nothing asks before the switch changes", Settings.KEEP_PROGRESS_BAR.userDialogMessage);
        assertTrue("Pause and the report don't know the switch",
                PatchFamily.PROGRESS_BAR.switches.contains(Settings.KEEP_PROGRESS_BAR));
    }

    @Test
    public void theTimeLabelIsHiddenAfterTheActiveLookAndCountedOnce() {
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        label.setVisibility(View.VISIBLE);
        ProgressBar.hideTimeLabel(label);
        assertEquals("the label stayed on screen", View.INVISIBLE, label.getVisibility());
        // Already hidden: nothing to do, nothing counted again.
        ProgressBar.hideTimeLabel(label);
        assertEquals(View.INVISIBLE, label.getVisibility());
        assertEquals(FamilyNames.PROGRESS_BAR + ": invoked 0, 0 found, 0 missing. Counted: "
                + ProgressBar.TIME_LABEL_HIDDEN + " 1", statusLine());
    }

    @Test
    public void aMissingTimeLabelIsLeftAlone() {
        ProgressBar.hideTimeLabel(null);
        assertNull("a missing label was reported", statusLine());
    }

    @Test
    public void onShortReelsKeepTheBarTheCaptionStaysAboveItAndTheBarMovesEveryFrame() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        assertTrue("a short reel was left without a bar", ProgressBar.barsEveryReel());
        assertTrue("a short reel was given the faded bar", ProgressBar.noFadedBar());
        assertTrue("a playing reel's caption went back down onto the bar", ProgressBar.liftsFooter());
        assertTrue("the bar kept Facebook's 100 ms wait", ProgressBar.smoothsBar());
        assertEquals(FamilyNames.PROGRESS_BAR + ": invoked 4, 4 found, 0 missing. Counted: "
                + ProgressBar.SHORT_REEL_BAR + " 1, " + ProgressBar.FADED_LOOK_SKIPPED + " 1, "
                + ProgressBar.FOOTER_LIFTED + " 1, " + ProgressBar.BAR_SMOOTHED + " 1", statusLine());
    }

    /** The next reel starts without the last one's graph and chapter marks only while the bar is kept. */
    @Test
    public void theNextReelIsClearedOnlyWhileTheBarIsKept() {
        assertFalse("off, the last reel's graph was cleared", ProgressBar.clearsLastReel());
        Settings.KEEP_PROGRESS_BAR.save(true);
        assertTrue("kept, the last reel's graph stayed for the next", ProgressBar.clearsLastReel());
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused, the last reel's graph was cleared", ProgressBar.clearsLastReel());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("cleared before the settings were ready", ProgressBar.clearsLastReel()));
        String line = statusLine();
        assertTrue("only the kept reel was counted: " + line, line.endsWith(", 1 found, 0 missing. Counted: "
                + ProgressBar.NEXT_REEL_CLEARED + " 1"));
    }

    /** The looks that leave no bar are counted while it's kept, and the others aren't. */
    @Test
    public void theLooksThatLeaveNoBarAreCountedWhileItsKept() {
        ProgressBar.look(ProgressBar.FADED);
        assertEquals("off, a look was counted", FamilyNames.PROGRESS_BAR + ": invoked 1, 0 found, 0 missing", statusLine());
        Settings.KEEP_PROGRESS_BAR.save(true);
        for (Integer look : new Integer[]{0, 1, 3, null}) ProgressBar.look(look);
        assertEquals("a look that shows the bar was counted",
                FamilyNames.PROGRESS_BAR + ": invoked 1, 0 found, 0 missing", statusLine());
        ProgressBar.look(ProgressBar.FADED);
        ProgressBar.look(ProgressBar.INVISIBLE);
        ProgressBar.look(ProgressBar.INVISIBLE);
        assertEquals(FamilyNames.PROGRESS_BAR + ": invoked 4, 1 found, 0 missing. Counted: "
                + ProgressBar.FADED_LOOK + " 1, " + ProgressBar.INVISIBLE_LOOK + " 2", statusLine());
    }

    /** A look that shows the bar takes the place of a hide Facebook parked, only while the bar is kept (#73). */
    @Test
    public void aLookThatShowsTheBarTakesAParkedHidesPlaceWhileKept() {
        assertFalse("off, a parked hide was replaced", ProgressBar.replacesParkedLook(0, ProgressBar.INVISIBLE));
        Settings.KEEP_PROGRESS_BAR.save(true);
        for (Integer look : new Integer[]{0, 1, 3}) {
            assertTrue("look " + look + " left a parked hide", ProgressBar.replacesParkedLook(look, ProgressBar.INVISIBLE));
            assertTrue("look " + look + " left a parked fade", ProgressBar.replacesParkedLook(look, ProgressBar.FADED));
        }
        assertFalse("a hide took a parked fade's place", ProgressBar.replacesParkedLook(ProgressBar.INVISIBLE, ProgressBar.FADED));
        assertFalse("a fade took a parked hide's place", ProgressBar.replacesParkedLook(ProgressBar.FADED, ProgressBar.INVISIBLE));
        assertFalse("a parked look that shows the bar was replaced", ProgressBar.replacesParkedLook(0, 1));
        assertFalse("with nothing parked the skip was passed", ProgressBar.replacesParkedLook(0, null));
        assertFalse(ProgressBar.replacesParkedLook(null, ProgressBar.INVISIBLE));
        String line = statusLine();
        assertTrue("the six replaced hides weren't counted: " + line, line.contains(ProgressBar.PARKED_HIDE_REPLACED + " 6"));
    }

    @Test
    public void offOrPausedShortReelsAndTheBarStayFacebooks() {
        assertFalse("off, a short reel was given a bar", ProgressBar.barsEveryReel());
        assertFalse("off, a short reel's faded bar was skipped", ProgressBar.noFadedBar());
        assertFalse("off, a playing reel's caption was lifted", ProgressBar.liftsFooter());
        assertFalse("off, the bar's wait was changed", ProgressBar.smoothsBar());

        Settings.KEEP_PROGRESS_BAR.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused, a short reel was given a bar", ProgressBar.barsEveryReel());
        assertFalse("paused, a short reel's faded bar was skipped", ProgressBar.noFadedBar());
        assertFalse("paused, a playing reel's caption was lifted", ProgressBar.liftsFooter());
        assertFalse("paused, the bar's wait was changed", ProgressBar.smoothsBar());
        assertFalse("paused, a parked hide was replaced", ProgressBar.replacesParkedLook(0, ProgressBar.INVISIBLE));
        ProgressBar.look(ProgressBar.FADED);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> {
            assertFalse("a short reel was given a bar before the settings were ready", ProgressBar.barsEveryReel());
            assertFalse(ProgressBar.noFadedBar());
            assertFalse(ProgressBar.liftsFooter());
            assertFalse(ProgressBar.smoothsBar());
            assertFalse(ProgressBar.replacesParkedLook(0, ProgressBar.INVISIBLE));
            ProgressBar.look(ProgressBar.INVISIBLE);
        });
        String line = statusLine();
        assertFalse("an answer that left it to Facebook was counted: " + line, line != null && line.contains("Counted"));
    }

    @Test
    public void withTheTimeSwitchTheLabelStaysAndRefreshesAFewTimesASecond() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        assertTrue("the time switch doesn't start on", Settings.KEEP_PROGRESS_BAR_TIME.get());
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        label.setVisibility(View.INVISIBLE);
        ProgressBar.hideTimeLabel(label);
        assertEquals("the time label was hidden with the time switch on", View.VISIBLE, label.getVisibility());

        assertTrue("a shown label wasn't refreshed", ProgressBar.refreshesTime(label, null));
        assertFalse("the label was refreshed again within " + ProgressBar.TIME_REFRESH_MS + " ms",
                ProgressBar.refreshesTime(label, null));
        ShadowSystemClock.advanceBy(ProgressBar.TIME_REFRESH_MS, TimeUnit.MILLISECONDS);
        assertTrue("the label wasn't refreshed after " + ProgressBar.TIME_REFRESH_MS + " ms",
                ProgressBar.refreshesTime(label, null));

        LinearLayout next = new LinearLayout(RuntimeEnvironment.getApplication());
        next.setVisibility(View.VISIBLE);
        assertTrue("the next reel's label waited on the last one's refresh", ProgressBar.refreshesTime(next, null));
        next.setVisibility(View.INVISIBLE);
        ShadowSystemClock.advanceBy(ProgressBar.TIME_REFRESH_MS, TimeUnit.MILLISECONDS);
        assertFalse("a label off screen was refreshed", ProgressBar.refreshesTime(next, null));
        assertFalse("a missing label was refreshed", ProgressBar.refreshesTime(null, null));
    }

    @Test
    public void twoReelsUpdatingTogetherEachRefreshAFewTimesASecond() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout shown = new LinearLayout(RuntimeEnvironment.getApplication());
        LinearLayout readied = new LinearLayout(RuntimeEnvironment.getApplication());
        assertTrue(ProgressBar.refreshesTime(shown, null));
        assertTrue(ProgressBar.refreshesTime(readied, null));
        assertFalse("the first label was refreshed again because a second one updated in between",
                ProgressBar.refreshesTime(shown, null));
        assertFalse(ProgressBar.refreshesTime(readied, null));
        ShadowSystemClock.advanceBy(ProgressBar.TIME_REFRESH_MS, TimeUnit.MILLISECONDS);
        assertTrue(ProgressBar.refreshesTime(shown, null));
        assertTrue(ProgressBar.refreshesTime(readied, null));
    }

    @Test
    public void aLabelInsideAHiddenReelIsNotRefreshed() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout reel = new LinearLayout(RuntimeEnvironment.getApplication());
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        reel.addView(label);
        reel.setVisibility(View.GONE);
        assertFalse("a label whose reel is hidden was refreshed", ProgressBar.refreshesTime(label, null));
        reel.setVisibility(View.VISIBLE);
        assertTrue(ProgressBar.refreshesTime(label, null));
    }

    @Test
    public void aTimeLabelFacebookLeftHiddenComesBackOnlyWhileTheBarShowsUndragged() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        LinearLayout reel = new LinearLayout(RuntimeEnvironment.getApplication());
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
        reel.addView(label);
        reel.addView(bar);
        // #73: the reel ended, or a drag showed preview frames, and Facebook left the label hidden.
        label.setVisibility(View.INVISIBLE);
        bar.setPressed(true);
        assertFalse("the label came back mid-drag", ProgressBar.refreshesTime(label, bar));
        assertEquals(View.INVISIBLE, label.getVisibility());
        bar.setPressed(false);
        bar.setVisibility(View.INVISIBLE);
        assertFalse("the label came back with no bar on screen", ProgressBar.refreshesTime(label, bar));
        assertEquals(View.INVISIBLE, label.getVisibility());
        assertFalse("the label came back without its bar", ProgressBar.refreshesTime(label, null));
        bar.setVisibility(View.VISIBLE);
        reel.setVisibility(View.GONE);
        assertFalse("the label came back in a hidden reel", ProgressBar.refreshesTime(label, bar));
        assertEquals(View.INVISIBLE, label.getVisibility());

        reel.setVisibility(View.VISIBLE);
        assertTrue("a hidden label wasn't put back and refreshed while the bar showed",
                ProgressBar.refreshesTime(label, bar));
        assertEquals(View.VISIBLE, label.getVisibility());
        // Shown: nothing to put back, nothing counted again.
        ShadowSystemClock.advanceBy(ProgressBar.TIME_REFRESH_MS, TimeUnit.MILLISECONDS);
        assertTrue(ProgressBar.refreshesTime(label, bar));
        // Gone is Facebook taking the label out of the layout, not a look leaving it hidden.
        label.setVisibility(View.GONE);
        ShadowSystemClock.advanceBy(ProgressBar.TIME_REFRESH_MS, TimeUnit.MILLISECONDS);
        assertFalse("a gone label was refreshed", ProgressBar.refreshesTime(label, bar));
        assertEquals("a gone label came back", View.GONE, label.getVisibility());
        assertEquals(FamilyNames.PROGRESS_BAR + ": invoked 0, 0 found, 0 missing. Counted: "
                + ProgressBar.TIME_LABEL_BACK + " 1", statusLine());
    }

    @Test
    public void withoutTheTimeSwitchAHiddenLabelStaysHidden() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        Settings.KEEP_PROGRESS_BAR_TIME.save(false);
        try {
            LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
            SeekBar bar = new SeekBar(RuntimeEnvironment.getApplication());
            label.setVisibility(View.INVISIBLE);
            assertFalse(ProgressBar.refreshesTime(label, bar));
            assertEquals("the label came back with the time switch off", View.INVISIBLE, label.getVisibility());
        } finally {
            Settings.KEEP_PROGRESS_BAR_TIME.resetToDefault();
        }
        Settings.KEEP_PROGRESS_BAR.save(false);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        label.setVisibility(View.INVISIBLE);
        assertFalse(ProgressBar.refreshesTime(label, new SeekBar(RuntimeEnvironment.getApplication())));
        assertEquals("the label came back with the bar not kept", View.INVISIBLE, label.getVisibility());
    }

    @Test
    public void withoutTheTimeSwitchTheLabelHidesAndIsNeverRefreshed() {
        Settings.KEEP_PROGRESS_BAR.save(true);
        Settings.KEEP_PROGRESS_BAR_TIME.save(false);
        try {
            LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
            label.setVisibility(View.VISIBLE);
            ProgressBar.hideTimeLabel(label);
            assertEquals("the label stayed with the time switch off", View.INVISIBLE, label.getVisibility());
            label.setVisibility(View.VISIBLE);
            assertFalse("the label was refreshed with the time switch off", ProgressBar.refreshesTime(label, null));
        } finally {
            Settings.KEEP_PROGRESS_BAR_TIME.resetToDefault();
        }
        Settings.KEEP_PROGRESS_BAR.save(false);
        LinearLayout label = new LinearLayout(RuntimeEnvironment.getApplication());
        label.setVisibility(View.VISIBLE);
        assertFalse("the label was refreshed with the bar not kept", ProgressBar.refreshesTime(label, null));
    }

    @Test
    public void theTimeSwitchNeedsNoRestartAndTravelsWithItsFamily() {
        assertFalse(Settings.KEEP_PROGRESS_BAR_TIME.rebootApp);
        assertNull(Settings.KEEP_PROGRESS_BAR_TIME.userDialogMessage);
        assertTrue("Pause and the report don't know the time switch",
                PatchFamily.PROGRESS_BAR.switches.contains(Settings.KEEP_PROGRESS_BAR_TIME));
    }

    @Test
    public void tapTheBarToSeekNeedsNoRestartAndTravelsWithItsFamily() {
        assertFalse(Settings.TAP_TO_SEEK.rebootApp);
        assertNull(Settings.TAP_TO_SEEK.userDialogMessage);
        assertTrue("Pause and the report don't know Tap the bar to seek",
                PatchFamily.PROGRESS_BAR.switches.contains(Settings.TAP_TO_SEEK));
    }
}
