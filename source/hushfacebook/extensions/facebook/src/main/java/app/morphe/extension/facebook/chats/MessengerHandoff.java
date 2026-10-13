/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What the Open Messenger from the top bar patch asks before Facebook rings for a call or posts a
 * chat notification, for the Leave calls and chats to Messenger switch (issues #39 and #100).
 *
 * <p>Facebook and Messenger both get every call and message for the account pushed to them, so
 * with both installed a call rings twice and a message notifies twice. The patch runs
 * {@link #dropCallPush} first in Facebook's push handlers for VOIP pushes, {@link #dropRing} first
 * in the parser every incoming ring goes through (from FCM, from Facebook's own MQTT connection and
 * from its call signaling), and {@link #dropChat} first where Facebook hands a chat notification to
 * its notifiers. While the switch is on and Meta's Messenger is installed and enabled, the push is
 * dropped, the ring parses to nothing, which Facebook already reads as nothing to ring, and a new
 * message, reaction, message request or missed call isn't posted. Messenger rings and notifies as it
 * always does.
 *
 * <p>Messenger is looked up afresh on every question, never kept. A missed call costs far more than
 * a package lookup, and a Messenger uninstalled or disabled a minute ago has to give the ring back
 * to Facebook straight away. Only Meta's own package counts: a copy under another package name, as
 * HushMessenger's install beside Meta's apps makes, may not get Meta's pushes at all, so Facebook
 * keeps ringing beside it. Whether Messenger is signed in can't be seen from here, which the
 * switch's summary says.
 *
 * <p>It fails open: with the switch off, a pause, settings that aren't ready, no context yet, no
 * Messenger, a disabled one, or any failure in here, Facebook rings and notifies as it always did.
 * The switch is read on every question, and every hook runs in Facebook's main process, so a change
 * applies to the next call without a restart.
 */
public final class MessengerHandoff {
    /** Counted under the patch's name when a VOIP push was dropped. */
    static final String CALL_PUSH_DROPPED = "call push left to Messenger";

    /** Counted when an incoming ring was parsed to nothing. */
    static final String RING_DROPPED = "ring left to Messenger";

    /** Counted when a chat notification wasn't posted. */
    static final String CHAT_DROPPED = "chat notification left to Messenger";

    /** Counted when the switch was on but no enabled Messenger was there, so Facebook kept it. */
    static final String NO_MESSENGER = "no Messenger, Facebook rang or notified";

    /**
     * The chat notifications Messenger posts itself, by their kept class names: a new message, a
     * reaction to one, a message request, a missed call, and the placeholder Facebook posts for an
     * end-to-end encrypted message it synced in the background. Facebook's own reminders, Page
     * inboxes and the rest still post.
     */
    static final Set<String> CHAT_KINDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.facebook.messaging.notify.type.NewMessageNotification",
            "com.facebook.messaging.notify.type.MessageReactionNotification",
            "com.facebook.messaging.notify.type.MessageRequestNotification",
            "com.facebook.messaging.rtc.callstatus.notification.MissedCallNotification",
            "com.facebook.messaging.tincan.tincanoverwamsys.backgroundsync.generic.notify.MessengerGenericTincanMessage")));

    private MessengerHandoff() {
    }

    /**
     * Injection point, first thing in each push handler that takes VOIP pushes alone. True drops
     * the push. Never throws.
     */
    public static boolean dropCallPush() {
        try {
            HookStatus.invoked(FamilyNames.MESSENGER_ICON);
            return handOff(CALL_PUSH_DROPPED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSENGER_ICON, "Messenger call pushes", failure);
            return false;
        }
    }

    /**
     * Injection point, first thing in the parser every incoming ring goes through. True answers
     * nothing to ring. Never throws.
     */
    public static boolean dropRing() {
        try {
            HookStatus.invoked(FamilyNames.MESSENGER_ICON);
            return handOff(RING_DROPPED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSENGER_ICON, "Messenger rings", failure);
            return false;
        }
    }

    /**
     * Injection point, first thing where Facebook hands a chat notification to its notifiers. True
     * when the notification is one of {@link #CHAT_KINDS} and Messenger takes it, so it isn't
     * posted. Never throws.
     *
     * @param notification the MessagingNotification Facebook is about to post
     */
    public static boolean dropChat(@Nullable Object notification) {
        try {
            HookStatus.invoked(FamilyNames.MESSENGER_ICON);
            if (notification == null || !isChatKind(notification.getClass().getName())) return false;
            return handOff(CHAT_DROPPED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSENGER_ICON, "Messenger chat notifications", failure);
            return false;
        }
    }

    /** Whether a notification of the class named {@code className} is one Messenger posts itself. */
    static boolean isChatKind(@Nullable String className) {
        return className != null && CHAT_KINDS.contains(className);
    }

    /**
     * True, counted under {@code what}, while the switch is on and Messenger is installed and enabled.
     * The switch is read first, so nothing is looked up while it's off.
     */
    private static boolean handOff(String what) {
        if (!Utils.settingsReady() || !Settings.LEAVE_CALLS_AND_CHATS_TO_MESSENGER.get()) return false;
        Context context = Utils.getContext();
        if (context == null || !MessengerCard.lookUp(context.getPackageManager())) {
            HookStatus.counted(FamilyNames.MESSENGER_ICON, NO_MESSENGER);
            return false;
        }
        HookStatus.counted(FamilyNames.MESSENGER_ICON, what);
        return true;
    }
}
