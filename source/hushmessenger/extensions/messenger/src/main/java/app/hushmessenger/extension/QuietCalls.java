package app.hushmessenger.extension;

import android.app.Notification;
import android.content.Context;

/**
 * Quiet incoming calls, the notification half. Messenger's call service hands each call notification here just before
 * it posts it and moves to the foreground with it. With the switch on, an incoming call's notification goes back as
 * the only child of a group that alerts through its summary, and there is no summary. Android then neither sounds it
 * nor shows it on top of the screen, and SystemUI doesn't open its full-screen call screen over the lock screen. It
 * waits in the shade with Answer and Decline. The full-screen intent itself stays on the notification, since Android
 * refuses a call notification that has neither one nor a foreground service behind it.
 */
public final class QuietCalls {
    static final String GROUP = "hush_quiet_call";
    /** Notification.EXTRA_CALL_TYPE and CallStyle.CALL_TYPE_INCOMING, both Android 12. */
    static final String CALL_TYPE = "android.callType";
    static final int INCOMING = 1;

    private QuietCalls() {}

    /** The notification to post: [notification] itself, made silent when it's an incoming call and the switch is on. */
    public static Notification incomingNotification(Notification notification, Context context) {
        if (notification == null || context == null) return notification;
        try {
            if (!isIncomingCall(notification) || !Settings.quietIncomingCalls()) return notification;
            // The builder adopts the notification and writes into it, so the result is the same object Messenger posts.
            return Notification.Builder.recoverBuilder(context, notification)
                .setGroup(GROUP)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                .build();
        } catch (RuntimeException error) {
            Settings.hookFailed("quiet_calls", "Couldn't quiet the incoming call notification", error);
            return notification;
        }
    }

    /** A call notification that rings: it has a full-screen intent, or its call style says incoming. */
    static boolean isIncomingCall(Notification notification) {
        if (!Notification.CATEGORY_CALL.equals(notification.category)) return false;
        if (notification.fullScreenIntent != null) return true;
        return notification.extras != null && notification.extras.getInt(CALL_TYPE, 0) == INCOMING;
    }
}
