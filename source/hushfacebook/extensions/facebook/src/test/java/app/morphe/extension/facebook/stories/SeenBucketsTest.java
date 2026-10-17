/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The watched buckets stay within their bounds: at most {@link SeenBuckets#MAX_BUCKETS}, the least
 * recently watched going first, none older than {@link SeenBuckets#KEEP_MS}, and a value that
 * doesn't read as a bucket is dropped from the file rather than kept or trusted.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SeenBucketsTest {
    private static final long NOW = 1_800_000_000_000L;
    private SharedPreferences file;

    @Before
    public void start() {
        file = RuntimeEnvironment.getApplication().getSharedPreferences(SeenBuckets.FILE, Context.MODE_PRIVATE);
        file.edit().clear().commit();
    }

    @Test
    public void aBucketIsKeptReplacedAndReadBackAfterARestart() {
        SeenBuckets seen = new SeenBuckets(file);
        assertTrue(seen.isEmpty(NOW));
        assertNull(seen.newestWatched("b1", NOW));
        seen.watched("b1", "s1", NOW);
        seen.watched("b1", "s2", NOW + 1);
        assertEquals("s2", seen.newestWatched("b1", NOW + 2));
        assertEquals(1, seen.size(NOW));

        // A new store over the same file is Facebook started again.
        SeenBuckets restarted = new SeenBuckets(file);
        assertEquals("s2", restarted.newestWatched("b1", NOW + 3));
        // A story id is Facebook's to choose, colons and all.
        restarted.watched("b2", "s:3:x", NOW + 4);
        assertEquals("s:3:x", new SeenBuckets(file).newestWatched("b2", NOW + 5));
    }

    @Test
    public void theOldestGoesFirstPastTheLimit() {
        SeenBuckets seen = new SeenBuckets(file);
        for (int index = 0; index <= SeenBuckets.MAX_BUCKETS; index++) {
            seen.watched("b" + index, "s", NOW + index);
        }
        assertEquals(SeenBuckets.MAX_BUCKETS, seen.size(NOW + 1_000));
        assertNull("the oldest stayed", seen.newestWatched("b0", NOW + 1_000));
        assertNotNull(seen.newestWatched("b1", NOW + 1_000));
        assertEquals(SeenBuckets.MAX_BUCKETS, file.getAll().size());

        // Watching an old one again makes it the newest, so the next to go is the one after it.
        seen.watched("b1", "s", NOW + 2_000);
        seen.watched("new", "s", NOW + 2_001);
        assertNotNull(seen.newestWatched("b1", NOW + 3_000));
        assertNull(seen.newestWatched("b2", NOW + 3_000));
    }

    @Test
    public void aFileOverTheLimitIsTrimmedToTheNewestWhenRead() {
        SharedPreferences.Editor edit = file.edit();
        for (int index = 0; index < SeenBuckets.MAX_BUCKETS + 5; index++) {
            edit.putString("b" + index, SeenBuckets.encode("s", NOW + index));
        }
        edit.commit();
        SeenBuckets seen = new SeenBuckets(file);
        assertEquals(SeenBuckets.MAX_BUCKETS, seen.size(NOW + 1_000));
        for (int index = 0; index < 5; index++) assertNull(seen.newestWatched("b" + index, NOW + 1_000));
        assertNotNull(seen.newestWatched("b5", NOW + 1_000));
        assertEquals(SeenBuckets.MAX_BUCKETS, file.getAll().size());
    }

    @Test
    public void aBucketPastItsAgeIsForgotten() {
        SeenBuckets seen = new SeenBuckets(file);
        seen.watched("old", "s", NOW);
        seen.watched("new", "s", NOW + SeenBuckets.KEEP_MS);
        long later = NOW + SeenBuckets.KEEP_MS + 1;
        assertNull(seen.newestWatched("old", later));
        assertFalse(file.contains("old"));
        assertEquals("s", seen.newestWatched("new", later));

        // Read cold, the file drops the old one too.
        file.edit().putString("old", SeenBuckets.encode("s", NOW)).commit();
        assertEquals(1, new SeenBuckets(file).size(later));
        assertFalse(file.contains("old"));
    }

    @Test
    public void aValueThatDoesntReadIsDropped() {
        file.edit()
                .putString("noTime", ":s")
                .putString("noStory", NOW + ":")
                .putString("notANumber", "soon:s")
                .putInt("notText", 4)
                .putString("good", SeenBuckets.encode("s", NOW))
                .commit();
        SeenBuckets seen = new SeenBuckets(file);
        assertEquals(1, seen.size(NOW));
        assertEquals("s", seen.newestWatched("good", NOW));
        assertEquals(1, file.getAll().size());
    }

    @Test
    public void clearForgetsEveryBucket() {
        SeenBuckets seen = new SeenBuckets(file);
        seen.watched("b1", "s", NOW);
        seen.watched("b2", "s", NOW);
        seen.clear();
        assertTrue(seen.isEmpty(NOW));
        assertTrue(file.getAll().isEmpty());
        assertTrue(new SeenBuckets(file).isEmpty(NOW));
    }
}
