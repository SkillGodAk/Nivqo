/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import com.facebook.messaging.notify.type.NewMessageNotification;

/**
 * Asks the Leave calls and chats to Messenger hooks the way Facebook does, with Messenger installed.
 * Each answers true when Facebook's ring or notification was dropped, which is the switch changing
 * what Facebook would have done.
 */
public final class MessengerHandoffForTests {
    private MessengerHandoffForTests() {
    }

    /** An incoming call's VOIP push reaching one of Facebook's push handlers. */
    public static boolean dropsACallPush() {
        MessengerCardForTests.install(true);
        return MessengerHandoff.dropCallPush();
    }

    /** An incoming ring reaching Facebook's ring parser. */
    public static boolean dropsARing() {
        MessengerCardForTests.install(true);
        return MessengerHandoff.dropRing();
    }

    /** A new message's notification reaching Facebook's notifiers. */
    public static boolean dropsAMessage() {
        MessengerCardForTests.install(true);
        return MessengerHandoff.dropChat(new NewMessageNotification());
    }

    /** A message in Facebook's own Chats, as a push reaching the method that posts Facebook's pushes. */
    public static boolean dropsAChatPush() {
        MessengerCardForTests.install(true);
        return MessengerHandoff.dropChatPush("MESSAGING_IN_BLUE_DIRECT_MESSAGE");
    }
}
