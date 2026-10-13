package app.hushmessenger.extension;

import android.content.Context;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class ReadReceiptsTest {
    private static final String KEY = "hide_read_receipts";

    @Before public void reset() {
        Context context = RuntimeEnvironment.getApplication();
        Settings.initialize(context);
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        CrashGuard.resetForTests();
        CrashGuard.onProcessStart(context);
        Settings.lastSentAt = 0;
    }

    @Test public void anOpenChatSkipsItsSeenJobOnlyWhileTheSwitchIsInEffect() {
        assertFalse(Settings.hideChatSeen());
        Settings.preferences.edit().putBoolean(KEY, true).commit();
        assertTrue(Settings.hideChatSeen());
        assertTrue(Settings.lastActive(KEY) > 0);
        Settings.preferences.edit().putBoolean("paused", true).commit();
        assertFalse(Settings.hideChatSeen());
    }

    @Test public void sendingSomethingLetsTheChatGoReadForAFewSeconds() {
        Settings.preferences.edit().putBoolean(KEY, true).commit();
        Settings.noteMessageSent();
        assertFalse(Settings.hideChatSeen());
        Settings.lastSentAt = System.currentTimeMillis() - Settings.REPLY_READ_MS + 1_000;
        assertFalse(Settings.hideChatSeen());
        Settings.lastSentAt = System.currentTimeMillis() - Settings.REPLY_READ_MS;
        assertTrue(Settings.hideChatSeen());
    }

    @Test public void aClockThatWentBackwardsDoesNotOpenTheWindow() {
        Settings.preferences.edit().putBoolean(KEY, true).commit();
        Settings.lastSentAt = System.currentTimeMillis() + 60_000;
        assertTrue(Settings.hideChatSeen());
    }
}
