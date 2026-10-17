/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the View stories anonymously patch asks before Facebook reports the stories you've viewed.
 *
 * <p>The story viewer queues each story card you view and sends the queue as one GraphQL mutation,
 * DirectSeenMutation, which is what puts you on a story's viewer list. The patch asks
 * {@link #toSend} first thing in the method that builds and sends it, handing over everything that
 * method was handed, and sends the set of card ids it answers. Nothing is built or sent on null.
 * Facebook has already counted those cards as reported in a set it keeps in memory, so a card held
 * back isn't queued again until it restarts. The switch is read at each send, so turning it off lets
 * the next batch through.
 *
 * <p>With the second switch on, the cards you tapped Mark as seen on ({@link StorySeenButton})
 * still go out: the answer is then a new set holding those cards and nothing else, and the rest
 * stay held ({@link StoryMarks}). A card a batch already held back is sent at the next send, or
 * right away through the send that held it when that is still around.
 *
 * <p>When the servers take a report, Facebook publishes the bucket's new is_bucket_seen_by_viewer
 * to every copy of it the app holds, which is what turns its ring gray. A report held back gets no
 * answer, so the patch does that publishing itself (#61). {@link #viewed} hears each card the
 * viewer queues; when it's the newest card of its bucket, the bucket was watched to the end, and
 * {@link SeenBuckets} keeps its id with that card's. At the next send, the one held back, those
 * buckets are published as watched. The servers keep sending them as unwatched, so
 * {@link #bucketMade} hears each bucket Facebook builds, and one that's kept here with the same
 * newest card is published as watched again. A newer story changes the newest card, so it shows as
 * new. Nothing about a story leaves the phone.
 *
 * <p>It fails open, like the other hooks: the switch off, a pause, settings that aren't ready yet,
 * or a failure reading the switch send the views as Facebook would, and leave the rings as Facebook
 * draws them. A failure picking out the marked cards holds the whole batch back, as the switch does
 * without marks.
 */
public final class StorySeen {
    /** The diagnostic counter route: each batch of views Facebook went to send, and the ones held back. */
    static final String ROUTE = "Story views";

    /** What a batch held back is counted under. */
    static final String HELD_BACK = "viewed stories";

    static final String SEND_HOOK = "story seen send";
    static final String MARKED_HOOK = "marked story send";

    /** The cards marked as seen, shared with the button. */
    static final StoryMarks MARKS = new StoryMarks(SystemClock::elapsedRealtime);

    /** What Hook status counts each bucket published as watched under. */
    static final String MARKED = "marked watched here";

    /** The type and field of the tree Facebook publishes for a bucket when its servers take a report. */
    static final String BUCKET_TYPE = "DirectMessageThreadBucket";
    static final String SEEN_FIELD = "is_bucket_seen_by_viewer";

    /**
     * A bucket published within this long of the last time isn't published again. Facebook builds
     * the same bucket several times over as a screen fills, and once is enough.
     */
    static final long REMARK_MS = 10_000;

    /** What a stub answers until the patch fills it, so a missing one reads as missing. */
    private static final Object UNPATCHED = new Object();

    /** What the code here needs from Facebook's story classes. The stubs below, in the app. */
    interface Stories {
        @Nullable String bucketId(Object bucket);

        /** The bucket's cards, oldest first, or null. */
        @Nullable List<?> cards(Object bucket);

        @Nullable String storyId(Object card);

        /** The bucket's own answer: whether it has a story not watched yet. */
        boolean hasUnwatched(Object bucket);

        /** Publishes the bucket as watched to every copy of it the app holds. */
        void publishWatched(Object bucket, String bucketId);
    }

    private static final Stories PATCHED = new Stories() {
        @Override public String bucketId(Object bucket) { return text(bucketIdOf(bucket)); }
        @Override public List<?> cards(Object bucket) {
            Object cards = cardsOf(bucket);
            return cards instanceof List ? (List<?>) cards : null;
        }
        @Override public String storyId(Object card) { return text(storyIdOf(card)); }
        @Override public boolean hasUnwatched(Object bucket) { return hasUnwatchedOf(bucket); }
        @Override public void publishWatched(Object bucket, String bucketId) {
            publishSeen(sessionOf(bucket), bucketId, BUCKET_TYPE, SEEN_FIELD, true);
        }
    };

    private static volatile Stories stories = PATCHED;
    private static volatile SeenBuckets store;
    /** The buckets watched to the end since the last send, until it's held back. */
    private static final List<WeakReference<Object>> finished = new ArrayList<>();
    /** When each bucket was last published, by SystemClock.elapsedRealtime. */
    private static final Map<String, Long> published = new HashMap<>();

    /**
     * One call of Facebook's sender, kept with the cards it held back, so a card marked later can
     * go out through the same sender, callback and account. The sender and the session are held
     * weakly. The callback is held as it is: Facebook makes a new one for each batch and nothing
     * else keeps it, so a weak hold lost it at the next garbage collection and a mark waited for
     * a later batch. {@link StoryMarks} keeps at most {@link StoryMarks#MAX_HELD} held cards for 24
     * hours, so these stay few.
     */
    static final class Call {
        final WeakReference<Object> sender;
        @Nullable final Object listener;
        final WeakReference<Object> session;
        @Nullable final String first;
        @Nullable final String second;
        @Nullable final String third;
        final boolean peek;

        Call(@Nullable Object sender, @Nullable Object listener, @Nullable Object session, @Nullable String first,
             @Nullable String second, @Nullable String third, boolean peek) {
            this.sender = new WeakReference<>(sender);
            this.listener = listener;
            this.session = new WeakReference<>(session);
            this.first = first;
            this.second = second;
            this.third = third;
            this.peek = peek;
        }
    }

    private StorySeen() {
    }

    /**
     * Injection point, first thing in the seen sender, handed what the sender was: the sender, its
     * callback, the account's session, its three strings, the card filters, the set of card ids and
     * whether the cards were only peeked at. Answers the set to send: [ids] itself while the switch
     * is off, paused or before the settings are ready; a new set holding only the cards marked with
     * Mark as seen, which the patch sends without the card filters; or null, and the sender returns
     * before building anything. Never throws.
     */
    @Nullable
    public static Set<?> toSend(@Nullable Object sender, @Nullable Object listener, @Nullable Object session,
                                @Nullable String first, @Nullable String second, @Nullable String third,
                                @Nullable Map<?, ?> filters, @Nullable Set<?> ids, boolean peek) {
        try {
            HookStatus.invoked(FamilyNames.STORY_SEEN);
            FeedFilterCounters.sawList(ROUTE, 1);
        } catch (Throwable ignored) {
            // Counters are optional.
        }
        try {
            if (!Utils.settingsReady() || !Settings.VIEW_STORIES_ANONYMOUSLY.get()) return ids;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, SEND_HOOK, failure);
            return ids;
        }
        Set<String> marked = null;
        try {
            if (StorySeenButton.switchedOn()) {
                marked = MARKS.choose(account(session), ids, new Call(sender, listener, session, first, second, third, peek));
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, MARKED_HOOK, failure);
            marked = null;
        }
        publishFinishedLater();
        try {
            if (marked == null) {
                FeedFilterCounters.removed(ROUTE, 1, HELD_BACK);
                Logger.printDebug(() -> "Story views: held back a batch of viewed stories");
            } else {
                Logger.printDebug(() -> "Story views: sent only the stories marked as seen");
            }
        } catch (Throwable ignored) {
            // Counters are optional.
        }
        return marked;
    }

    /**
     * Sends [card], which a batch of [account]'s held back and which has been marked since, through
     * the sender that held it, right away. The sender's hook fills the empty set it's handed with
     * the marked cards that sender held. False when that sender, its callback or the session is
     * gone, and the next send carries the card instead. Never throws.
     */
    static boolean sendHeld(@Nullable String account, @Nullable String card) {
        try {
            Call call = MARKS.heldBy(account, card);
            if (call == null) return false;
            Object sender = call.sender.get();
            Object listener = call.listener;
            Object session = call.session.get();
            if (sender == null || listener == null || session == null) return false;
            send(sender, listener, session, call.first, call.second, call.third, null, new LinkedHashSet<String>(),
                    call.peek);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, MARKED_HOOK, failure);
            return false;
        }
    }

    /**
     * The user ID of the account [session] is for, Facebook's FbUserSession: the first thing it
     * writes to a Parcel. Null for anything else.
     */
    @Nullable
    static String account(@Nullable Object session) {
        if (!(session instanceof Parcelable)) return null;
        Parcel parcel = Parcel.obtain();
        try {
            ((Parcelable) session).writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            String id = parcel.readString();
            return id == null || id.isEmpty() ? null : id;
        } catch (Throwable failure) {
            return null;
        } finally {
            parcel.recycle();
        }
    }

    // ------------------------------------------------------------------ watched on the phone (#61)

    /**
     * Injection point, first thing in the seen helper's method that queues a viewed card for the
     * next send. [peek] is the flag Facebook sends as is_story_peek_view: a glance from outside the
     * viewer, which doesn't count. Never throws.
     */
    public static void viewed(Object bucket, Object card, Object helper, boolean peek) {
        try {
            if (peek || !Utils.settingsReady() || !Settings.VIEW_STORIES_ANONYMOUSLY.get()) return;
            Stories access = stories;
            String story = access.storyId(card);
            if (story == null || !story.equals(newestStory(bucket, access))) return;
            String bucketId = access.bucketId(bucket);
            SeenBuckets seen = store();
            if (bucketId == null || seen == null) return;
            seen.watched(bucketId, story, System.currentTimeMillis());
            synchronized (finished) {
                finished.add(new WeakReference<>(bucket));
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, "story card viewed", failure);
        }
    }

    /**
     * Injection point, last thing in the constructor of Facebook's story bucket. One kept here as
     * watched is checked on the main thread, once it's built. With the switch off, and not just
     * paused, everything kept here is forgotten. Never throws.
     */
    public static void bucketMade(Object bucket) {
        try {
            if (!Utils.settingsReady()) return;
            SeenBuckets seen = store();
            if (seen == null) return;
            long now = System.currentTimeMillis();
            if (!Settings.VIEW_STORIES_ANONYMOUSLY.savedValue()) {
                if (!seen.isEmpty(now)) seen.clear();
                return;
            }
            if (!Settings.VIEW_STORIES_ANONYMOUSLY.get() || seen.isEmpty(now)) return;
            String bucketId = stories.bucketId(bucket);
            if (bucketId == null || seen.newestWatched(bucketId, now) == null) return;
            Utils.runOnMainThread(() -> publishIfStillWatched(bucket, bucketId));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, "story bucket made", failure);
        }
    }

    /**
     * The views are held back, so the buckets watched to the end since the last send go out as
     * watched, on the main thread. Never throws.
     */
    private static void publishFinishedLater() {
        try {
            List<Object> watched = new ArrayList<>();
            synchronized (finished) {
                for (WeakReference<Object> bucket : finished) {
                    Object live = bucket.get();
                    if (live != null) watched.add(live);
                }
                finished.clear();
            }
            if (!watched.isEmpty()) Utils.runOnMainThread(() -> publishFinished(watched));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, "watched story buckets", failure);
        }
    }

    private static void publishFinished(List<Object> buckets) {
        try {
            Stories access = stories;
            for (Object bucket : buckets) {
                String bucketId = access.bucketId(bucket);
                if (bucketId != null && access.hasUnwatched(bucket)) publish(bucket, bucketId, access);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, "watched story buckets", failure);
        }
    }

    /** The bucket goes out as watched when it still has the newest card it had when it was watched. */
    private static void publishIfStillWatched(Object bucket, String bucketId) {
        try {
            if (!Utils.settingsReady() || !Settings.VIEW_STORIES_ANONYMOUSLY.get()) return;
            SeenBuckets seen = store();
            String watched = seen == null ? null : seen.newestWatched(bucketId, System.currentTimeMillis());
            Stories access = stories;
            if (watched == null || !access.hasUnwatched(bucket) || !watched.equals(newestStory(bucket, access))) return;
            publish(bucket, bucketId, access);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_SEEN, "watched story bucket", failure);
        }
    }

    private static void publish(Object bucket, String bucketId, Stories access) {
        long now = SystemClock.elapsedRealtime();
        synchronized (published) {
            Long last = published.get(bucketId);
            if (last != null && now - last < REMARK_MS) return;
            published.put(bucketId, now);
        }
        access.publishWatched(bucket, bucketId);
        HookStatus.counted(FamilyNames.STORY_SEEN, MARKED);
        Logger.printDebug(() -> "Story views: marked a story bucket watched on this phone");
    }

    /** The id of the bucket's newest card, the last of its cards, or null. */
    @Nullable
    private static String newestStory(Object bucket, Stories access) {
        List<?> cards = access.cards(bucket);
        if (cards == null || cards.isEmpty()) return null;
        return access.storyId(cards.get(cards.size() - 1));
    }

    @Nullable
    private static SeenBuckets store() {
        SeenBuckets seen = store;
        if (seen != null) return seen;
        Context context = Utils.getContext();
        if (context == null) return null;
        synchronized (StorySeen.class) {
            if (store == null) {
                store = new SeenBuckets(context.getSharedPreferences(SeenBuckets.FILE, Context.MODE_PRIVATE));
            }
            return store;
        }
    }

    @Nullable
    private static String text(Object value) {
        return value instanceof String ? (String) value : null;
    }

    static void useForTests(@Nullable Stories access) {
        stories = access == null ? PATCHED : access;
        store = null;
        synchronized (finished) {
            finished.clear();
        }
        synchronized (published) {
            published.clear();
        }
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: hands [ids] to Facebook's sender [sender] with the rest of its arguments. */
    public static void send(Object sender, Object listener, Object session, @Nullable String first,
                            @Nullable String second, @Nullable String third, @Nullable Map<?, ?> filters,
                            Set<?> ids, boolean peek) {
    }

    /**
     * Filled in by the patch: the bucket's id, StoryBucket.getId(). The stubs below each use only
     * their parameter registers, so their own register counts don't matter.
     */
    public static Object bucketIdOf(Object bucket) {
        return UNPATCHED;
    }

    /** Filled in by the patch: the bucket's cards, an ImmutableList of StoryCard. */
    public static Object cardsOf(Object bucket) {
        return UNPATCHED;
    }

    /** Filled in by the patch: the card's id, StoryCard.getId(). */
    public static Object storyIdOf(Object card) {
        return UNPATCHED;
    }

    /**
     * Filled in by the patch: the bucket's own answer to whether it has a story not watched yet, the
     * method Facebook's bucket answers from is_bucket_seen_by_viewer.
     */
    public static boolean hasUnwatchedOf(Object bucket) {
        return false;
    }

    /** Filled in by the patch: the FbUserSession the bucket was built for. */
    public static Object sessionOf(Object bucket) {
        return UNPATCHED;
    }

    /**
     * Filled in by the patch with the calls Facebook makes when its servers answer a report: the
     * session's GraphQL consistency, a [type] tree with the bucket's id and [field] set to [seen],
     * published with full consistency. Unfilled, nothing is published.
     */
    public static void publishSeen(Object session, String bucketId, String type, String field, boolean seen) {
    }
}
