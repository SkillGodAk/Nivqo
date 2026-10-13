package app.hushmessenger.extension;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;

/**
 * Messenger's own chat notifications, read back from inside its process. Messenger tags each one with the chat's
 * ThreadKey text, the same string the notification hook hands over, so a captured message can be matched to what its
 * notification showed: who sent it, the group's name, and the Reply action. Only public notification extras are read.
 */
final class ChatNotifications {
    private ChatNotifications() {}

    /** The newest notification Messenger has up for this chat, or null when there's none (the chat was open, say). */
    static StatusBarNotification find(Context context, String thread) {
        if (context == null || thread == null || thread.isEmpty()) return null;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return null;
        StatusBarNotification newest = null;
        for (StatusBarNotification shown : manager.getActiveNotifications()) {
            if (!thread.equals(shown.getTag())) continue;
            if (newest == null || shown.getPostTime() > newest.getPostTime()) newest = shown;
        }
        return newest;
    }

    /**
     * Who sent [text]: the sender of the newest message in the notification's conversation with that text, else of its
     * newest message, else the notification's title. Null when the notification names nobody.
     */
    static String sender(Notification notification, String text) {
        Bundle extras = notification.extras;
        if (extras == null) return null;
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        String newest = null;
        if (messages != null) {
            for (int i = messages.length - 1; i >= 0; i--) {
                if (!(messages[i] instanceof Bundle)) continue;
                Bundle message = (Bundle) messages[i];
                String name = senderOf(message);
                if (name == null) continue;
                CharSequence body = message.getCharSequence("text");
                if (text != null && body != null && text.contentEquals(body)) return name;
                if (newest == null) newest = name;
            }
        }
        if (newest != null) return newest;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        return title == null || title.length() == 0 ? null : title.toString();
    }

    /** The group's name for a group chat's notification, null for a one-to-one chat. */
    static String conversation(Notification notification) {
        Bundle extras = notification.extras;
        CharSequence title = extras == null ? null : extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        return title == null || title.length() == 0 ? null : title.toString();
    }

    private static String senderOf(Bundle message) {
        Object person = message.get("sender_person");
        CharSequence name = null;
        if (Build.VERSION.SDK_INT >= 28 && person instanceof android.app.Person) name = ((android.app.Person) person).getName();
        else if (person instanceof Bundle) name = ((Bundle) person).getCharSequence("name");
        if (name == null) name = message.getCharSequence("sender");
        return name == null || name.length() == 0 ? null : name.toString();
    }

    /**
     * Sends [reply] through the notification's own Reply action, the same way a reply typed into the notification shade
     * goes, so Messenger handles the chat, encrypted or not. Returns false when the notification has no reply action.
     */
    static boolean reply(Context context, Notification notification, String reply) throws PendingIntent.CanceledException {
        if (notification.actions == null) return false;
        Notification.Action chosen = null;
        for (Notification.Action action : notification.actions) {
            if (action.actionIntent == null || !acceptsText(action.getRemoteInputs())) continue;
            if (chosen == null || (Build.VERSION.SDK_INT >= 28 && action.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY)) {
                chosen = action;
            }
        }
        if (chosen == null) return false;
        RemoteInput[] inputs = chosen.getRemoteInputs();
        Bundle results = new Bundle();
        for (RemoteInput input : inputs) if (input.getAllowFreeFormInput()) results.putCharSequence(input.getResultKey(), reply);
        Intent fill = new Intent();
        RemoteInput.addResultsToIntent(inputs, fill, results);
        chosen.actionIntent.send(context, 0, fill);
        return true;
    }

    private static boolean acceptsText(RemoteInput[] inputs) {
        if (inputs == null) return false;
        for (RemoteInput input : inputs) if (input.getAllowFreeFormInput()) return true;
        return false;
    }
}
