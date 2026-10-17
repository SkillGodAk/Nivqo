/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package com.facebook.events.targetedtab;

import app.morphe.extension.facebook.navigation.FacebookTabs;
import com.facebook.navigation.tabbar.state.model.TabTag;

/** Stands in for the Events tab, under the class name Facebook keeps. */
public final class EventsTab extends TabTag {
    public EventsTab() {
        super(FacebookTabs.EVENTS_ID);
    }
}
