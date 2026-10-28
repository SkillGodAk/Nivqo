/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.media.RecentlyWatched.Entry;

/**
 * The Recently watched list as it's kept: one entry per reel, video or story, the one watched last
 * first, a second watch moving it up, the oldest dropped past the limit, and the file read back as
 * it was written.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class WatchedListTest {
    private SharedPreferences store;

    @Before
    public void start() {
        store = RuntimeEnvironment.getApplication().getSharedPreferences("watched_list_test", Context.MODE_PRIVATE);
        store.edit().clear().commit();
    }

    @After
    public void restore() {
        store.edit().clear().commit();
    }

    private static Entry reel(String id, long at) {
        return new Entry(RecentlyWatched.REEL, id, null, null, null, at, 30_000);
    }

    private static List<String> ids(List<Entry> entries) {
        List<String> ids = new ArrayList<>();
        for (Entry entry : entries) ids.add(entry.kind + ":" + entry.id);
        return ids;
    }

    @Test
    public void theOneWatchedLastComesFirst() {
        WatchedList list = new WatchedList(store);
        assertTrue(list.add(reel("a", 1)));
        assertTrue(list.add(reel("b", 2)));
        assertTrue(list.add(reel("c", 3)));
        assertEquals("[reel:c, reel:b, reel:a]", ids(list.newestFirst()).toString());
    }

    @Test
    public void aSecondWatchMovesItUpWithItsNewTime() {
        WatchedList list = new WatchedList(store);
        list.add(reel("a", 1));
        list.add(reel("b", 2));
        assertFalse("a second watch counted as new", list.add(reel("a", 9)));
        List<Entry> entries = list.newestFirst();
        assertEquals("[reel:a, reel:b]", ids(entries).toString());
        assertEquals(9, entries.get(0).watchedAt);
        assertEquals(2, list.size());
    }

    @Test
    public void aReelAndAVideoWithOneIdAreOneEntryUnderTheLastKind() {
        WatchedList list = new WatchedList(store);
        list.add(reel("1", 1));
        assertFalse("the same video counted as new",
                list.add(new Entry(RecentlyWatched.VIDEO, "1", null, null, null, 2, 0)));
        List<Entry> entries = list.newestFirst();
        assertEquals("[video:1]", ids(entries).toString());
        assertEquals("the reel's length went", 30_000, entries.get(0).durationMs);
        list.add(reel("1", 3));
        assertEquals("[reel:1]", ids(list.newestFirst()).toString());
    }

    @Test
    public void aStoryAndAReelWithOneIdStayTwo() {
        WatchedList list = new WatchedList(store);
        list.add(new Entry(RecentlyWatched.STORY, "1", null, null, "b", 1, 0));
        assertTrue(list.add(reel("1", 2)));
        assertEquals("[reel:1, story:1]", ids(list.newestFirst()).toString());
    }

    @Test
    public void aFileListingOneVideoTwiceReadsAsOne() {
        store.edit().putString(WatchedList.KEY, "[{\"k\":\"reel\",\"i\":\"1\",\"t\":1,\"d\":30000},"
                + "{\"k\":\"video\",\"i\":\"2\",\"t\":2},{\"k\":\"video\",\"i\":\"1\",\"t\":3}]").commit();
        List<Entry> read = new WatchedList(store).newestFirst();
        assertEquals("[video:1, video:2]", ids(read).toString());
        assertEquals(30_000, read.get(0).durationMs);
        assertEquals(3, read.get(0).watchedAt);
    }

    @Test
    public void aSecondWatchKeepsWhatTheFirstKnew() {
        WatchedList list = new WatchedList(store);
        list.add(new Entry(RecentlyWatched.STORY, "s", "Ana", "Hello", "b", 1, 15_000));
        list.add(new Entry(RecentlyWatched.STORY, "s", null, null, null, 5, 0));
        Entry entry = list.newestFirst().get(0);
        assertEquals("Ana", entry.owner);
        assertEquals("Hello", entry.caption);
        assertEquals("b", entry.parent);
        assertEquals(15_000, entry.durationMs);
        assertEquals(5, entry.watchedAt);
    }

    @Test
    public void theOldestGoesPastTheLimit() {
        WatchedList list = new WatchedList(store);
        int over = WatchedList.MAX_ENTRIES + 5;
        for (int i = 0; i < over; i++) list.add(reel("v" + i, i));
        assertEquals(WatchedList.MAX_ENTRIES, list.size());
        List<Entry> entries = list.newestFirst();
        assertEquals("v" + (over - 1), entries.get(0).id);
        assertEquals("the oldest five stayed", "v5", entries.get(entries.size() - 1).id);

        List<Entry> reread = new WatchedList(store).newestFirst();
        assertEquals(WatchedList.MAX_ENTRIES, reread.size());
        assertEquals("v5", reread.get(reread.size() - 1).id);
    }

    @Test
    public void theFileReadsBackAsItWasWritten() {
        WatchedList list = new WatchedList(store);
        list.add(new Entry(RecentlyWatched.STORY, "s1", "Ana \"Q\" Lee", null, "b1", 100, 0));
        list.add(new Entry(RecentlyWatched.VIDEO, "v1", null, "A caption", null, 200, 61_000));
        list.add(reel("r1", 300));

        List<Entry> reread = new WatchedList(store).newestFirst();
        assertEquals("[reel:r1, video:v1, story:s1]", ids(reread).toString());
        Entry story = reread.get(2);
        assertEquals("Ana \"Q\" Lee", story.owner);
        assertEquals("b1", story.parent);
        assertNull(story.caption);
        assertEquals(100, story.watchedAt);
        Entry video = reread.get(1);
        assertEquals("A caption", video.caption);
        assertEquals(61_000, video.durationMs);
        assertNull(video.owner);
    }

    @Test
    public void whatDoesntParseIsLeftOut() {
        String saved = "[{\"k\":\"reel\",\"i\":\"good\",\"t\":5},"
                + "{\"k\":\"photo\",\"i\":\"unknown kind\",\"t\":6},"
                + "{\"k\":\"video\",\"t\":7},"
                + "{\"k\":\"video\",\"i\":\"\",\"t\":8},"
                + "\"not an object\","
                + "{\"k\":\"story\",\"i\":\"s\",\"p\":\"b\"}]";
        List<Entry> read = WatchedList.decode(saved);
        assertEquals(2, read.size());
        assertEquals("good", read.get(0).id);
        assertEquals("s", read.get(1).id);
        assertEquals(0, read.get(1).watchedAt);

        assertTrue(WatchedList.decode("not json").isEmpty());
        store.edit().putString(WatchedList.KEY, "{broken").commit();
        WatchedList list = new WatchedList(store);
        assertEquals(0, list.size());
        assertTrue("a broken file kept the list from taking a new entry", list.add(reel("a", 1)));
        assertEquals(1, new WatchedList(store).size());
    }

    @Test
    public void clearForgetsEveryEntryAndTheFile() {
        WatchedList list = new WatchedList(store);
        list.add(reel("a", 1));
        list.add(reel("b", 2));
        list.clear();
        assertEquals(0, list.size());
        assertFalse(store.contains(WatchedList.KEY));
        assertEquals(0, new WatchedList(store).size());
        assertTrue(list.add(reel("a", 3)));
        assertEquals(1, list.size());
    }
}
