package app.hushmessenger.extension;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class AutoReplyTest {
    private static final String CHAT = "ADVANCED_CRYPTO_ONE_TO_ONE:410775001";
    private static final String GROUP = "ADVANCED_CRYPTO_GROUP:5551";
    private static final String REPLY_ACTION = "app.hushmessenger.test.REPLY";
    private final long[] now = {1_000_000};
    private Context context;
    private NotificationManager manager;

    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        Settings.initialize(context);
        Settings.preferences.edit().clear().commit();
        Settings.activeAt.clear();
        Settings.hookErrors.clear();
        CrashGuard.resetForTests();
        CrashGuard.onProcessStart(context);
        AutoReply.lastReply.clear();
        AutoReply.clock = () -> now[0];
        Settings.preferences.edit().putBoolean(AutoReply.KEY, true).commit();
        manager = context.getSystemService(NotificationManager.class);
    }

    @After public void tidy() {
        manager.cancelAll();
        AutoReply.clock = System::currentTimeMillis;
    }

    private void replies(boolean groups, String... whenThenReply) {
        List<AutoReply.Rule> rules = new ArrayList<>();
        for (int i = 0; i < whenThenReply.length; i += 2) rules.add(new AutoReply.Rule(whenThenReply[i], whenThenReply[i + 1]));
        AutoReply.save(Settings.preferences, rules, groups);
    }

    /** A chat notification the way Messenger posts one: tagged with the chat's key, with a Reply action. */
    private void notification(String thread) {
        RemoteInput input = new RemoteInput.Builder("reply_text").setLabel("Reply").build();
        PendingIntent intent = PendingIntent.getBroadcast(context, 0, new Intent(REPLY_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        Notification.Action reply = new Notification.Action.Builder(null, "Reply", intent).addRemoteInput(input).build();
        Notification.Action read = new Notification.Action.Builder(null, "Mark as read",
            PendingIntent.getBroadcast(context, 1, new Intent("app.hushmessenger.test.READ"), PendingIntent.FLAG_IMMUTABLE)).build();
        manager.notify(thread, thread.hashCode(), new Notification.Builder(context, "chats")
            .setSmallIcon(android.R.drawable.sym_def_app_icon).setContentTitle("Ana").addAction(read).addAction(reply).build());
    }

    /** What the Reply action received, or null when nothing was sent. */
    private String sent() {
        List<Intent> broadcasts = shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents();
        String text = null;
        for (Intent intent : broadcasts) {
            if (!REPLY_ACTION.equals(intent.getAction())) continue;
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            text = results == null ? null : String.valueOf(results.getCharSequence("reply_text"));
        }
        return text;
    }

    @Test public void matchesWholeWordsIgnoringCase() {
        replies(false, "hi, hello", "Hey!");
        List<AutoReply.Rule> rules = AutoReply.rules(Settings.preferences);
        assertEquals("Hey!", AutoReply.match(rules, "HI there").reply);
        assertEquals("Hey!", AutoReply.match(rules, "well, hello?").reply);
        assertNull(AutoReply.match(rules, "this is not a greeting"));
        assertNull(AutoReply.match(rules, "chicken"));
    }

    @Test public void anEmptyWordsBoxAnswersAnyMessageAndTheFirstMatchWins() {
        replies(false, "lunch", "At noon", "", "Away right now");
        List<AutoReply.Rule> rules = AutoReply.rules(Settings.preferences);
        assertEquals("At noon", AutoReply.match(rules, "lunch today?").reply);
        assertEquals("Away right now", AutoReply.match(rules, "anything else").reply);
    }

    @Test public void keepsRepliesWithTextUpToTheLimit() {
        List<AutoReply.Rule> rules = new ArrayList<>();
        rules.add(new AutoReply.Rule("no reply here", "  "));
        for (int i = 0; i < AutoReply.MAX_RULES + 3; i++) rules.add(new AutoReply.Rule("w" + i, "r" + i));
        assertEquals(AutoReply.MAX_RULES, AutoReply.save(Settings.preferences, rules, true));
        List<AutoReply.Rule> saved = AutoReply.rules(Settings.preferences);
        assertEquals(AutoReply.MAX_RULES, saved.size());
        assertEquals("w0", saved.get(0).when);
        assertTrue(Settings.preferences.getBoolean(AutoReply.GROUPS, false));
    }

    @Test public void aDamagedValueReadsAsNoRepliesAndTheHookSkipsTheMessage() {
        Settings.preferences.edit().putString(AutoReply.RULES, "{not json").commit();
        assertTrue(AutoReply.rules(Settings.preferences).isEmpty());
        assertFalse(AutoReply.wanted());
        replies(false, "", "Away");
        assertTrue(AutoReply.wanted());
        Settings.preferences.edit().putBoolean(AutoReply.KEY, false).commit();
        assertFalse(AutoReply.wanted());
    }

    @Test public void answersThroughTheNotificationsReplyAction() {
        replies(false, "free", "Busy, back soon");
        notification(CHAT);
        assertTrue(AutoReply.answer("are you free tonight?", CHAT, false));
        assertEquals("Busy, back soon", sent());
        assertTrue(Settings.lastActive(AutoReply.KEY) > 0);
    }

    @Test public void handsTheMessageLogTheSenderBeforeTheReplyTakesTheNotificationDown() {
        Settings.preferences.edit().putBoolean(MessageLog.KEY, true).commit();
        MessageLog.forgetCaptures();
        replies(false, "", "Away");
        notification(CHAT);
        assertTrue(AutoReply.answer("are you there?", CHAT, false));
        manager.cancelAll();
        assertEquals("Ana", MessageLog.named(context, new MessageLog.Entry(1, CHAT, "are you there?")).sender);
    }

    @Test public void answersEachChatAtMostOnceEveryFiveMinutes() {
        replies(false, "", "Away");
        notification(CHAT);
        assertTrue(AutoReply.answer("one", CHAT, false));
        now[0] += AutoReply.COOLDOWN_MS - 1;
        assertFalse(AutoReply.answer("two", CHAT, false));
        now[0] += 1;
        assertTrue(AutoReply.answer("three", CHAT, false));
    }

    @Test public void leavesGroupChatsAloneUnlessAllowed() {
        replies(false, "", "Away");
        notification(GROUP);
        assertFalse(AutoReply.answer("hello all", GROUP, false));
        assertNull(sent());
        replies(true, "", "Away");
        assertTrue(AutoReply.answer("hello all", GROUP, false));
        assertTrue(AutoReply.oneToOne("ONE_TO_ONE:1:2"));
        assertFalse(AutoReply.oneToOne("GROUP:3"));
    }

    @Test public void neverAnswersAMessageThatIsOneOfItsOwnReplies() {
        replies(false, "", "Away right now");
        notification(CHAT);
        assertFalse(AutoReply.answer(" away right now ", CHAT, false));
        assertNull(sent());
    }

    @Test public void noNotificationMeansNoReplyAndNoCooldown() {
        replies(false, "", "Away");
        assertFalse(AutoReply.answer("hello", CHAT, false));
        assertFalse(AutoReply.lastReply.containsKey(CHAT));
        notification(CHAT);
        assertTrue(AutoReply.answer("hello", CHAT, false));
    }

    @Test public void pauseAndOffSendNothing() {
        replies(false, "", "Away");
        notification(CHAT);
        Settings.preferences.edit().putBoolean("paused", true).commit();
        assertFalse(AutoReply.answer("hello", CHAT, false));
        Settings.preferences.edit().putBoolean("paused", false).putBoolean(AutoReply.KEY, false).commit();
        assertFalse(AutoReply.answer("hello", CHAT, false));
        assertNull(sent());
    }
}
