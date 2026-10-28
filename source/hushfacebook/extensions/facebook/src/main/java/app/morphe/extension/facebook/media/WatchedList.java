/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

import app.morphe.extension.facebook.media.RecentlyWatched.Entry;

/**
 * The Recently watched list: at most {@link #MAX_ENTRIES} entries, one per video or story, the one
 * watched last at the end. A second watch moves an entry to the end with its new time. A reel is a
 * video: Facebook plays one id in its Reels viewer one time and its video viewer the next, so the
 * two watches share an entry, listed as the kind it was watched as last.
 *
 * <p>The list lives in a preferences file of its own as one JSON array, so it never reaches the
 * settings export or the diagnostic report. It's read once, the first time it's needed, and every
 * change writes the whole array back. An entry that doesn't parse is left out of it.
 */
final class WatchedList {
    /** The preferences file, in Facebook's own preferences folder. Only this class writes it. */
    static final String FILE = "hushfacebook_recently_watched";

    /** The one key the file holds. */
    static final String KEY = "entries";

    /** How many entries are kept. The one watched longest ago goes first. */
    static final int MAX_ENTRIES = 500;

    // The JSON names, short since the array is written whole on every change.
    private static final String KIND = "k";
    private static final String ID = "i";
    private static final String OWNER = "o";
    private static final String CAPTION = "c";
    private static final String PARENT = "p";
    private static final String WATCHED_AT = "t";
    private static final String DURATION = "d";

    private final SharedPreferences store;
    /** By {@link #key}, the one watched longest ago first. */
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private boolean loaded;

    WatchedList(SharedPreferences store) {
        this.store = store;
    }

    /**
     * Puts [entry] at the end, in place of the entry it was before, whose owner and caption it
     * keeps when it has none of its own. Drops the oldest past the limit. True when it's new.
     */
    synchronized boolean add(Entry entry) {
        load();
        String key = key(entry.kind, entry.id);
        Entry earlier = entries.remove(key);
        entries.put(key, earlier == null ? entry : entry.over(earlier));
        Iterator<String> oldest = entries.keySet().iterator();
        while (entries.size() > MAX_ENTRIES && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
        save();
        return earlier == null;
    }

    /** Every entry, the one watched last first. */
    synchronized List<Entry> newestFirst() {
        load();
        List<Entry> list = new ArrayList<>(entries.values());
        Collections.reverse(list);
        return list;
    }

    synchronized int size() {
        load();
        return entries.size();
    }

    /** Forgets every entry, and the file with them. */
    synchronized void clear() {
        entries.clear();
        loaded = true;
        store.edit().clear().apply();
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        String saved = store.getString(KEY, null);
        if (saved == null) return;
        List<Entry> read = decode(saved);
        int skip = Math.max(0, read.size() - MAX_ENTRIES);
        for (int index = skip; index < read.size(); index++) {
            Entry entry = read.get(index);
            String key = key(entry.kind, entry.id);
            Entry earlier = entries.remove(key);
            entries.put(key, earlier == null ? entry : entry.over(earlier));
        }
    }

    private void save() {
        store.edit().putString(KEY, encode(entries.values())).apply();
    }

    /** Where an entry sits: a reel's and a video's with one id are the same place. A story's is its card's. */
    static String key(String kind, String id) {
        return (RecentlyWatched.STORY.equals(kind) ? RecentlyWatched.STORY : RecentlyWatched.VIDEO) + ":" + id;
    }

    /** [entries] as the file keeps them, in their order. */
    static String encode(Iterable<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            try {
                JSONObject object = new JSONObject()
                        .put(KIND, entry.kind)
                        .put(ID, entry.id)
                        .put(WATCHED_AT, entry.watchedAt);
                if (entry.owner != null) object.put(OWNER, entry.owner);
                if (entry.caption != null) object.put(CAPTION, entry.caption);
                if (entry.parent != null) object.put(PARENT, entry.parent);
                if (entry.durationMs > 0) object.put(DURATION, entry.durationMs);
                array.put(object);
            } catch (JSONException unwritable) {
                // Only a non-finite number throws, and none is written.
            }
        }
        return array.toString();
    }

    /** The entries [saved] holds, in its order. One of an unknown kind, or with no id, is left out. */
    static List<Entry> decode(String saved) {
        List<Entry> read = new ArrayList<>();
        JSONArray array;
        try {
            array = new JSONArray(saved);
        } catch (JSONException unreadable) {
            return read;
        }
        for (int index = 0; index < array.length(); index++) {
            JSONObject object = array.optJSONObject(index);
            if (object == null) continue;
            String kind = text(object, KIND);
            String id = text(object, ID);
            if (kind == null || id == null || !RecentlyWatched.isKind(kind)) continue;
            read.add(new Entry(kind, id, text(object, OWNER), text(object, CAPTION), text(object, PARENT),
                    object.optLong(WATCHED_AT, 0), object.optInt(DURATION, 0)));
        }
        return read;
    }

    @Nullable
    private static String text(JSONObject object, String name) {
        Object value = object.opt(name);
        return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
    }
}
