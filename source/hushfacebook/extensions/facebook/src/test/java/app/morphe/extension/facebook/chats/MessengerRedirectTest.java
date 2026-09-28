/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/** The optional Facebook Chats -> Messenger bridge layered on the proven Messenger-card hook. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MessengerRedirectTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void startClean() {
        MessengerCardForTests.uninstall();
        MessengerCardForTests.newProcess();
        Settings.OPEN_CHATS_IN_MESSENGER.resetToDefault();
        Settings.HIDE_GET_MESSENGER_CARD.resetToDefault();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.OPEN_CHATS_IN_MESSENGER.resetToDefault();
        Settings.HIDE_GET_MESSENGER_CARD.resetToDefault();
        MessengerCardForTests.uninstall();
        MessengerCardForTests.newProcess();
    }

    @Test
    public void redirectStartsOffSoUpstreamChatsBehaviourIsUnchanged() {
        assertFalse(Settings.OPEN_CHATS_IN_MESSENGER.get());
        MessengerCardForTests.install(true);
        // Upstream's default still hides only the Get Messenger card.
        assertTrue(MessengerRedirect.hideOrRedirect(
                org.robolectric.RuntimeEnvironment.getApplication()));
    }

    @Test
    public void onWithMessengerLaunchesOrcaAndFinishesFacebookChatsActivity() {
        Activity chats = Robolectric.buildActivity(Activity.class).setup().get();
        MessengerCardForTests.install(true);
        Settings.OPEN_CHATS_IN_MESSENGER.save(true);

        assertTrue(MessengerRedirect.hideOrRedirect(chats));

        Intent started = shadowOf(chats).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(Intent.ACTION_MAIN, started.getAction());
        assertTrue(started.getCategories().contains(Intent.CATEGORY_LAUNCHER));
        assertEquals(MessengerCard.MESSENGER, started.getPackage());
        assertTrue(chats.isFinishing());
    }

    @Test
    public void anotherSigningKeyStillRedirects() {
        Activity chats = Robolectric.buildActivity(Activity.class).setup().get();
        // MessengerCardForTests deliberately installs com.facebook.orca with another signature.
        MessengerCardForTests.install(true);
        Settings.OPEN_CHATS_IN_MESSENGER.save(true);

        assertTrue(MessengerRedirect.hideOrRedirect(chats));
        Intent started = shadowOf(chats).getNextStartedActivity();
        assertNotNull(started);
        assertEquals("com.facebook.orca", started.getPackage());
    }

    @Test
    public void missingMessengerLeavesFacebookChatsInPlace() {
        Activity chats = Robolectric.buildActivity(Activity.class).setup().get();
        Settings.OPEN_CHATS_IN_MESSENGER.save(true);
        Settings.HIDE_GET_MESSENGER_CARD.save(false);

        assertFalse(MessengerRedirect.hideOrRedirect(chats));
        assertNull(shadowOf(chats).getNextStartedActivity());
        assertFalse(chats.isFinishing());
    }

    @Test
    public void switchOffCanUseFacebooksOwnChatsEvenWithMessengerInstalled() {
        Activity chats = Robolectric.buildActivity(Activity.class).setup().get();
        MessengerCardForTests.install(true);
        Settings.OPEN_CHATS_IN_MESSENGER.save(false);
        Settings.HIDE_GET_MESSENGER_CARD.save(false);

        assertFalse(MessengerRedirect.hideOrRedirect(chats));
        assertNull(shadowOf(chats).getNextStartedActivity());
        assertFalse(chats.isFinishing());
    }

    @Test
    public void pauseDisablesTheRedirectAndUsesFacebooksOwnPath() {
        Activity chats = Robolectric.buildActivity(Activity.class).setup().get();
        MessengerCardForTests.install(true);
        Settings.OPEN_CHATS_IN_MESSENGER.save(true);
        Settings.HIDE_GET_MESSENGER_CARD.save(false);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);

        assertFalse(MessengerRedirect.hideOrRedirect(chats));
        assertNull(shadowOf(chats).getNextStartedActivity());
        assertFalse(chats.isFinishing());
    }
}
