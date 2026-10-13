/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

import com.facebook.messaging.business.bizrtc.model.PageMessageNotification;
import com.facebook.messaging.notify.type.NewMessageNotification;
import com.facebook.messaging.rtc.callstatus.notification.MissedCallNotification;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The hooks first in Facebook's VOIP push handlers, its ring parser and its chat dispatcher: while
 * Leave calls and chats to Messenger is on and Messenger is installed and enabled, Facebook drops
 * the call push, parses the ring to nothing and doesn't post Messenger's own kinds of chat
 * notification. Every other time, Facebook rings and notifies as it always did.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MessengerHandoffTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String PREFIX = FamilyNames.MESSENGER_ICON + ": ";

    @Before
    public void startClean() {
        MessengerIconForTests.uninstall();
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER.resetToDefault();
        MessengerIconForTests.uninstall();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(PREFIX)) return line;
        }
        return null;
    }

    /** All three hooks, as one incoming call and one message reach Facebook. */
    private static boolean[] callAndMessage() {
        return new boolean[]{
                MessengerHandoff.dropCallPush(),
                MessengerHandoff.dropRing(),
                MessengerHandoff.dropChat(new NewMessageNotification()),
        };
    }

    private static void assertNoneDropped(String why, boolean[] dropped) {
        for (int hook = 0; hook < dropped.length; hook++) {
            assertFalse(why + ": hook " + hook + " dropped Facebook's", dropped[hook]);
        }
    }

    private static void assertAllDropped(String why, boolean[] dropped) {
        for (int hook = 0; hook < dropped.length; hook++) {
            assertTrue(why + ": hook " + hook + " left it to Facebook", dropped[hook]);
        }
    }

    /** Off until it's turned on: with Messenger right there, Facebook still rings and notifies. */
    @Test
    public void theSwitchStartsOffAndFacebookRingsAndNotifies() {
        assertFalse("the switch starts on", Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER.get());
        MessengerCardForTests.install(true);
        assertNoneDropped("off", callAndMessage());
        assertEquals(PREFIX + "invoked 3, 0 found, 0 missing", statusLine());
    }

    /**
     * On, with Messenger installed and enabled, Facebook drops the call push, has no ring for the
     * call and doesn't post the message or the missed call. Each is counted.
     */
    @Test
    public void onWithMessengerFacebookLeavesCallsAndChatsToIt() {
        Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER.save(true);
        MessengerCardForTests.install(true);
        assertAllDropped("on with Messenger", callAndMessage());
        assertTrue(MessengerHandoff.dropChat(new MissedCallNotification()));
        assertEquals(PREFIX + "invoked 4, 0 found, 0 missing. Counted: "
                + MessengerHandoff.CALL_PUSH_DROPPED + " 1, "
                + MessengerHandoff.RING_DROPPED + " 1, "
                + MessengerHandoff.CHAT_DROPPED + " 2", statusLine());
    }

    /** Without Messenger nothing else rings, so Facebook does, and the report says why. */
    @Test
    public void withoutMessengerFacebookRingsAndNotifies() {
        Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER.save(true);
        assertNoneDropped("no Messenger", callAndMessage());
        assertEquals(PREFIX + "invoked 3, 0 found, 0 missing. Counted: "
                + MessengerHandoff.NO_MESSENGER + " 3", statusLine());
    }

    /** A Messenger turned off in Android's settings gets no pushes, so it counts as none. */
    @Test
    public void aDisabledMessengerIsNoMessenger() {
        Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER.save(true);
        MessengerCardForTests.install(false);
        assertNoneDropped("Messenger disabled", callAndMessage());
    }

    /**
     * Messenger is looked up on every call, never kept: uninstalled or disabled a minute ago, the
     * next call rings in Facebook, and it goes back to Messenger as soon as Messenger is back.
     */
    @Test
    public void messengerGoingAwayGivesTheNextCallBackToFacebook() {
        Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER.save(true);
        MessengerCardForTests.install(true);
        assertTrue(MessengerHandoff.dropRing());
        MessengerCardForTests.uninstall();
        assertFalse("an uninstalled Messenger still took the ring", MessengerHandoff.dropRing());
        MessengerCardForTests.install(false);
        assertFalse("a disabled Messenger still took the ring", MessengerHandoff.dropRing());
        MessengerCardForTests.install(true);
        assertTrue(MessengerHandoff.dropRing());
    }

    /** The switch is read on every call, so turning it off gives the next call back with no restart. */
    @Test
    public void turningTheSwitchOffAppliesToTheNextCall() {
        assertFalse("the switch asks for a restart", Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER.rebootApp);
        MessengerCardForTests.install(true);
        Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER.save(true);
        assertTrue(MessengerHandoff.dropCallPush());
        Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER.save(false);
        assertFalse(MessengerHandoff.dropCallPush());
    }

    /**
     * Only Meta's Messenger counts. A copy under another package name, HushMessenger beside Meta's
     * apps say, may not get Meta's pushes at all, so Facebook keeps ringing beside it.
     */
    @Test
    public void aMessengerUnderAnotherPackageNameDoesntCount() {
        Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER.save(true);
        MessengerIconForTests.installLauncher(MessengerIconForTests.CLONE_HOME);
        assertNoneDropped("only a copy of Messenger", callAndMessage());
    }

    /**
     * Only the notifications Messenger posts itself are dropped: a Page inbox message, anything
     * else Facebook hands its notifiers, or nothing at all still goes on, and isn't counted.
     */
    @Test
    public void onlyMessengersOwnKindsOfChatNotificationAreDropped() {
        Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER.save(true);
        MessengerCardForTests.install(true);
        assertFalse(MessengerHandoff.dropChat(new PageMessageNotification()));
        assertFalse(MessengerHandoff.dropChat(new Object()));
        assertFalse(MessengerHandoff.dropChat(null));
        assertTrue(MessengerHandoff.dropChat(new NewMessageNotification()));
        assertEquals(PREFIX + "invoked 4, 0 found, 0 missing. Counted: "
                + MessengerHandoff.CHAT_DROPPED + " 1", statusLine());
    }

    /** The kinds are matched by the whole class name, never by a part of it. */
    @Test
    public void aChatKindIsItsWholeClassName() {
        assertEquals(5, MessengerHandoff.CHAT_KINDS.size());
        for (String kind : MessengerHandoff.CHAT_KINDS) {
            assertTrue(kind, MessengerHandoff.isChatKind(kind));
            assertFalse(kind + " with more on the end", MessengerHandoff.isChatKind(kind + "2"));
            assertFalse(kind + " without its package", MessengerHandoff.isChatKind(kind.substring(kind.lastIndexOf('.') + 1)));
        }
        assertTrue(MessengerHandoff.isChatKind(NewMessageNotification.class.getName()));
        assertTrue(MessengerHandoff.isChatKind(MissedCallNotification.class.getName()));
        assertFalse(MessengerHandoff.isChatKind(PageMessageNotification.class.getName()));
        assertFalse(MessengerHandoff.isChatKind(null));
        assertFalse(MessengerHandoff.isChatKind(""));
    }

    /** Paused, by the switch or by safe mode, Facebook rings and notifies as it always did. */
    @Test
    public void pausedFacebookRingsAndNotifies() {
        Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER.save(true);
        MessengerCardForTests.install(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNoneDropped("paused", callAndMessage());
        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        assertNoneDropped("safe mode", callAndMessage());
        PauseForTests.resume();
        assertAllDropped("resumed", callAndMessage());
    }

    /**
     * A push can reach Facebook before its application's onCreate. Until the settings are ready,
     * Facebook rings and notifies, and nothing is looked up or counted.
     */
    @Test
    public void untilTheSettingsAreReadyFacebookRingsAndNotifies() {
        Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER.save(true);
        MessengerCardForTests.install(true);
        boolean[][] dropped = new boolean[2][];
        SettingsContextRule.withoutContext(() -> dropped[0] = callAndMessage());
        SettingsContextRule.beforeThePauseIsDecided(() -> dropped[1] = callAndMessage());
        assertNoneDropped("no context", dropped[0]);
        assertNoneDropped("pause undecided", dropped[1]);
        assertEquals(PREFIX + "invoked 6, 0 found, 0 missing", statusLine());
        assertAllDropped("ready", callAndMessage());
    }

    /**
     * A failure in the hook leaves the call or message to Facebook, never to nobody, and the report
     * names the hook that failed.
     */
    @Test
    public void aFailureLeavesItToFacebookAndTheReportSaysSo() {
        Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER.save(true);
        MessengerCardForTests.install(true);
        AtomicBoolean failing = new AtomicBoolean(false);
        Context app = RuntimeEnvironment.getApplication();
        Utils.setContext(new ContextWrapper(app) {
            @Override
            public PackageManager getPackageManager() {
                if (failing.get()) throw new IllegalStateException("the package manager failed");
                return super.getPackageManager();
            }
        });
        failing.set(true);
        assertNoneDropped("the package manager failed", callAndMessage());
        List<String> missing = HookStatus.missing(FamilyNames.MESSENGER_ICON);
        assertEquals(missing.toString(), 3, missing.size());
        for (String hook : new String[]{"Messenger call pushes", "Messenger rings", "Messenger chat notifications"}) {
            assertTrue(missing + " doesn't name " + hook, missing.stream().anyMatch(line ->
                    line.contains("'" + hook + "' hook (it threw " + IllegalStateException.class.getName() + ")")));
        }
        failing.set(false);
        assertAllDropped("the package manager is back", callAndMessage());
    }
}
