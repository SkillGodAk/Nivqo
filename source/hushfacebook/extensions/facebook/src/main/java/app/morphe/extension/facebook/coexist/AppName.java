/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.coexist;

import android.content.Context;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The name App name gives the app (#105), for the recent apps screen. Facebook labels its task with
 * its brand string, which its About and login screens show too, so the patch leaves that string
 * alone and passes the task's label through here instead. It's only patched in when a name was
 * picked, and a paused Hushfacebook keeps it, the way the name under the icon stays.
 */
public final class AppName {
    private AppName() {
    }

    /**
     * The app's own label, the name under its icon, in place of [facebooks]. Facebook's label when
     * the app's can't be read.
     */
    public static String taskLabel(@Nullable String facebooks) {
        try {
            Context context = Utils.getContext();
            if (context == null) return facebooks;
            CharSequence label = context.getApplicationInfo().loadLabel(context.getPackageManager());
            return label == null || label.length() == 0 ? facebooks : label.toString();
        } catch (Throwable failure) {
            Logger.printException(() -> "App name: the app's label couldn't be read for the recent apps screen", failure);
            return facebooks;
        }
    }
}
