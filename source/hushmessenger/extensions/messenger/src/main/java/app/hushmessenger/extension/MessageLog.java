package app.hushmessenger.extension;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * A local log of the messages that reached a notification, kept so an unsend can't take them back. It's the only way to
 * cover end-to-end encrypted chats, which "Keep unsent messages" can't reach. The file and its key stay on the phone,
 * and the key lives in the Android keystore, so nothing here leaves the device and clearing the log throws the key away
 * with it. Writing happens off Messenger's notification thread, and any failure is swallowed into the control's
 * hook-error record the way the other controls handle theirs.
 *
 * Retention: entries older than 30 days are removed from the file itself, by a check shortly after Messenger starts and
 * every six hours after, whether or not the switch is on. Turning the switch off, pausing or safe mode stop new entries
 * but keep the ones already stored until they expire or the log is cleared.
 */
public final class MessageLog {
    private MessageLog() {}

    static final String KEY = "message_log";
    static final String FILE = "hush_message_log.bin";
    static final String DAMAGED = FILE + ".damaged";
    static final String KEY_ALIAS = "hushmessenger_message_log";
    /** Keep the newest of these, or 30 days, whichever is smaller. */
    static final int MAX_ENTRIES = 500;
    static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;
    /** One message keeps at most this much text, and the whole log at most this many bytes before encryption. */
    static final int MAX_TEXT_CHARS = 8_000;
    static final int MAX_THREAD_CHARS = 256;
    static final int MAX_PLAIN_BYTES = 2 * 1024 * 1024;
    /** A file bigger than this was never written by this log, so it's set aside without reading it. */
    static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    static final long EXPIRY_DELAY_MS = 30_000;
    static final long EXPIRY_PERIOD_MS = 6L * 60 * 60 * 1000;
    /** How long a capture waits for its notification before it's written. */
    static final long NAME_DELAY_MS = 2_000;
    static final int MAX_NAME_CHARS = 200;
    /**
     * Messenger can build one message's notification twice, from the push and again from the sync, so the same text in
     * the same chat this soon after is that message again.
     */
    static final long DUPLICATE_WINDOW_MS = 60_000;
    /** Names auto reply read off a notification, kept for this many captures at most. */
    private static final int MAX_NOTED = 50;
    /** A clock moved back can leave entries in the future, which would never expire; they count from now instead. */
    private static final long FUTURE_SLACK_MS = 24L * 60 * 60 * 1000;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final ScheduledExecutorService WRITER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HushMessageLog");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean EXPIRY_SCHEDULED = new AtomicBoolean();
    /** Guards the file and the key. Taken before PENDING whenever both are needed. */
    private static final Object LOCK = new Object();
    /** Messages captured but not yet written, oldest first. Guarded by itself. */
    private static final ArrayDeque<Entry> PENDING = new ArrayDeque<>();
    private static boolean drainScheduled;
    /** When each recent capture came in, by chat and text, oldest first. Guarded by PENDING. */
    private static final LinkedHashMap<String, Long> RECENT = new LinkedHashMap<>();
    /** Sender and group name auto reply read before answering, by chat and text. Guarded by itself. */
    private static final LinkedHashMap<String, String[]> NOTED = new LinkedHashMap<>();

    /**
     * One stored message. The thread is Messenger's key for the chat, used only to group and match entries. The sender
     * and the group's name come from the notification that showed the message, and are empty when it named nobody.
     */
    public static final class Entry {
        public final long time;
        public final String thread;
        public final String text;
        public final String sender;
        public final String chat;
        Entry(long time, String thread, String text) {
            this(time, thread, text, "", "");
        }
        Entry(long time, String thread, String text, String sender, String chat) {
            this.time = time;
            this.thread = thread;
            this.text = text;
            this.sender = sender == null ? "" : sender;
            this.chat = chat == null ? "" : chat;
        }
    }

