/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.navigation.FeedsSubtab;
import app.morphe.extension.facebook.navigation.FeedsSubtabRoute;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Following feed on Home: Home asks Facebook for the newest posts from the friends, groups and
 * Pages you follow, the feed the Feeds tab's All shows, instead of the ranked one.
 *
 * <p>Facebook builds every news feed request's parameters in one place, which is handed the feed
 * type the request is for and turns it into the query's feed style. Home's own feed type keeps the
 * name {@value #HOME}, and the most recent feed's keeps {@value #MOST_RECENT}, both public constants
 * of Facebook's FeedType; the builder gives the second the MOST_RECENT_FEED_DEFAULT style, as it
 * does the Feeds tab's All. The patch hands the feed type to {@link #feedType} first thing, and
 * keeps what it answers in its place. While the switch is on, Home's answers the most recent
 * feed's. Every other feed type, the Feeds tab's filters among them, is handed back as it came.
 *
 * <p>Home can show one of the Feeds tab's filters instead ({@link Settings#HOME_FEED}): Friends,
 * Favorites, Groups or Pages. Their feed types keep names of their own, "most_recent_friend" and
 * the like, which {@link FeedsSubtab#feedTypes} lists, and Home's then answers the first of them
 * Facebook has. When it has none, Home's answers the most recent feed's, as with All, and the
 * report names the filter that wasn't there.
 *
 * <p>It used to answer the Following feed's type ("following_feed", the FOLLOWING_FEED style).
 * On the S22 test account (581, 2026-10-08) that request came back as the ranked feed, suggestions
 * and all, while the Feeds tab's All showed only followed posts, so Facebook's servers no longer
 * honour that style for every account.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the feed type is
 * handed back as it came and Home loads the ranked feed.
 */
public final class FollowingHome {
    /** The name Home's ranked feed type keeps. */
    static final String HOME = "top_stories";

    /** The name the most recent feed's type keeps. */
    static final String MOST_RECENT = "most_recent";

    /** Facebook's feed type class, the owner the report names when the most recent feed isn't in it. */
    static final String FEED_TYPE_CLASS = "com.facebook.api.feedtype.FeedType";

    /** Counted each time Home's request goes out for the most recent feed. */
    static final String SWAPPED = "Home asked for the most recent feed";

    /** Counted, followed by the feed type's name, each time Home's request goes out for a filter's feed. */
    static final String SWAPPED_TO = "Home asked for ";

    /** The member the report names once a request for Home's feed has come through. */
    static final String REQUEST = "Home feed request";

    /**
     * Counted, followed by the feed type's name, for each request for another feed while the switch
     * is on. On the S25 (581, 2026-10-08) a cold start's Home matched the Feeds tab's All, while a
     * pull to refresh showed ranked posts again, so the report says which feed types go out.
     */
    static final String LEFT_ALONE = "Request left as ";

    /** A feed type's name as Facebook writes its built-in ones. Anything else is counted unnamed. */
    private static final Pattern FEED_TYPE_NAME = Pattern.compile("[a-z0-9_]{1,40}");

    /** What a request for a feed type that isn't named, or past the names kept, is counted as. */
    private static final String ANOTHER_TYPE = "another type";

    /**
     * Feed types counted by name after {@link #LEFT_ALONE}. A family's report keeps 24 named counts,
     * and Home's own, one per filter and the most recent feed, have to fit after these.
     */
    private static final int LEFT_ALONE_NAMES = 8;

    /** The names counted after {@link #LEFT_ALONE} since the report was last emptied. */
    private static final Set<String> leftAloneNames = ConcurrentHashMap.newKeySet();

    /** The report's generation {@link #leftAloneNames} belongs to. */
    private static volatile long leftAloneGeneration = -1;

    private static final String FAMILY = FamilyNames.FOLLOWING_HOME;

    /** A feed type found by its name, kept so the next request needn't look again. */
    private static final class Found {
        final String name;
        final Object type;

        Found(String name, Object type) {
            this.name = name;
            this.type = type;
        }
    }

    /** The most recent feed's type, once found. */
    @Nullable
    private static volatile Object mostRecent;

    /** The chosen filter's feed type, once found. */
    @Nullable
    private static volatile Found chosen;

    /** The name of the feed type the log last said Home asked for. */
    @Nullable
    private static volatile String logged;

    private FollowingHome() {
    }

    /**
     * The hook, first thing in the news feed parameter builder, handed the feed type the request is
     * for. Answers the chosen filter's feed type, or the most recent feed's, in place of Home's
     * while the switch is on, and [requested] otherwise.
     */
    @Nullable
    public static Object feedType(@Nullable Object requested) {
        try {
            HookStatus.invoked(FAMILY);
            if (requested == null) return null;
            String name = requested.toString();
            if (!HOME.equals(name)) {
                countLeftAlone(name);
                return requested;
            }
            HookStatus.bound(FAMILY, REQUEST);
            if (!Utils.settingsReady() || !Settings.FOLLOWING_FEED_HOME.get()) return requested;
            FeedsSubtab feed = Settings.HOME_FEED.get();
            if (feed != FeedsSubtab.ALL) {
                Found filter = filterLike(requested, feed);
                if (filter != null) {
                    HookStatus.counted(FAMILY, SWAPPED_TO + filter.name);
                    log(filter.name);
                    return filter.type;
                }
                HookStatus.missingMember(FAMILY, "home feed", FEED_TYPE_CLASS, String.join(" or ", feed.feedTypes));
            }
            Object recent = mostRecentLike(requested);
            if (recent == null) {
                HookStatus.missingMember(FAMILY, "feed type", FEED_TYPE_CLASS, MOST_RECENT);
                return requested;
            }
            HookStatus.counted(FAMILY, SWAPPED);
            log(MOST_RECENT);
            return recent;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "home feed request", failure);
            return requested;
        }
    }

    /**
     * Counts a request for another feed while the switch is on, under its type's name when it reads
     * like one and there's room for another name.
     */
    private static void countLeftAlone(@Nullable String name) {
        if (!Utils.settingsReady() || !Settings.FOLLOWING_FEED_HOME.get()) return;
        String counted = name != null && FEED_TYPE_NAME.matcher(name).matches() ? name : ANOTHER_TYPE;
        long generation = HookStatus.generation();
        if (generation != leftAloneGeneration) {
            leftAloneNames.clear();
            leftAloneGeneration = generation;
        }
        if (!ANOTHER_TYPE.equals(counted) && !leftAloneNames.contains(counted)) {
            if (leftAloneNames.size() >= LEFT_ALONE_NAMES) counted = ANOTHER_TYPE;
            else leftAloneNames.add(counted);
        }
        HookStatus.counted(FAMILY, LEFT_ALONE + counted);
    }

    /** The most recent feed's type among the constants of [requested]'s class, or null. */
    @Nullable
    private static Object mostRecentLike(Object requested) {
        Object found = mostRecent;
        if (found != null && found.getClass() == requested.getClass()) return found;
        found = FeedsSubtabRoute.feedTypeNamed(requested.getClass(), MOST_RECENT);
        if (found != null) mostRecent = found;
        return found;
    }

    /** The first of [feed]'s feed types among the constants of [requested]'s class, with its name, or null. */
    @Nullable
    private static Found filterLike(Object requested, FeedsSubtab feed) {
        Found found = chosen;
        if (found != null && feed.feedTypes.contains(found.name) && found.type.getClass() == requested.getClass()) {
            return found;
        }
        for (String name : feed.feedTypes) {
            Object type = FeedsSubtabRoute.feedTypeNamed(requested.getClass(), name);
            if (type != null) {
                found = new Found(name, type);
                chosen = found;
                return found;
            }
        }
        return null;
    }

    /** Logs the feed Home asked for, once each time it changes. */
    private static void log(String name) {
        if (name.equals(logged)) return;
        logged = name;
        Logger.printDebug(() -> "Following feed on Home: Home asked for " + name);
    }
}
