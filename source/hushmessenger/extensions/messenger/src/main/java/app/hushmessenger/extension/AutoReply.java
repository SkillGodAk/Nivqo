package app.hushmessenger.extension;

import android.content.Context;
import android.content.SharedPreferences;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Answers a message that matches one of your replies, through the Reply action on the notification Messenger shows for
 * it, so it goes out the same way as a reply typed into the notification shade, encrypted chats included. A message
 * that never raised a notification (its chat was open, say) gets no answer. Each chat gets at most one automatic reply
 * every five minutes, and a message that repeats one of your own replies is never answered, so two phones running
 * auto replies can't keep answering each other.
 */
public final class AutoReply {
    private AutoReply() {}

    static final String KEY = "auto_reply";
    static final String RULES = "auto_reply_rules";
    static final String GROUPS = "auto_reply_groups";
    /** Messenger posts the notification a moment after the hook sees the message. */
    static final long WAIT_MS = 1_500;
    static final long RETRY_MS = 3_000;
    static final long COOLDOWN_MS = 5L * 60 * 1000;
    static final int MAX_RULES = 20;
    static final int MAX_WHEN_CHARS = 200;
    static final int MAX_REPLY_CHARS = 1_000;

    /** One reply: [when] holds words or phrases split by commas, and an empty one answers any message. */
    static final class Rule {
        final String when;
        final String reply;
        Rule(String when, String reply) {
            this.when = when == null ? "" : when.trim();
            this.reply = reply == null ? "" : reply.trim();
        }
    }

    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HushAutoReply");
        thread.setDaemon(true);
        return thread;
    });
    /** When each chat last got an automatic reply, for the cooldown. Per process, so a restart starts fresh. */
    static final ConcurrentHashMap<String, Long> lastReply = new ConcurrentHashMap<>();
    static volatile LongSupplier clock = System::currentTimeMillis;

    /** The notification hook asks this before it reads a message: on, not paused, and at least one reply written. */
    static boolean wanted() {
        SharedPreferences prefs = Settings.preferences;
        return prefs != null && Settings.wouldUse(KEY) && !rules(prefs).isEmpty();
    }

    /** The hook hands each incoming message here and returns at once; the answer runs on its own thread. */
    static void received(String text, String thread) {
        if (text == null || thread == null || thread.isEmpty()) return;
        WORKER.schedule(() -> answer(text, thread, true), WAIT_MS, TimeUnit.MILLISECONDS);
    }

    /** Matches, checks the cooldown and sends. Returns whether a reply went out. Tests call it directly. */
    static boolean answer(String text, String thread, boolean retry) {
        try {
            SharedPreferences prefs = Settings.preferences;
            Context context = Settings.appContext;
            if (prefs == null || context == null || !Settings.wouldUse(KEY)) return false;
            if (!oneToOne(thread) && !prefs.getBoolean(GROUPS, false)) return false;
            List<Rule> rules = rules(prefs);
            if (echoesAReply(rules, text)) return false;
            Rule rule = match(rules, text);
            if (rule == null) return false;
            long now = clock.getAsLong();
            Long last = lastReply.get(thread);
            if (last != null && now - last < COOLDOWN_MS) return false;
            StatusBarNotification shown = ChatNotifications.find(context, thread);
            if (shown == null) {
                if (retry) WORKER.schedule(() -> answer(text, thread, false), RETRY_MS, TimeUnit.MILLISECONDS);
                return false;
            }
            // Taken before the send, so a second message in the same moment can't get its own reply.
            lastReply.put(thread, now);
            // The reply takes the notification down, so the message log gets its names now.
            MessageLog.noteNames(thread, text, shown.getNotification());
            if (!ChatNotifications.reply(context, shown.getNotification(), rule.reply)) {
                lastReply.remove(thread, now);
                android.util.Log.w("HushMessenger", "auto_reply: the chat's notification has no Reply action");
                return false;
            }
            Settings.enabled(KEY);
            return true;
        } catch (Throwable failure) {
            Settings.hookFailedPrivately(KEY, "Can't send the auto reply", failure);
            return false;
        }
    }

    /** One-to-one chats only, unless group chats are allowed. Messenger's key names the chat type first. */
    static boolean oneToOne(String thread) {
        int colon = thread.indexOf(':');
        return (colon < 0 ? thread : thread.substring(0, colon)).contains("ONE_TO_ONE");
    }

    /** The first reply whose words appear in [text] as whole words, ignoring case, or that answers anything. */
    static Rule match(List<Rule> rules, String text) {
        for (Rule rule : rules) {
            if (rule.reply.isEmpty()) continue;
            if (rule.when.isEmpty()) return rule;
            for (String term : rule.when.split(",")) {
                String word = term.trim();
                if (word.isEmpty()) continue;
                Pattern pattern = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                if (pattern.matcher(text).find()) return rule;
            }
        }
        return null;
    }

    /** A message that is one of our own replies came from another auto reply, so answering it would start a loop. */
    static boolean echoesAReply(List<Rule> rules, String text) {
        String said = text.trim().toLowerCase(Locale.ROOT);
        for (Rule rule : rules) if (!rule.reply.isEmpty() && rule.reply.toLowerCase(Locale.ROOT).equals(said)) return true;
        return false;
    }

    /** The saved replies, in order. A damaged value reads as none. */
    static List<Rule> rules(SharedPreferences prefs) {
        String saved = prefs.getString(RULES, null);
        if (saved == null || saved.isEmpty()) return Collections.emptyList();
        List<Rule> rules = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(saved);
            for (int i = 0; i < array.length() && rules.size() < MAX_RULES; i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                Rule rule = new Rule(item.optString("when", ""), item.optString("reply", ""));
                if (!rule.reply.isEmpty()) rules.add(rule);
            }
        } catch (JSONException damaged) {
            return Collections.emptyList();
        }
        return rules;
    }

    /** Keeps replies with text, trimmed and cut to size, at most [MAX_RULES]. Returns how many were kept. */
    static int save(SharedPreferences prefs, List<Rule> rules, boolean groups) {
        JSONArray array = new JSONArray();
        try {
            for (Rule rule : rules) {
                if (rule.reply.isEmpty()) continue;
                if (array.length() >= MAX_RULES) break;
                array.put(new JSONObject().put("when", MessageLog.clip(rule.when, MAX_WHEN_CHARS))
                    .put("reply", MessageLog.clip(rule.reply, MAX_REPLY_CHARS)));
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        prefs.edit().putString(RULES, array.toString()).putBoolean(GROUPS, groups).apply();
        return array.length();
    }
}