    /**
     * Turns plaintext into a stored blob and back. The production path uses an AES-GCM key in the Android keystore. Tests
     * swap in a software key, since Robolectric has no keystore. The blob is the 12-byte GCM nonce followed by the
     * ciphertext and tag.
     */
    interface Vault {
        byte[] seal(byte[] plain) throws Exception;
        byte[] open(byte[] blob) throws Exception;
        void forget() throws Exception;
    }

    static volatile Vault vault;

    static Vault vault() {
        Vault current = vault;
        if (current == null) {
            synchronized (LOCK) {
                if (vault == null) vault = new KeystoreVault();
                current = vault;
            }
        }
        return current;
    }

    /**
     * The hook calls this after it reads the message. It only adds the message to a short in-memory queue and returns;
     * one write on the log's own thread takes everything queued, so a burst of notifications costs one rewrite.
     */
    static void record(String text, String thread) {
        // The write waits a moment so Messenger has posted the notification the sender's name is read from.
        if (queue(text, thread)) WRITER.schedule(MessageLog::drain, NAME_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /** Queues and writes on the calling thread. Tests use it to skip the background thread. */
    static void storeNow(String text, String thread) {
        queue(text, thread);
        drain();
    }

    /** Returns true when no write is scheduled for the queue yet. */
    static boolean queue(String text, String thread) {
        if (text == null) return false;
        long now = System.currentTimeMillis();
        Entry entry = new Entry(now, clip(thread == null ? "" : thread, MAX_THREAD_CHARS), clip(text, MAX_TEXT_CHARS));
        synchronized (PENDING) {
            if (repeated(entry.thread, entry.text, now)) return false;
            if (PENDING.size() >= MAX_ENTRIES) PENDING.removeFirst();
            PENDING.addLast(entry);
            if (drainScheduled) return false;
            drainScheduled = true;
            return true;
        }
    }

    /**
     * Whether this chat had the same text captured within DUPLICATE_WINDOW_MS, which means it's the second notification
     * Messenger built for one message. Otherwise remembers this capture and returns false. Keeps its record across Clear,
     * so the second half of a message that came in before a Clear can't land after it.
     */
    static boolean repeated(String thread, String text, long now) {
        synchronized (PENDING) {
            // A clock moved back leaves times in the future, and those can't vouch for anything either.
            RECENT.values().removeIf(at -> now - at > DUPLICATE_WINDOW_MS || at > now);
            String key = thread + '\u0000' + text;
            if (RECENT.containsKey(key)) return true;
            RECENT.put(key, now);
            while (RECENT.size() > MAX_ENTRIES) {
                Iterator<String> oldest = RECENT.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            return false;
        }
    }

    /** Forgets recent captures and noted names. Tests start each case from a clean record with it. */
    static void forgetCaptures() {
        synchronized (PENDING) {
            RECENT.clear();
        }
        synchronized (NOTED) {
            NOTED.clear();
        }
    }

    /**
     * Auto reply answers through the chat's notification, and Messenger takes that notification down once the reply goes,
     * often before the log reads the sender's name off it. Auto reply hands the names over just before it answers.
     */
    static void noteNames(String thread, String text, android.app.Notification notification) {
        if (thread == null || text == null || notification == null || !Settings.wouldUse(KEY)) return;
        String sender = ChatNotifications.sender(notification, text);
        String chat = ChatNotifications.conversation(notification);
        if (sender == null && chat == null) return;
        String key = clip(thread, MAX_THREAD_CHARS) + '\u0000' + clip(text, MAX_TEXT_CHARS);
        synchronized (NOTED) {
            NOTED.put(key, new String[] {sender, chat});
            while (NOTED.size() > MAX_NOTED) {
                Iterator<Map.Entry<String, String[]>> oldest = NOTED.entrySet().iterator();
                oldest.next();
                oldest.remove();
            }
        }
    }

    static void drain() {
        // Taking the queue under the file lock means a Clear either runs before this (and empties the queue) or after
        // it (and deletes what this wrote). A message that arrived before Clear can't land after it.
        synchronized (LOCK) {
            List<Entry> batch;
            synchronized (PENDING) {
                batch = new ArrayList<>(PENDING);
                PENDING.clear();
                drainScheduled = false;
            }
            if (batch.isEmpty()) return;
            // The switch may have been turned off, paused or put in safe mode between the notification and this write.
            if (!Settings.wouldUse(KEY)) return;
            Context context = Settings.appContext;
            if (context == null) return;
            try {
                List<Entry> entries = load(context);
                for (Entry entry : batch) entries.add(named(context, entry));
                prune(entries, System.currentTimeMillis());
                save(context, entries);
                Settings.activeAt.put(KEY, System.currentTimeMillis());
            } catch (Throwable failure) {
                // Back in front of the queue, so the next notification's write tries them again. The cap still holds.
                synchronized (PENDING) {
                    for (int i = batch.size() - 1; i >= 0; i--) PENDING.addFirst(batch.get(i));
                    while (PENDING.size() > MAX_ENTRIES) PENDING.removeFirst();
                }
                Settings.hookFailedPrivately(KEY, "Can't keep the message log", failure);
            }
        }
    }

    /**
     * The entry with who sent it and the group's name, from the names auto reply noted before answering, else from the
     * notification Messenger put up for that chat. Unchanged when there's neither (the chat was open, say) or the
     * notification can't be read.
     */
    static Entry named(Context context, Entry entry) {
        if (!entry.sender.isEmpty()) return entry;
        String[] noted;
        synchronized (NOTED) {
            noted = NOTED.remove(entry.thread + '\u0000' + entry.text);
        }
        try {
            String sender;
            String chat;
            if (noted != null) {
                sender = noted[0];
                chat = noted[1];
            } else {
                android.service.notification.StatusBarNotification shown = ChatNotifications.find(context, entry.thread);
                if (shown == null) return entry;
                sender = ChatNotifications.sender(shown.getNotification(), entry.text);
                chat = ChatNotifications.conversation(shown.getNotification());
            }
            if (sender == null && chat == null) return entry;
            return new Entry(entry.time, entry.thread, entry.text, clip(sender == null ? "" : sender, MAX_NAME_CHARS),
                clip(chat == null || chat.equals(sender) ? "" : chat, MAX_NAME_CHARS));
        } catch (RuntimeException unreadable) {
            return entry;
        }
    }

    /** Newest first, for the viewer. Reads and decrypts off the main thread, and removes expired entries from disk. */
    static List<Entry> entries() {
        Context context = Settings.appContext;
        if (context == null) return new ArrayList<>();
        try {
            synchronized (LOCK) {
                List<Entry> entries = load(context);
                if (prune(entries, System.currentTimeMillis()) || repairedOnRead) save(context, entries);
                List<Entry> newestFirst = new ArrayList<>(entries.size());
                for (int i = entries.size() - 1; i >= 0; i--) newestFirst.add(entries.get(i));
                return newestFirst;
            }
        } catch (Throwable failure) {
            Settings.hookFailedPrivately(KEY, "Can't read the message log", failure);
            return new ArrayList<>();
        }
    }

    /** Starts the expiry check once per process. Settings calls it at startup when the control is installed. */
    static void scheduleExpiry() {
        if (EXPIRY_SCHEDULED.compareAndSet(false, true)) {
            WRITER.scheduleWithFixedDelay(MessageLog::expireNow, EXPIRY_DELAY_MS, EXPIRY_PERIOD_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Rewrites the file without its expired entries. Runs with the switch off too, since Off keeps but doesn't extend. */
    static void expireNow() {
        Context context = Settings.appContext;
        if (context == null) return;
        try {
            synchronized (LOCK) {
                if (!new File(context.getFilesDir(), FILE).exists()) return;
                List<Entry> entries = load(context);
                if (prune(entries, System.currentTimeMillis()) || repairedOnRead) save(context, entries);
            }
        } catch (Throwable failure) {
            // Caught here because a periodic task that throws is never run again.
            Settings.hookFailedPrivately(KEY, "Can't expire old messages in the log", failure);
        }
    }

    /**
     * A repatch without this control leaves the file and its key behind, and the settings screen can't clear them any
     * more. Settings calls this at startup then, so the log doesn't outlive the control. Returns the clear, or null.
     */
    static java.util.concurrent.Future<?> dropLeftovers(Context context) {
        for (String name : new String[] {FILE, FILE + ".tmp", DAMAGED}) {
            if (new File(context.getFilesDir(), name).exists()) return WRITER.submit(MessageLog::clear);
        }
        return null;
    }

    /** Deletes the file, anything set aside and the key, and drops messages still waiting to be written. */
    static void clear() {
        Context context = Settings.appContext;
        synchronized (LOCK) {
            synchronized (PENDING) {
                PENDING.clear();
            }
            if (context != null) {
                for (String name : new String[] {FILE, FILE + ".tmp", DAMAGED}) {
                    File file = new File(context.getFilesDir(), name);
                    if (file.exists() && !file.delete()) {
                        Settings.hookFailedPrivately(KEY, "Can't delete the message log",
                            new IOException("delete failed: " + name));
                    }
                }
            }
            try {
                vault().forget();
            } catch (Throwable failure) {
                Settings.hookFailedPrivately(KEY, "Can't drop the message log key", failure);
            }
        }
    }

    /**
     * Drops expired entries, then the oldest until the count and the plaintext size fit, so the newest always stay.
     * Returns whether anything was dropped.
     */
    static boolean prune(List<Entry> entries, long now) {
        int before = entries.size();
        long cutoff = now - MAX_AGE_MS;
        entries.removeIf(entry -> entry.time < cutoff);
        long bytes = 0;
        int first = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            bytes += line(entries.get(i)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_PLAIN_BYTES || entries.size() - i > MAX_ENTRIES) {
                first = i + 1;
                break;
            }
        }
        if (first > 0) entries.subList(0, first).clear();
        return entries.size() != before;
    }

    /** The log's file content could not have come from this log, or no longer opens with its key. */
    static final class Damaged extends Exception {
        Damaged(String why, Throwable cause) {
            super(why, cause);
        }
    }

    /**
     * Reads the log. A file that can't be the log's own, or that its key no longer opens, is moved aside (replacing any
     * earlier one) so the log keeps working instead of failing on every message. Other failures, like a busy keystore,
     * are thrown and the file stays where it is.
     */
    private static List<Entry> load(Context context) throws Exception {
        File file = new File(context.getFilesDir(), FILE);
        try {
            return read(file);
        } catch (Damaged damaged) {
            File aside = new File(context.getFilesDir(), DAMAGED);
            if (aside.exists()) aside.delete();
            if (!file.renameTo(aside)) file.delete();
            Settings.hookFailedPrivately(KEY, "Set aside a message log it can't read", damaged);
            return new ArrayList<>();
        }
    }

    /**
     * Set by {@link #read} when a stored time had to be moved. The callers hold {@link #LOCK}, and they write the file
     * back even when nothing expired, or a time the clock got wrong would read as fresh on every run and never expire.
     */
    static boolean repairedOnRead;

    static List<Entry> read(File file) throws Exception {
        List<Entry> entries = new ArrayList<>();
        repairedOnRead = false;
        if (!file.exists()) return entries;
        if (file.length() > MAX_FILE_BYTES) throw new Damaged("too large: " + file.length(), null);
        byte[] blob = readAll(file);
        if (blob.length == 0) return entries;
        // Shorter than an IV and a tag never reaches the tag check, and the keystore reports it as a size error instead.
        if (blob.length < IV_BYTES + TAG_BITS / 8) throw new Damaged("too short: " + blob.length, null);
        String plain;
        try {
            plain = new String(vault().open(blob), StandardCharsets.UTF_8);
        } catch (AEADBadTagException wrongKey) {
            throw new Damaged("doesn't open", wrongKey);
        }
        long latest = System.currentTimeMillis() + FUTURE_SLACK_MS;
        for (String line : plain.split("\n", -1)) {
            if (line.isEmpty()) continue;
            // Logs written before names were kept have three columns.
            String[] parts = line.split("\t", -1);
            if (parts.length < 3) continue;
            long time;
            try {
                time = Long.parseLong(parts[0]);
            } catch (NumberFormatException malformed) {
                continue;
            }
            if (time > latest) {
                time = System.currentTimeMillis();
                repairedOnRead = true;
            }
            entries.add(new Entry(time, clip(unescape(parts[1]), MAX_THREAD_CHARS), clip(unescape(parts[2]), MAX_TEXT_CHARS),
                parts.length > 3 ? clip(unescape(parts[3]), MAX_NAME_CHARS) : "",
                parts.length > 4 ? clip(unescape(parts[4]), MAX_NAME_CHARS) : ""));
        }
        return entries;
    }

    /** Writes the whole log, or deletes the file when nothing is left. */
    private static void save(Context context, List<Entry> entries) throws Exception {
        if (entries.isEmpty()) {
            File file = new File(context.getFilesDir(), FILE);
            if (file.exists() && !file.delete()) throw new IOException("delete failed: " + FILE);
            return;
        }
        write(context, entries);
    }

    /**
     * Writes to a temporary file, syncs it, then moves it over the log in one step. If the move fails the old log stays
     * as it was and the write counts as failed; it never writes into the log in place.
     */
    static void write(Context context, List<Entry> entries) throws Exception {
        StringBuilder plain = new StringBuilder();
        for (Entry entry : entries) plain.append(line(entry));
        byte[] blob = vault().seal(plain.toString().getBytes(StandardCharsets.UTF_8));
        File dir = context.getFilesDir();
        File temp = new File(dir, FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(blob);
            out.getFD().sync();
        }
        try {
            // An atomic move replaces the old file on Android (rename) and on the JVM the tests run on.
            Files.move(temp.toPath(), new File(dir, FILE).toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failed) {
            temp.delete();
            throw failed;
        }
    }

    private static String line(Entry entry) {
        if (entry.sender.isEmpty() && entry.chat.isEmpty()) return entry.time + "\t" + escape(entry.thread) + "\t" + escape(entry.text) + "\n";
        return entry.time + "\t" + escape(entry.thread) + "\t" + escape(entry.text) + "\t" + escape(entry.sender) + "\t" + escape(entry.chat) + "\n";
    }

    /** Cuts long text, never between the two halves of an emoji, and marks the cut. */
    static String clip(String value, int max) {
        if (value.length() <= max) return value;
        int end = max - 1;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "\u2026";
    }

    private static byte[] readAll(File file) throws IOException, Damaged {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_FILE_BYTES) throw new Damaged("grew while reading", null);
            }
            return out.toByteArray();
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t");
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                out.append(next == 'n' ? '\n' : next == 't' ? '\t' : next);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** AES-GCM with a 256-bit key the Android keystore holds, so the key never leaves the phone. */
    static final class KeystoreVault implements Vault {
        private SecretKey key() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            KeyStore.Entry entry = store.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
            return generator.generateKey();
        }

        @Override public byte[] seal(byte[] plain) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] body = cipher.doFinal(plain);
            byte[] blob = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(body, 0, blob, iv.length, body.length);
            return blob;
        }

        @Override public byte[] open(byte[] blob) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(blob, 0, iv, 0, IV_BYTES);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
        }

        @Override public void forget() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
        }
    }
}
