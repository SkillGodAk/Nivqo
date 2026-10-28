/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Open comments with a gesture patch asks when a reel gets a double tap or a long press
 * (issue #179).
 *
 * <p>The Comment button on a reel's sidebar is built from one object per reel, which keeps the reel
 * it belongs to and knows how to open the comment sheet. {@link #remember} notes each of them as
 * Facebook makes it. When a double tap reaches the reel like helper's double-tap like, or the
 * long-press handler of a reel's controls runs, the patch asks {@link #doubleTap} or
 * {@link #longPress}; with the switch on they find the button object of that reel and press it
 * the way a tap on the button does, so the sheet that opens is the one the Comment button opens.
 *
 * <p>With Clean up Reels' Always use clean mode on, the sidebar is never built, so a reel has no
 * button object (#179). The reel's long-press handler keeps what the button's click hands the comment
 * sheet, so {@link #rememberHandler} notes each handler as Facebook makes it, and a reel with no
 * button gets its sheet from its handler through {@link #openSheetStub}, which the patch fills with
 * the click's own calls. The handler the video controls make never keeps the reel's story holder (the
 * overlay's does, and Clean mode has no overlay), so the holder may be null and the patch's helper
 * then makes one from the reel, the way Facebook does for a reel it has no story for yet.
 *
 * <p>A double tap that can't open comments is still not a like while the switch is on, since the
 * point of the switch is that a double tap doesn't like. A long press that can't open comments goes
 * to Facebook, which holds or opens its menu as before. With the switches off, with Hushfacebook
 * paused or when anything in here fails, nothing changes.
 */
public final class ReelGestures {
    private static final String FAMILY = FamilyNames.REEL_GESTURES;

    /** Counted for each reel whose comments a gesture opened. */
    static final String OPENED = "comments opened";
    /** Counted for each reel with no Comment button object whose comments a gesture opened from its long-press handler. */
    static final String OPENED_WITHOUT_BUTTON = "comments opened without the button";
    /** Counted for each gesture on a reel that has no Comment button object yet, nor a handler keeping what the sheet needs. */
    static final String NO_BUTTON = "no comment button for the reel";
    /** Counted for each double tap or long press whose reel view the patch hasn't seen. */
    static final String NO_VIEW = "no view for the reel";
    /** Counted for each double tap like that the switch replaced. */
    static final String LIKE_REPLACED = "double tap like replaced";

    /** The names Kotlin gives what the long-press handler captured: the reel, and what the comment sheet needs. */
    static final String HANDLER_ITEM_FIELD = "$shortFormVideoUnit";
    static final String HANDLER_SESSION_FIELD = "$fbUserSession";
    static final String HANDLER_ENVIRONMENT_FIELD = "$environment";
    static final String HANDLER_STORY_FIELD = "$videoGraphQLPhoton";
    static final String HANDLER_CONFIG_FIELD = "$immersiveFeedPlayerConfig";

    /** What the comment sheet takes from a handler, in the order the sheet opener takes it. */
    private static final String[] SHEET_FIELDS = {
            HANDLER_SESSION_FIELD, HANDLER_ITEM_FIELD, HANDLER_ENVIRONMENT_FIELD, HANDLER_STORY_FIELD, HANDLER_CONFIG_FIELD,
    };

    /** Where the story holder sits in {@link #SHEET_FIELDS}: the one part the sheet can do without. */
    private static final int STORY_PART = 3;

    /** The Comment button objects and handlers kept, newest first. A reel on screen has one of each, so a few are plenty. */
    private static final int KEEP = 24;

    /** How the Comment button is pressed. Replaced by tests; in a patched build the patch fills {@link #openStub}. */
    interface Opener {
        boolean open(Object view, Object button);
    }

    static Opener opener = (view, button) -> openStub(view, button, false);

    /**
     * How a reel with no button object gets its comment sheet, from what its long-press handler keeps.
     * Replaced by tests; in a patched build the patch fills {@link #openSheetStub}.
     */
    interface SheetOpener {
        boolean open(Object view, Object session, Object item, Object environment, Object story, Object config);
    }

    static SheetOpener sheetOpener = ReelGestures::openSheetStub;

    private static final class Entry {
        final WeakReference<Object> item;
        final WeakReference<Object> owner;

        Entry(Object item, Object owner) {
            this.item = new WeakReference<>(item);
            this.owner = new WeakReference<>(owner);
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final List<Entry> HANDLERS = new ArrayList<>();
    private static WeakReference<Object> lastView = new WeakReference<>(null);
    private static final Set<String> LOGGED = new HashSet<>();
    private static final ConcurrentHashMap<String, ConcurrentHashMap<Class<?>, Field>> NAMED_FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Field> VIEW_FIELDS = new ConcurrentHashMap<>();

    private ReelGestures() {
    }

    /**
     * Filled in by the patch: presses Facebook's Comment button, its static click handler taking the
     * view, the button object and whether the count was tapped. Only a view and a button object may
     * be passed. Returns true once filled in.
     */
    public static boolean openStub(Object view, Object button, boolean count) {
        return false;
    }

    /**
     * Filled in by the patch: builds the comment sheet the way the Comment button's click does, from
     * the view, the session, the reel, the viewer's environment, the reel's story holder and the
     * viewer config a long-press handler keeps, and shows it. Only those may be passed, and only the
     * story holder may be null: the patch's helper then makes one from the reel. Returns true once
     * filled in and a sheet was built.
     */
    public static boolean openSheetStub(Object view, Object session, Object item, Object environment, Object story, Object config) {
        return false;
    }

    /**
     * Injection point, the end of the Comment button object's constructor: [button] is the new
     * object and [item] the reel it was made for.
     */
    public static void remember(@Nullable Object button, @Nullable Object item) {
        HookStatus.invoked(FAMILY);
        try {
            keep(ENTRIES, button, item);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "remember", failure);
        }
    }

    /**
     * Injection point, the end of a long-press handler's constructor: [handler] is the new handler and
     * [item] the reel it captured.
     */
    public static void rememberHandler(@Nullable Object handler, @Nullable Object item) {
        HookStatus.invoked(FAMILY);
        try {
            keep(HANDLERS, handler, item);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "remember handler", failure);
        }
    }

    private static void keep(List<Entry> entries, @Nullable Object owner, @Nullable Object item) {
        if (owner == null || item == null) return;
        synchronized (entries) {
            entries.removeIf(entry -> entry.owner.get() == null || entry.item.get() == null);
            entries.add(0, new Entry(item, owner));
            while (entries.size() > KEEP) entries.remove(entries.size() - 1);
        }
    }

    /** Injection point, first thing in the gesture view's heart: the view a double tap landed on. */
    public static void noteView(@Nullable Object view) {
        HookStatus.invoked(FAMILY);
        try {
            if (view != null) lastView = new WeakReference<>(view);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "view", failure);
        }
    }

    /**
     * Injection point, first thing in the reel like helper's double-tap like, with the reel it was
     * handed. True when the double tap is taken here, so the helper returns without liking.
     */
    public static boolean doubleTap(@Nullable Object item) {
        HookStatus.invoked(FAMILY);
        try {
            if (!Utils.settingsReady() || !Settings.DOUBLE_TAP_OPENS_COMMENTS.get()) return false;
            // Turn off double tap to like keeps the double tap from getting here at all; if it did,
            // it still wins.
            if (Settings.TURN_OFF_DOUBLE_TAP_LIKE.get()) return false;
            open(item, lastView.get(), null, "double tap");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "double tap", failure);
            return false;
        }
    }

    /**
     * Injection point, first thing in a reel's long-press handler: [handler] is the handler and
     * [holder] the gesture it was handed, which holds the view. True when comments opened and the
     * handler returns without its usual hold, false to let Facebook handle the press.
     */
    public static boolean longPress(@Nullable Object handler, @Nullable Object holder) {
        HookStatus.invoked(FAMILY);
        try {
            if (handler == null || !Utils.settingsReady() || !Settings.LONG_PRESS_OPENS_COMMENTS.get()) return false;
            return open(named(handler, HANDLER_ITEM_FIELD), viewOf(holder), handler, "long press");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "long press", failure);
            return false;
        }
    }

    /** Called by DoubleTapLike when this switch is what stopped a double tap from liking. */
    static void likeReplaced(String where) {
        HookStatus.counted(FAMILY, LIKE_REPLACED);
        logOnce(LIKE_REPLACED + ": " + where);
    }

    /**
     * Presses [item]'s Comment button, or with no button builds its sheet from [handler] (the long
     * press's own) or the newest handler made for it. True when comments opened.
     */
    private static boolean open(@Nullable Object item, @Nullable Object view, @Nullable Object handler, String gesture)
            throws IllegalAccessException {
        Object button = buttonFor(item);
        Object[] sheet = null;
        if (button == null) {
            sheet = sheetParts(handler);
            if (sheet == null) sheet = sheetParts(handlerFor(item));
        }
        if (button == null && sheet == null) {
            HookStatus.counted(FAMILY, NO_BUTTON);
            logOnce(gesture + ": " + NO_BUTTON + lacking(handler != null ? handler : handlerFor(item)));
            return false;
        }
        if (!(view instanceof View)) {
            HookStatus.counted(FAMILY, NO_VIEW);
            logOnce(gesture + ": " + NO_VIEW);
            return false;
        }
        if (button != null ? !opener.open(view, button)
                : !sheetOpener.open(view, sheet[0], sheet[1], sheet[2], sheet[3], sheet[4])) {
            String what = button != null ? "the comment button can't be pressed" : "the comment sheet can't be built without the button";
            HookStatus.threw(FAMILY, gesture, new IllegalStateException(what));
            return false;
        }
        String opened = button != null ? OPENED : OPENED_WITHOUT_BUTTON;
        HookStatus.counted(FAMILY, opened);
        logOnce(gesture + ": " + opened);
        return true;
    }

    /**
     * The session, the reel, the environment, the story holder and the config [handler] keeps, or null
     * when it lacks one of them other than the story holder.
     */
    @Nullable
    private static Object[] sheetParts(@Nullable Object handler) throws IllegalAccessException {
        if (handler == null) return null;
        Object[] parts = new Object[SHEET_FIELDS.length];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = named(handler, SHEET_FIELDS[i]);
            if (parts[i] == null && i != STORY_PART) return null;
        }
        return parts;
    }

    /** For the log: [handler]'s class and what it lacks of what the sheet needs, or that no handler was kept. */
    private static String lacking(@Nullable Object handler) throws IllegalAccessException {
        if (handler == null) return " (no long-press handler kept for the reel)";
        List<String> lacks = new ArrayList<>();
        for (String name : SHEET_FIELDS) {
            if (named(handler, name) == null) lacks.add(name);
        }
        return " (" + handler.getClass().getName() + " lacks " + String.join(", ", lacks) + ")";
    }

    /** The newest Comment button object made for [item]: the same object first, an equal one next. */
    @Nullable
    static Object buttonFor(@Nullable Object item) {
        return find(ENTRIES, item);
    }

    /** The newest long-press handler made for [item], found the same way. */
    @Nullable
    static Object handlerFor(@Nullable Object item) {
        return find(HANDLERS, item);
    }

    @Nullable
    private static Object find(List<Entry> entries, @Nullable Object item) {
        if (item == null) return null;
        synchronized (entries) {
            for (Entry entry : entries) {
                if (entry.item.get() == item && entry.owner.get() != null) return entry.owner.get();
            }
            for (Entry entry : entries) {
                Object kept = entry.item.get();
                if (kept != null && kept.equals(item) && entry.owner.get() != null) return entry.owner.get();
            }
        }
        return null;
    }

    @Nullable
    private static Object viewOf(@Nullable Object holder) throws IllegalAccessException {
        return holder == null ? null : read(VIEW_FIELDS, holder, View.class, null);
    }

    /** [owner]'s field named [name], read; null when it has none. */
    @Nullable
    private static Object named(Object owner, String name) throws IllegalAccessException {
        return read(NAMED_FIELDS.computeIfAbsent(name, key -> new ConcurrentHashMap<>()), owner, null, name);
    }

    /**
     * [owner]'s field named [name], or its first field of a type assignable to [type] when no name is
     * given, read; null when it has none. The lookup is kept per class.
     */
    @Nullable
    private static Object read(ConcurrentHashMap<Class<?>, Field> cache, Object owner, @Nullable Class<?> type,
                               @Nullable String name) throws IllegalAccessException {
        Field field = cache.get(owner.getClass());
        if (field == null) {
            for (Field candidate : owner.getClass().getDeclaredFields()) {
                boolean match = name != null ? candidate.getName().equals(name)
                        : type != null && type.isAssignableFrom(candidate.getType());
                if (match && !java.lang.reflect.Modifier.isStatic(candidate.getModifiers())) {
                    candidate.setAccessible(true);
                    field = candidate;
                    break;
                }
            }
            if (field == null) return null;
            cache.put(owner.getClass(), field);
        }
        return field.get(owner);
    }

    private static void logOnce(String message) {
        boolean first;
        synchronized (LOGGED) {
            first = LOGGED.add(message);
        }
        if (first) Logger.printDebug(() -> "Reel gestures: " + message);
    }

    /** For tests: forgets the buttons, the handlers, the view, the logged lines and the cached reflection. */
    static void reset() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
        synchronized (HANDLERS) {
            HANDLERS.clear();
        }
        lastView = new WeakReference<>(null);
        synchronized (LOGGED) {
            LOGGED.clear();
        }
        NAMED_FIELDS.clear();
        VIEW_FIELDS.clear();
    }
}
