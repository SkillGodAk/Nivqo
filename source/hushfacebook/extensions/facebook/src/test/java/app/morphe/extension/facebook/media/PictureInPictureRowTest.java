/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.view.ContextThemeWrapper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Picture-in-picture from a reel's More sheet (#44): the row is offered only on the screen the
 * window is armed on, with the switch on and the phone's feature, and its tap opens the window
 * there with the shape Facebook armed. With Only from a reel's More menu on, leaving Facebook opens
 * none: auto-enter goes off after each arming and nothing turns it back on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PictureInPictureRowTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Records each auto-enter handed to it and each request to open the window. */
    public static final class Screen extends Activity {
        final List<Boolean> autoEnter = new ArrayList<>();
        final List<PictureInPictureParams> entered = new ArrayList<>();

        @Override
        public void setPictureInPictureParams(PictureInPictureParams params) {
            autoEnter.add(params.isAutoEnterEnabled());
        }

        @Override
        public boolean enterPictureInPictureMode(PictureInPictureParams params) {
            entered.add(params);
            return true;
        }
    }

    private final Object player = new Object();
    private Screen screen;

    @Before
    public void start() {
        HookStatus.clear();
        PictureInPicture.forget();
        feature(true);
        screen = Robolectric.buildActivity(Screen.class).setup().get();
        Settings.PICTURE_IN_PICTURE.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.PICTURE_IN_PICTURE.resetToDefault();
        Settings.PICTURE_IN_PICTURE_FROM_MENU.resetToDefault();
        PictureInPicture.forget();
        feature(true);
        HookStatus.clear();
    }

    private static void feature(boolean on) {
        shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
                .setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, on);
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.PICTURE_IN_PICTURE + ":")) return line;
        }
        return null;
    }

    @Test
    public void theArmedScreensSheetGetsTheRowAndItsTapOpensTheWindow() {
        PictureInPicture.armed(screen, player, null);
        // Facebook builds the sheet in a themed wrapper of the screen.
        PictureInPictureRow row = PictureInPictureRow.forSheet(new ContextThemeWrapper(screen, 0));
        assertNotNull("the armed screen's sheet has the row", row);
        assertEquals("Picture-in-picture", PictureInPictureRow.label());

        row.run();
        assertEquals("the window waits for the tap's own dispatch", Collections.emptyList(), screen.entered);
        idle();
        assertEquals("one tap opens one window", 1, screen.entered.size());
        assertFalse("the tap leaves the armed shape alone", screen.entered.get(0).isAutoEnterEnabled());
        assertTrue(statusLine(), statusLine().contains(PictureInPictureRow.ROW_ADDED + " 1"));
        assertTrue(statusLine(), statusLine().contains(PictureInPicture.FROM_MENU + " 1"));
    }

    @Test
    public void noRowWithoutAWindowArmedOnThatScreen() {
        assertNull("nothing armed", PictureInPictureRow.forSheet(screen));
        PictureInPicture.armed(screen, player, null);
        Screen other = Robolectric.buildActivity(Screen.class).setup().get();
        assertNull("a reel opened over the armed screen", PictureInPictureRow.forSheet(other));
        assertNull("no context", PictureInPictureRow.forSheet(null));
        PictureInPicture.disarmed();
        assertNull("after a disarm", PictureInPictureRow.forSheet(screen));
    }

    @Test
    public void noRowOffPausedOrWithoutTheFeature() {
        PictureInPicture.armed(screen, player, null);
        Settings.PICTURE_IN_PICTURE.save(false);
        assertNull("off", PictureInPictureRow.forSheet(screen));
        Settings.PICTURE_IN_PICTURE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull("paused", PictureInPictureRow.forSheet(screen));
        PauseForTests.resume();
        feature(false);
        assertNull("a phone without picture-in-picture", PictureInPictureRow.forSheet(screen));
    }

    @Test
    public void aTapAfterTheScreenWasDisarmedOpensNothing() {
        PictureInPicture.armed(screen, player, null);
        PictureInPictureRow row = PictureInPictureRow.forSheet(screen);
        assertNotNull(row);
        PictureInPicture.disarmed();
        row.run();
        idle();
        assertEquals(Collections.emptyList(), screen.entered);
    }

    @Test
    public void onlyFromTheMenuKeepsAutoEnterOffAfterEachArming() {
        Settings.PICTURE_IN_PICTURE_FROM_MENU.save(true);
        PictureInPicture.armed(screen, player, null);
        idle();
        PictureInPicture.playerPaused(player);
        PictureInPicture.playerPlaying(player);
        assertEquals("the arming, the pause and the play all leave it off", Arrays.asList(false, false, false), screen.autoEnter);
        PictureInPicture.disarmed();
        assertTrue("Facebook's own change is set aside even with nothing armed", PictureInPicture.setAside());
        assertTrue(statusLine(), statusLine().contains(PictureInPicture.MENU_ONLY + " 1"));

        PictureInPicture.armed(screen, player, null);
        assertNotNull("the row is still offered", PictureInPictureRow.forSheet(screen));
    }

    @Test
    public void leavingStillOpensTheWindowUntilOnlyFromTheMenuIsOn() {
        PictureInPicture.armed(screen, player, null);
        idle();
        PictureInPicture.playerPaused(player);
        PictureInPicture.playerPlaying(player);
        assertEquals("nothing after the arming, then pause and play as before", Arrays.asList(false, true), screen.autoEnter);
        PictureInPicture.disarmed();
        assertFalse("Facebook's change goes ahead with nothing armed", PictureInPicture.setAside());
    }

    @Test
    public void onlyFromTheMenuChangesNothingWithPictureInPictureOffOrPaused() {
        Settings.PICTURE_IN_PICTURE_FROM_MENU.save(true);
        Settings.PICTURE_IN_PICTURE.save(false);
        PictureInPicture.armed(screen, player, null);
        idle();
        assertFalse(PictureInPicture.setAside());
        Settings.PICTURE_IN_PICTURE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        PictureInPicture.armed(screen, player, null);
        idle();
        assertFalse(PictureInPicture.setAside());
        assertEquals("Facebook's auto-enter stayed its own", Collections.emptyList(), screen.autoEnter);
    }
}
