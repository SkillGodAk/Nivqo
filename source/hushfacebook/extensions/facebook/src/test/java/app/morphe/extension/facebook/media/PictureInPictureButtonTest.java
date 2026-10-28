/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

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
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The floating Picture-in-picture button (#44): drawn only on the armed screen with its own switch
 * on (and Picture-in-picture on, unpaused, with the phone's feature); gone off, paused, disarmed,
 * with a window open or on another screen; its tap opens the window once with plain params; and a
 * failure anywhere leaves Facebook as it was.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PictureInPictureButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Records each request to open the window and answers whether a window is open. */
    public static final class Screen extends Activity {
        final List<PictureInPictureParams> entered = new ArrayList<>();
        boolean inWindow;

        @Override
        public boolean enterPictureInPictureMode(PictureInPictureParams params) {
            entered.add(params);
            return true;
        }

        @Override
        public boolean isInPictureInPictureMode() {
            return inWindow;
        }

        @Override
        public void setPictureInPictureParams(PictureInPictureParams params) {
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
        Settings.PICTURE_IN_PICTURE_BUTTON.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.PICTURE_IN_PICTURE.resetToDefault();
        Settings.PICTURE_IN_PICTURE_BUTTON.resetToDefault();
        PictureInPicture.forget();
        PictureInPictureButton.apply();
        shadowOf(Looper.getMainLooper()).idle();
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

    private int buttonsOn(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        int count = 0;
        for (int i = 0; i < content.getChildCount(); i++) {
            if (content.getChildAt(i) instanceof PictureInPictureButton.Button) count++;
        }
        return count;
    }

    @Test
    public void offByDefaultNoButtonEvenWhenArmed() {
        Settings.PICTURE_IN_PICTURE_BUTTON.resetToDefault();
        assertFalse("starts off", Settings.PICTURE_IN_PICTURE_BUTTON.get());
        PictureInPicture.armed(screen, player, null);
        idle();
        assertEquals(0, buttonsOn(screen));
        assertNull(PictureInPictureButton.showing());
    }

    @Test
    public void onTheArmedScreenTheButtonIsThereOnceAndItsTapOpensTheWindow() {
        PictureInPicture.armed(screen, player, null);
        idle();
        assertEquals("one button on the armed screen", 1, buttonsOn(screen));
        PictureInPicture.armed(screen, player, null);
        idle();
        assertEquals("arming again adds no second one", 1, buttonsOn(screen));

        View button = PictureInPictureButton.showing();
        assertNotNull(button);
        assertEquals("Picture-in-picture button", button.getContentDescription());
        button.performClick();
        assertEquals("the window waits for the tap's own dispatch", 0, screen.entered.size());
        idle();
        assertEquals("one tap opens one window", 1, screen.entered.size());
        assertFalse("plain params, like the More row", screen.entered.get(0).isAutoEnterEnabled());
        assertTrue(statusLine(), statusLine().contains(PictureInPicture.FROM_BUTTON + " 1"));
        assertTrue(statusLine(), statusLine().contains(PictureInPictureButton.BUTTON_ADDED + " 1"));
    }

    @Test
    public void noButtonOnAnotherScreenAndItLeavesOnDisarm() {
        PictureInPicture.armed(screen, player, null);
        idle();
        Screen other = Robolectric.buildActivity(Screen.class).setup().get();
        assertEquals("a screen that isn't armed has none", 0, buttonsOn(other));
        PictureInPicture.armed(other, player, null);
        idle();
        assertEquals("it moved to the newly armed screen", 0, buttonsOn(screen));
        assertEquals(1, buttonsOn(other));
        PictureInPicture.disarmed();
        idle();
        assertEquals("a disarm takes it off", 0, buttonsOn(other));
        assertNull(PictureInPictureButton.showing());
    }

    @Test
    public void switchedOffPausedOrWithoutTheFeatureItGoesAndDoesNotCome() {
        PictureInPicture.armed(screen, player, null);
        idle();
        assertEquals(1, buttonsOn(screen));

        Settings.PICTURE_IN_PICTURE_BUTTON.save(false);
        PictureInPictureButton.sync();
        idle();
        assertEquals("its own switch off", 0, buttonsOn(screen));

        Settings.PICTURE_IN_PICTURE_BUTTON.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        PictureInPicture.armed(screen, player, null);
        idle();
        assertEquals("paused is like off", 0, buttonsOn(screen));
        PauseForTests.resume();

        Settings.PICTURE_IN_PICTURE.save(false);
        PictureInPicture.armed(screen, player, null);
        idle();
        assertEquals("Picture-in-picture off", 0, buttonsOn(screen));
        Settings.PICTURE_IN_PICTURE.save(true);

        feature(false);
        PictureInPicture.armed(screen, player, null);
        idle();
        assertEquals("a phone without the feature", 0, buttonsOn(screen));
    }

    @Test
    public void anOpenWindowTakesItOffAndAStaleTapOpensNothing() {
        PictureInPicture.armed(screen, player, null);
        idle();
        View button = PictureInPictureButton.showing();
        assertNotNull(button);

        screen.inWindow = true;
        PictureInPictureButton.sync();
        idle();
        assertEquals("no button inside the window", 0, buttonsOn(screen));

        // A tap that was already queued when the window opened must not ask for a second one.
        button.performClick();
        idle();
        assertEquals(0, screen.entered.size());

        screen.inWindow = false;
        PictureInPictureButton.sync();
        idle();
        assertEquals("back full screen, it comes back", 1, buttonsOn(screen));
        assertSame(PictureInPictureButton.showing().getContext(), screen);
    }

    @Test
    public void aTapAfterTheScreenWasDisarmedOpensNothing() {
        PictureInPicture.armed(screen, player, null);
        idle();
        View button = PictureInPictureButton.showing();
        assertNotNull(button);
        PictureInPicture.disarmed();
        button.performClick();
        idle();
        assertEquals(0, screen.entered.size());
    }

    /** A screen whose entering throws, as a phone that refuses at that moment does. */
    public static final class RefusingScreen extends Activity {
        @Override
        public boolean enterPictureInPictureMode(PictureInPictureParams params) {
            throw new IllegalStateException("not allowed now");
        }

        @Override
        public void setPictureInPictureParams(PictureInPictureParams params) {
        }
    }

    @Test
    public void aThrowFailsOpen() {
        RefusingScreen refusing = Robolectric.buildActivity(RefusingScreen.class).setup().get();
        PictureInPicture.armed(refusing, player, null);
        idle();
        View button = PictureInPictureButton.showing();
        assertNotNull(button);
        assertFalse("a refused window answers false and raises nothing", PictureInPicture.enterFromButton(refusing));
        button.performClick();
        idle();
        assertFalse(PictureInPicture.enterFromButton(null));
        assertEquals("the button stays for another try", 1, buttonsOn(refusing));
    }
}
