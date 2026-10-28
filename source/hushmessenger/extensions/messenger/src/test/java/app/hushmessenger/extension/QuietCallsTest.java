package app.hushmessenger.extension;

import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class QuietCallsTest {
    private Application app;

    @Before public void reset() {
        app = RuntimeEnvironment.getApplication();
        Settings.initialize(app);
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        CrashGuard.resetForTests();
    }

    private Notification.Builder call(String category) {
        return new Notification.Builder(app, "messenger_orca_749_voip_incoming@test")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming call")
            .setCategory(category);
    }

    /** An incoming call the way Messenger posts it: call category and a full-screen intent. */
    private Notification ringing() {
        PendingIntent screen = PendingIntent.getActivity(app, 0, new Intent(), PendingIntent.FLAG_IMMUTABLE);
        return call(Notification.CATEGORY_CALL).setFullScreenIntent(screen, true).build();
    }

    /** A call notification with only its call type to go on. */
    private Notification typed(int callType) {
        Notification notification = call(Notification.CATEGORY_CALL).build();
        notification.extras.putInt(QuietCalls.CALL_TYPE, callType);
        return notification;
    }

    @Test public void anIncomingCallIsQuietedOnlyWhileTheSwitchIsOn() {
        Notification off = ringing();
        assertSame(off, QuietCalls.incomingNotification(off, app));
        assertNull(off.getGroup());
        assertEquals(0, Settings.lastActive("quiet_calls"));

        Settings.preferences.edit().putBoolean("quiet_calls", true).commit();
        Notification on = ringing();
        PendingIntent screen = on.fullScreenIntent;
        Notification quiet = QuietCalls.incomingNotification(on, app);
        // The same notification goes on to Messenger, in a group of its own that only its missing summary could sound.
        assertSame(on, quiet);
        assertEquals(QuietCalls.GROUP, quiet.getGroup());
        assertEquals(Notification.GROUP_ALERT_SUMMARY, quiet.getGroupAlertBehavior());
        // Android refuses a call notification with neither a full-screen intent nor a foreground service, so it stays.
        assertSame(screen, quiet.fullScreenIntent);
        assertEquals(Notification.CATEGORY_CALL, quiet.category);
        assertTrue(Settings.lastActive("quiet_calls") > 0);

        Settings.preferences.edit().putBoolean("quiet_calls", false).commit();
        Notification next = ringing();
        assertNull("The next call after switching off rings", QuietCalls.incomingNotification(next, app).getGroup());
    }

    @Test public void onlyIncomingCallsAreTouched() {
        Settings.preferences.edit().putBoolean("quiet_calls", true).commit();
        assertEquals("A call type of incoming is enough", QuietCalls.GROUP, QuietCalls.incomingNotification(typed(QuietCalls.INCOMING), app).getGroup());
        Settings.activeAt.clear();

        PendingIntent screen = PendingIntent.getActivity(app, 0, new Intent(), PendingIntent.FLAG_IMMUTABLE);
        Notification[] others = {
            typed(2),
            call(Notification.CATEGORY_CALL).build(),
            call(Notification.CATEGORY_MESSAGE).setFullScreenIntent(screen, true).build(),
            call(null).build(),
        };
        for (Notification other : others) {
            assertSame(other, QuietCalls.incomingNotification(other, app));
            assertNull(String.valueOf(other.category), other.getGroup());
        }
        // The switch only counts as used when a call actually came in.
        assertEquals(0, Settings.lastActive("quiet_calls"));
        assertNull(QuietCalls.incomingNotification(null, app));
        Notification noContext = ringing();
        assertSame(noContext, QuietCalls.incomingNotification(noContext, null));
        assertNull(noContext.getGroup());
    }

    @Test public void offPauseSafeModeAndAMissingControlLetCallsRing() {
        for (String state : new String[] {"off", "paused", "safe", "missing"}) {
            Settings.initialize(app);
            Settings.preferences.edit().clear().putBoolean("quiet_calls", !state.equals("off"))
                .putBoolean("paused", state.equals("paused")).putBoolean("safe_mode", state.equals("safe")).commit();
            CrashGuard.resetForTests();
            CrashGuard.onProcessStart(app);
            if (state.equals("missing")) Settings.installed = Collections.emptySet();
            Settings.activeAt.clear();
            assertFalse(state, Settings.quietIncomingCalls());
            Notification notification = ringing();
            assertSame(state, notification, QuietCalls.incomingNotification(notification, app));
            assertNull(state, notification.getGroup());
            assertEquals(state, 0, Settings.lastActive("quiet_calls"));
        }
    }

    @Test public void aSwitchThatCantBeReadLetsTheCallRing() {
        // A stored value of the wrong type makes reading the switch throw, which must never reach Messenger's call service.
        Settings.preferences.edit().putString("quiet_calls", "on").commit();
        Settings.hookErrors.clear();
        Notification notification = ringing();
        assertSame(notification, QuietCalls.incomingNotification(notification, app));
        assertNull(notification.getGroup());
        assertTrue(Settings.hookErrors.containsKey("quiet_calls"));
    }

    @Test public void theSwitchSitsUnderConversationsStartsOffIsExperimentalAndStaysOutOfTheQuickSetups() {
        assertTrue(Settings.installed.contains("quiet_calls"));
        String[] row = Arrays.stream(SettingsActivity.CONTROLS).filter(c -> c[0].equals("quiet_calls")).findFirst().orElseThrow();
        assertEquals("Quiet incoming calls", row[1]);
        assertEquals("conversations", row[3]);
        assertTrue(SettingsActivity.EXPERIMENTAL.contains("quiet_calls"));
        assertFalse(Settings.preferences.contains("quiet_calls"));
        assertFalse(Settings.wouldUse("quiet_calls"));
        // A call that doesn't ring is easy to miss, so no one-tap setup turns it on.
        assertFalse(QuickSetups.PRIVACY.contains("quiet_calls"));
        assertFalse(QuickSetups.QUIET.contains("quiet_calls"));
    }
}
