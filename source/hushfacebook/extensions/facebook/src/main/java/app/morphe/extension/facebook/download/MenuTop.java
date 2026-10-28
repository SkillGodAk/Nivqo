/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.view.Menu;
import android.view.MenuItem;

import app.morphe.extension.facebook.settings.Settings;

/**
 * Where a post menu's save row goes. The row under a post holds only icons with counts and gives a
 * new child no room, so Hushfacebook's Download rows ride in the post's three-dot menu. With
 * {@link Settings#POST_MENU_DOWNLOAD_FIRST} on they sit above Facebook's rows, else below them.
 * "Above" means first in the menu's own order. Facebook's bottom sheet then files each row by the
 * kind and section tag Facebook gave it ({@code Vq3.A05} on 583) and shows its tagged top rows
 * before the untagged ones, so ours lead the options list under those (on photo posts the More
 * options page), not the whole sheet.
 *
 * <p>The two kinds of menu sort differently. Facebook's post menu keeps its rows in the order
 * number each was added with, smallest first, so a row goes on top with a number below every
 * other row's (583 on the S22, 2026-10-18: the category below sank Save photo to the bottom).
 * Android's own menu sorts by category first and refuses a negative number, and there category 5
 * (0x50000) ranks ahead of every public category, so a row added in it lands on top.
 */
final class MenuTop {
    private MenuTop() {}

    /** A category no public constant names, which Android's menu sorts before all the others. */
    static final int FIRST_CATEGORY = 0x50000;

    /** Adds [title] to [menu], on top when the switch is on and below Facebook's rows otherwise. */
    static MenuItem add(Menu menu, CharSequence title) {
        if (!Settings.POST_MENU_DOWNLOAD_FIRST.get()) return menu.add(title);
        if (androids(menu)) return menu.add(Menu.NONE, Menu.NONE, FIRST_CATEGORY, title);
        int lowest = 0;
        for (int i = 0; i < menu.size(); i++) {
            MenuItem row = menu.getItem(i);
            if (row != null) lowest = Math.min(lowest, row.getOrder());
        }
        return menu.add(Menu.NONE, Menu.NONE, lowest == Integer.MIN_VALUE ? lowest : lowest - 1, title);
    }

    /** Whether [menu] is one of Android's own, the platform's or AndroidX's, rather than Facebook's. */
    static boolean androids(Menu menu) {
        String name = menu.getClass().getName();
        return name.startsWith("com.android.internal.view.menu.") || name.startsWith("androidx.appcompat.view.menu.")
                || name.startsWith("android.");
    }
}
