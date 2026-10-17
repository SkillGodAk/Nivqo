/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Marking watched buckets on the phone (#61): a bucket watched to its newest story is published as
 * watched when its batch of views is held back, and again each time Facebook builds it with the
 * same newest story, until a new one arrives. A peek, a story short of the newest, the switch off
 * or a pause marks nothing, and the switch saved off forgets what was kept.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StorySeenMarkingTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Facebook's bucket, as far as the code here sees it. */
    private static final class Bucket {
        final String id;
        final List<String> cards;
        boolean unwatched = true;

        Bucket(String id, String... cards) {
            this.id = id;
            this.cards = Arrays.asList(cards);
        }
    }

    /** Facebook's classes: a card is its id, and publishing a bucket marks it watched, as the consistency publish does. */
    private final List<String> published = new ArrayList<>();
    private final StorySeen.Stories stories = new StorySeen.Stories() {
        @Override public String bucketId(Object bucket) { return ((Bucket) bucket).id; }
        @Override public List<?> cards(Object bucket) { return ((Bucket) bucket).cards; }
        @Override public String storyId(Object card) { return (String) card; }
        @Override public boolean hasUnwatched(Object bucket) { return ((Bucket) bucket).unwatched; }
        @Override public void publishWatched(Object bucket, String bucketId) {
            published.add(bucketId);
            ((Bucket) bucket).unwatched = false;
        }
    };

    private final Object helper = new Object();

    @Before
    public void start() {
        RuntimeEnvironment.getApplication().getSharedPreferences(SeenBuckets.FILE, Context.MODE_PRIVATE).edit().clear().commit();
        StorySeen.useForTests(stories);
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
    }

    @After
    public void restore() {
        StorySeen.useForTests(null);
        PauseForTests.resume();
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** Past the time a bucket isn't published twice in. */
    private static void later() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(StorySeen.REMARK_MS + 1));
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.STORY_SEEN + ":")) return line;
        }
        return null;
    }

    /** The patched sender's hook, handed a batch of one card: whether the batch was held back. */
    private static boolean heldBack() {
        return StorySeen.toSend(new Object(), new Object(), StorySeenForTests.ACCOUNT, "a", "b", "c", null,
                StorySeenForTests.cards("x"), false) == null;
    }

    /** Facebook built [bucket] again, the way it does each time the tray loads. */
    private static void built(Bucket bucket) {
        StorySeen.bucketMade(bucket);
        idle();
    }

    @Test
    public void aBucketWatchedToItsNewestStoryIsMarkedWatchedWhenItsViewsAreHeldBack() {
        Bucket friend = new Bucket("b1", "s1", "s2");
        StorySeen.viewed(friend, "s1", helper, false);
        StorySeen.viewed(friend, "s2", helper, false);
        assertTrue(published.isEmpty());
        assertTrue(heldBack());
        idle();
        assertEquals(Arrays.asList("b1"), published);
        assertFalse(friend.unwatched);
        assertTrue(statusLine(), statusLine().contains(StorySeen.MARKED));

        // The next batch held back doesn't mark it again.
        assertTrue(heldBack());
        idle();
        assertEquals(1, published.size());
    }

    @Test
    public void aBucketBuiltAgainIsMarkedAgainUntilANewStoryArrives() {
        Bucket friend = new Bucket("b1", "s1", "s2");
        StorySeen.viewed(friend, "s2", helper, false);
        heldBack();
        idle();
        assertEquals(1, published.size());

        // Facebook builds the bucket again from its servers, which still send it as unwatched.
        // Within moments of the last mark, once is enough.
        built(new Bucket("b1", "s1", "s2"));
        assertEquals(1, published.size());
        later();
        Bucket reloaded = new Bucket("b1", "s1", "s2");
        built(reloaded);
        assertEquals(Arrays.asList("b1", "b1"), published);
        assertFalse(reloaded.unwatched);

        // Facebook started again: what was watched is read back from the file.
        StorySeen.useForTests(stories);
        built(new Bucket("b1", "s1", "s2"));
        assertEquals(3, published.size());

        // A new story makes it new again, and it stays so.
        later();
        Bucket newer = new Bucket("b1", "s1", "s2", "s3");
        built(newer);
        assertEquals(3, published.size());
        assertTrue(newer.unwatched);

        // A bucket Facebook already sends as watched isn't published over.
        later();
        Bucket seen = new Bucket("b1", "s1", "s2");
        seen.unwatched = false;
        built(seen);
        assertEquals(3, published.size());
    }

    @Test
    public void aPeekOrAStoryShortOfTheNewestMarksNothing() {
        Bucket friend = new Bucket("b1", "s1", "s2");
        StorySeen.viewed(friend, "s1", helper, false);
        StorySeen.viewed(friend, "s2", helper, true);
        StorySeen.viewed(new Bucket("b2"), "s1", helper, false);
        heldBack();
        idle();
        built(new Bucket("b1", "s1", "s2"));
        assertTrue(published.isEmpty());
        assertTrue(friend.unwatched);
    }

    @Test
    public void offOrPausedNothingIsMarked() {
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        Bucket friend = new Bucket("b1", "s1");
        StorySeen.viewed(friend, "s1", helper, false);
        assertFalse(heldBack());
        idle();
        built(new Bucket("b1", "s1"));
        assertTrue(published.isEmpty());

        // Watched with the switch on, then paused: the views go out, and nothing is marked here,
        // since Facebook marks them itself once its servers take them.
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        StorySeen.viewed(friend, "s1", helper, false);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(heldBack());
        idle();
        built(new Bucket("b1", "s1"));
        assertTrue(published.isEmpty());

        // A pause keeps what was watched, for when it ends.
        PauseForTests.resume();
        built(new Bucket("b1", "s1"));
        assertEquals(Arrays.asList("b1"), published);
    }

    @Test
    public void theSwitchSavedOffForgetsWhatWasWatched() {
        StorySeen.viewed(new Bucket("b1", "s1"), "s1", helper, false);
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        built(new Bucket("b1", "s1"));
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        built(new Bucket("b1", "s1"));
        assertTrue(published.isEmpty());
        assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences(SeenBuckets.FILE, Context.MODE_PRIVATE)
                .getAll().isEmpty());
    }

    @Test
    public void aFailureInFacebooksClassesSendsTheViewsAndIsReported() {
        StorySeen.useForTests(new StorySeen.Stories() {
            @Override public String bucketId(Object bucket) { throw new IllegalStateException("renamed"); }
            @Override public List<?> cards(Object bucket) { throw new IllegalStateException("renamed"); }
            @Override public String storyId(Object card) { throw new IllegalStateException("renamed"); }
            @Override public boolean hasUnwatched(Object bucket) { throw new IllegalStateException("renamed"); }
            @Override public void publishWatched(Object bucket, String bucketId) { throw new IllegalStateException("renamed"); }
        });
        StorySeen.viewed(new Bucket("b1", "s1"), "s1", helper, false);
        StorySeen.bucketMade(new Bucket("b1", "s1"));
        idle();
        assertTrue("the views stopped going out", heldBack());
        assertTrue(statusLine(), statusLine().contains("threw"));
    }
}
