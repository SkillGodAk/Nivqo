/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package com.facebook.rti.notifgateway.streamhandler;

/** Kept public getter, including when an implementation's class isn't public. */
public abstract class NotifGatewayStreamHandler {
    public abstract String getStreamName();
}
