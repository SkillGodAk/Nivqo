/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;

/**
 * What Marketplace only does to Facebook's tab bar: it takes off Home with the news feed, Video,
 * Friends, Feeds, Groups, Gaming and Events, and leaves Marketplace, Notifications, the profile or
 * Menu tab and any tab it doesn't know. Events only (#164) is the same with Events kept and
 * Marketplace taken off. Marketplace only wins when both switches are on.
 *
 * <p>Facebook builds the tab bar's list from the tabs its servers configure, leaving out each one
 * whose id is in the set of tabs hidden in its own Settings, Tab bar, Customize the bar. The patch
 * calls {@link #hidesTab} right after that set answers for a tab, so a tab this drops goes the way
 * a tab hidden there does. Facebook builds the list once and keeps it, so a change of a switch
 * shows when Facebook restarts. While a switch is on, {@link StartTabRoute} also opens Facebook on
 * the tab the mode keeps.
 *
 * <p>It fails open: with the patch not in the build, the switch off, Hushfacebook paused, the
 * settings not ready yet, a tab bar that has no tab to keep (not configured for the account, or
 * hidden in Facebook's editor), or a failure in here, the tab bar is Facebook's own.
 */
public final class MarketplaceOnly {
    /** The tabs this takes off the tab bar, by the class Facebook keeps for each. */
    static final Set<String> DROPPED_TABS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            FacebookTabs.HOME_CLASS,
            FacebookTabs.FEEDS_CLASS,
            FacebookTabs.MOST_RECENT_CLASS,
            FacebookTabs.VIDEO_CLASS,
            FacebookTabs.FRIENDS_CLASS,
            FacebookTabs.GROUPS_CLASS,
            FacebookTabs.GAMING_CLASS,
            FacebookTabs.GAMING_CONTROLLER_CLASS,
            FacebookTabs.EVENTS_CLASS)));

    /** The name the log lines go under, which is also their logcat tag after Morphe's prefix. */
    static final String SOURCE = "MarketplaceOnly";

    /** What every line of this hook starts with, for a person reading the log. */
    static final String PREFIX = "Marketplace only: ";

    /** What every line of this hook starts with while the tab kept is Events. */
    static final String EVENTS_PREFIX = "Events only: ";

    /** Whether the patch is in this build, when a test says so instead of {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean inBuildForTests;

    /** The tab classes a line has been logged for, so a rebuilt tab bar doesn't log them again. */
    private static final Set<String> logged = Collections.synchronizedSet(new HashSet<>());

    public enum State { OFF, WAITING, ACTIVE, RESTART_NEEDED, PAUSED, MISSING, HIDDEN, UNREADABLE }

    /** What the last tab-bar build actually used, not merely what its switch now asks for. */
    @Nullable
    private static volatile Boolean appliedChoice;
    /** The tab the last tab-bar build kept, when it kept one. */
    @Nullable
    private static volatile StartTab appliedTab;
    private static volatile State marketplaceAvailability = State.WAITING;
    private static volatile State eventsAvailability = State.WAITING;

    private MarketplaceOnly() {
    }

    /** Whether this build carries the patch. */
    static boolean inBuild() {
        Boolean forced = inBuildForTests;
        return forced != null ? forced : SettingsStatus.marketplaceOnly();
    }

    /**
     * The tab the tab bar keeps now, Marketplace or Events, or null: the patch in the build, the
     * settings ready and a switch on, which a pause answers off. Marketplace wins over Events.
     * Never throws.
     */
    @Nullable
    static StartTab keptTab() {
        try {
            if (!inBuild() || !Utils.settingsReady()) return null;
            if (Settings.MARKETPLACE_ONLY.get()) return StartTab.MARKETPLACE;
            return Settings.EVENTS_ONLY.get() ? StartTab.EVENTS : null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MARKETPLACE_ONLY, "marketplace only switch", failure);
            return null;
        }
    }

    /** Whether the tab bar is Marketplace only or Events only now. Never throws. */
    static boolean on() {
        return keptTab() != null;
    }

    /** Whether the tab bar is Marketplace only now. Never throws. */
    static boolean marketplaceOn() {
        return keptTab() == StartTab.MARKETPLACE;
    }

    /** The tab the switches ask for as saved, whatever a pause says, or null. */
    @Nullable
    private static StartTab savedTab() {
        if (Settings.MARKETPLACE_ONLY.savedValue()) return StartTab.MARKETPLACE;
        return Settings.EVENTS_ONLY.savedValue() ? StartTab.EVENTS : null;
    }

    /**
     * Whether {@link #state} speaks for [tab]'s switch: that tab is the one kept, or the bar Facebook
     * built still keeps only it. The other mode's row shows its plain summary instead.
     */
    public static boolean stateIsFor(StartTab tab) {
        return savedTab() == tab || (Boolean.TRUE.equals(appliedChoice) && appliedTab == tab);
    }

    private static State availabilityOf(StartTab tab) {
        return tab == StartTab.EVENTS ? eventsAvailability : marketplaceAvailability;
    }

    /** State for settings, based on the tab list the hook has seen in this process. */
    public static State state() {
        if (!inBuild()) return State.OFF;
        if (!Utils.settingsReady()) return State.WAITING;
        StartTab wanted = savedTab();
        Boolean applied = appliedChoice;
        // Pausing cannot put back tabs already removed from Facebook's cached bar, and a bar built
        // for the other mode needs a restart too.
        if (Boolean.TRUE.equals(applied) && appliedTab != wanted) return State.RESTART_NEEDED;
        if (Setting.isPaused()) return wanted != null ? State.PAUSED : State.OFF;
        if (wanted == null) return State.OFF;
        State availability = availabilityOf(wanted);
        if (availability == State.MISSING || availability == State.HIDDEN || availability == State.UNREADABLE) {
            return availability;
        }
        if (Boolean.FALSE.equals(applied)) return State.RESTART_NEEDED;
        return availability;
    }

    /** An optional overlay. The six independent notification switches are never rewritten. */
    public static boolean quietNotifications() {
        return marketplaceOn() && Settings.MARKETPLACE_QUIET_NOTIFICATIONS.get();
    }

    /** Only background feed warm-ups ask this. Early startup and uncertain tab state fail open. */
    public static boolean skipFeedPrefetch() {
        try {
            if (!marketplaceOn() || !Settings.MARKETPLACE_SKIP_FEED_PREFETCH.get() || state() != State.ACTIVE) return false;
            if (logged.add("prefetch")) {
                Logger.diagnosticInfo(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                        () -> PREFIX + "skipped a background feed warm-up while Marketplace is active.");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MARKETPLACE_ONLY, "feed prefetch", failure);
            return false;
        }
    }

    /**
     * Injection point in Facebook's tab bar list builder, right after its set of hidden tab ids
     * answered [hidden] for [tab]. [configured] is the list of tabs the account is configured with
     * and [hiddenIds] the set, each id a String. Answers whether the tab stays off the bar: always
     * when Facebook hides it, and for a tab this drops while a switch is on and the bar has the tab
     * it keeps. Never throws.
     */
    public static boolean hidesTab(boolean hidden, @Nullable Object tab, @Nullable List<?> configured,
                                   @Nullable Set<?> hiddenIds) {
        try {
            if (tab == null || !inBuild()) return hidden;
            if (!Utils.settingsReady()) {
                appliedChoice = false;
                appliedTab = null;
                marketplaceAvailability = State.WAITING;
                eventsAvailability = State.WAITING;
                return hidden;
            }
            StartTab kept = keptTab();
            marketplaceAvailability = availability(StartTab.MARKETPLACE, configured, hiddenIds);
            eventsAvailability = availability(StartTab.EVENTS, configured, hiddenIds);
            boolean applies = kept != null && availabilityOf(kept) == State.ACTIVE;
            appliedChoice = applies;
            appliedTab = applies ? kept : null;
            if (hidden) return true;
            HookStatus.invoked(FamilyNames.MARKETPLACE_ONLY);
            String name = tab.getClass().getName();
            if (kept == null || !drops(kept, name)) return false;
            boolean events = kept == StartTab.EVENTS;
            if (!applies) {
                if (logged.add(events ? "events" : "")) {
                    Logger.diagnosticInfo(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE, () -> (events
                            ? EVENTS_PREFIX + "this tab bar has no Events to open"
                            : PREFIX + "this tab bar has no Marketplace to open")
                            + ", so it stays as Facebook built it.");
                }
                return false;
            }
            HookStatus.bound(FamilyNames.MARKETPLACE_ONLY, "tab bar");
            if (logged.add(name)) {
                Logger.diagnosticDebug(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                        () -> (events ? EVENTS_PREFIX : PREFIX) + "took " + tab.getClass().getSimpleName()
                                + " off the tab bar.");
            }
            return true;
        } catch (Throwable failure) {
            marketplaceAvailability = State.UNREADABLE;
            eventsAvailability = State.UNREADABLE;
            HookStatus.threw(FamilyNames.MARKETPLACE_ONLY, "tab bar", failure);
            return hidden;
        }
    }

    /** Whether the mode that keeps [kept] takes the tab of the class Java names [name] off the bar. */
    private static boolean drops(StartTab kept, String name) {
        if (kept != StartTab.EVENTS) return DROPPED_TABS.contains(name);
        if (StartTab.MARKETPLACE.isTab(name)) return true;
        return DROPPED_TABS.contains(name) && !StartTab.EVENTS.isTab(name);
    }

    /**
     * Whether the tab bar will have [wanted]: the account is configured with it and it isn't among
     * the tabs hidden in Facebook's own editor. Without it there'd be nothing to open, so the bar
     * stays as Facebook built it.
     */
    private static State availability(StartTab wanted, @Nullable List<?> configured, @Nullable Set<?> hiddenIds) {
        if (configured == null) return State.UNREADABLE;
        for (Object each : configured) {
            if (each == null || !wanted.isTab(each.getClass().getName())) continue;
            long id = StartTabRoute.TabBar.tabId(each);
            if (id == -1) return State.UNREADABLE;
            return hiddenIds != null && hiddenIds.contains(String.valueOf(id)) ? State.HIDDEN : State.ACTIVE;
        }
        return State.MISSING;
    }

    /** Forgets which lines were logged, as a new process would. */
    static void forgetLogged() {
        logged.clear();
        appliedChoice = null;
        appliedTab = null;
        marketplaceAvailability = State.WAITING;
        eventsAvailability = State.WAITING;
    }
}
